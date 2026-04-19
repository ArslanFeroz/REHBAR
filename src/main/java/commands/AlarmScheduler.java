//package commands;
//
//import db.DatabaseManager;
//import javafx.application.Platform;
//import javafx.scene.control.Alert;
//import javafx.scene.control.ButtonType;
//
//import java.awt.Toolkit;
//import java.time.Duration;
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.Executors;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.ScheduledFuture;
//import java.util.concurrent.TimeUnit;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//public class AlarmScheduler {
//
//    private final ScheduledExecutorService scheduler =
//            Executors.newScheduledThreadPool(10);
//
//    // Stores scheduled tasks so we can cancel them later
//    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
//
//    private static final DateTimeFormatter DISPLAY_FORMAT =
//            DateTimeFormatter.ofPattern("h:mm a");
//
//    // ── Called by CommandRouter ──
//    public String set(String command) {
//        LocalDateTime triggerTime = parseTime(command);
//
//        if (triggerTime == null) return "NEED_TIME";
//
//        if (triggerTime.isBefore(LocalDateTime.now())) {
//            triggerTime = triggerTime.plusDays(1);
//            return "That time has already passed today. I have set the alarm for tomorrow at "
//                    + triggerTime.format(DISPLAY_FORMAT);
//        }
//
//        String label = extractLabel(command);
//
//        // Save to DB so it survives app restarts
//        DatabaseManager.getInstance().saveAlarm(label, triggerTime);
//
//        // Schedule the background task
//        scheduleAlarm(label, triggerTime);
//
//        return "Alarm set for " + triggerTime.format(DISPLAY_FORMAT)
//                + (label.equals("Alarm") ? "." : " — " + label + ".");
//    }
//
//    // ── Schedules the actual background task ──
//    private void scheduleAlarm(String label, LocalDateTime triggerTime) {
//        long delayMillis = Duration.between(LocalDateTime.now(), triggerTime).toMillis();
//
//        ScheduledFuture<?> task = scheduler.schedule(
//                () -> triggerAlarm(label),
//                delayMillis,
//                TimeUnit.MILLISECONDS
//        );
//
//        // Store so we can cancel later
//        scheduledTasks.put(label, task);
//    }
//
//    // ── Fires when alarm time arrives ──
//    private void triggerAlarm(String label) {
//        // Beep works on any thread
//        Toolkit.getDefaultToolkit().beep();
//
//        // UI update MUST be on JavaFX thread
////        Platform.runLater(() -> showAlarmNotification(label));   uncomment this later on
//        // Mark triggered in DB
//        DatabaseManager.getInstance().markAlarmTriggered(label);
//
//        // Remove from tracking map
//        scheduledTasks.remove(label);
//    }
//
//    // ── Shows the popup notification ──
//    private void showAlarmNotification(String label) {
//        Alert alert = new Alert(Alert.AlertType.INFORMATION);
//        alert.setTitle("RAHBAR — Alarm");
//        alert.setHeaderText("⏰  Time is up!");
//        alert.setContentText(label.equals("Alarm")
//                ? "Your alarm is ringing!"
//                : "Reminder: " + label);
//        alert.getButtonTypes().setAll(ButtonType.OK);
//
//        // Beep again when popup appears
//        Toolkit.getDefaultToolkit().beep();
//
//        // show() is non-blocking — app keeps running
//        alert.show();
//    }
//
//    // ── Call this on app startup to reschedule DB alarms ──
//    // Without this, alarms set before app restart silently never fire
//    public void restoreAlarmsFromDatabase() {
//        var alarms = DatabaseManager.getInstance().getAllAlarms();
//        int restored = 0;
//
//        for (DatabaseManager.Alarm alarm : alarms) {
//            if (alarm.triggerTime.isAfter(LocalDateTime.now())) {
//                scheduleAlarm(alarm.label, alarm.triggerTime);
//                restored++;
//            } else {
//                // Alarm time passed while app was closed — clean it up
//                DatabaseManager.getInstance().markAlarmTriggered(alarm.label);
//            }
//        }
//        System.out.println("Restored " + restored + " alarm(s) from database.");
//    }
//
//    // ── Call this when app closes ──
//    public void shutdown() {
//        scheduler.shutdown();
//        try {
//            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
//                scheduler.shutdownNow();
//            }
//        } catch (InterruptedException e) {
//            scheduler.shutdownNow();
//            Thread.currentThread().interrupt();
//        }
//    }
//
//    // ═══════════════════════════════════════════
//    //  HELPER METHODS
//    // ═══════════════════════════════════════════
//
//    private String extractLabel(String command) {
//        String cmd = command.toLowerCase().trim();
//
//        cmd = cmd.replaceAll("^(please|can you|could you|hey rahbar|rahbar)\\s+", "");
//
//        // "remind me to drink water at 3pm" → "Drink water"
//        if (cmd.contains("remind me to ")) {
//            String after = cmd.substring(cmd.indexOf("remind me to ") + 13);
//            if (after.contains(" at ")) {
//                return capitalise(after.substring(0, after.indexOf(" at ")).trim());
//            }
//            return capitalise(after.replaceAll("\\d{1,2}(:\\d{2})?\\s*(am|pm)?", "").trim());
//        }
//
//        // "set reminder for meeting at 2pm" → "Meeting"
//        if (cmd.contains("reminder for ")) {
//            String after = cmd.substring(cmd.indexOf("reminder for ") + 13);
//            if (after.contains(" at ")) {
//                return capitalise(after.substring(0, after.indexOf(" at ")).trim());
//            }
//            return capitalise(after.trim());
//        }
//
//        // Generic alarm — no label needed
//        if (cmd.contains("set alarm") || cmd.contains("alarm for")) {
//            return "Alarm";
//        }
//
//        return "Reminder";
//    }
//
//    private LocalDateTime parseTime(String command) {
//        String cmd = command.toLowerCase().trim();
//
//        try {
//            // Handle "in X minutes" or "in X hours"
//            if (cmd.contains(" in ")) {
//                return parseRelativeTime(cmd);
//            }
//
//            // Handle special words
//            if (cmd.contains("noon")) {
//                return LocalDateTime.now().withHour(12).withMinute(0).withSecond(0).withNano(0);
//            }
//            if (cmd.contains("midnight")) {
//                return LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
//            }
//
//            // Main pattern — handles: 7am, 7:30am, 7 30 am, 14:00
//            Pattern pattern = Pattern.compile(
//                    "(\\d{1,2})(?::(\\d{2})|\\s+(\\d{2}))?\\s*(am|pm|AM|PM)?"
//            );
//            Matcher matcher = pattern.matcher(cmd);
//
//            if (!matcher.find()) return null;
//
//            int hour   = Integer.parseInt(matcher.group(1));
//            int minute = 0;
//
//            if (matcher.group(2) != null) {
//                minute = Integer.parseInt(matcher.group(2));
//            } else if (matcher.group(3) != null) {
//                minute = Integer.parseInt(matcher.group(3));
//            }
//
//            // Validate
//            if (hour > 23 || minute > 59) return null;
//
//            String ampm = matcher.group(4);
//            if (ampm != null) {
//                if (ampm.equalsIgnoreCase("pm") && hour != 12) hour += 12;
//                if (ampm.equalsIgnoreCase("am") && hour == 12) hour  = 0;
//            } else {
//                // No AM/PM — if hour is 1-6 assume PM
//                // e.g. "alarm for 3" → 3 PM, not 3 AM
//                if (hour > 0 && hour < 7) hour += 12;
//            }
//
//            return LocalDateTime.now()
//                    .withHour(hour)
//                    .withMinute(minute)
//                    .withSecond(0)
//                    .withNano(0);
//
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    // ── Handles "in 30 minutes" / "in 2 hours" ──
//    private LocalDateTime parseRelativeTime(String cmd) {
//        try {
//            Pattern pattern = Pattern.compile(
//                    "in\\s+(\\d+)\\s+(minute|minutes|hour|hours)"
//            );
//            Matcher matcher = pattern.matcher(cmd);
//
//            if (!matcher.find()) return null;
//
//            int amount = Integer.parseInt(matcher.group(1));
//            String unit = matcher.group(2);
//
//            if (unit.startsWith("minute")) return LocalDateTime.now().plusMinutes(amount);
//            if (unit.startsWith("hour"))   return LocalDateTime.now().plusHours(amount);
//
//            return null;
//
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    private String capitalise(String text) {
//        if (text == null || text.isEmpty()) return text;
//        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
//    }
//}