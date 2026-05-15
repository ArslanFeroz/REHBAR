package commands;

import db.DatabaseManager;

import java.awt.Toolkit;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Schedules, persists, and fires alarms from voice commands; uses ScheduledExecutorService, SQLite persistence, and TTS callback. */
public class AlarmScheduler implements CommandHandler {

    // ── Callback interface ────────────────────────────────────────────────────

    /** Receives TTS text when an alarm fires. Wire to PythonBridge.sendResponse(). */
    public interface AlarmListener {
        void onAlarmFired(String message);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(10);
    private final Map<String, ScheduledFuture<?>> tasks =
            new ConcurrentHashMap<>();
    private AlarmListener listener;

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("h:mm a");

    // ── Public API ────────────────────────────────────────────────────────────

    @Override
    public String execute(String input) { return set(input); }

    public void setAlarmListener(AlarmListener l) {
        this.listener = l;
    }

    /** Parses command, schedules alarm, persists to DB, and returns TTS confirmation string. */
    public String set(String command) {
        LocalDateTime triggerTime = parseTime(command);

        if (triggerTime == null) {
            return "I could not figure out the time. " +
                    "Try saying something like 'set alarm for 7 a.m.' " +
                    "or 'remind me in 30 minutes'.";
        }

        boolean rolledForward = false;
        if (triggerTime.isBefore(LocalDateTime.now())) {
            triggerTime   = triggerTime.plusDays(1);
            rolledForward = true;
        }

        String label = extractLabel(command);

        DatabaseManager.getInstance().saveAlarm(label, triggerTime);
        scheduleAlarm(label, triggerTime);

        String reply = "Alarm set for " + triggerTime.format(DISPLAY);
        if (!label.equals("Alarm") && !label.equals("Reminder"))
            reply += " — " + label;
        if (rolledForward)
            reply += ". That time has passed today so I scheduled it for tomorrow.";
        return reply + ".";
    }

    /** Cancels all pending alarms in memory and in the database. */
    public String cancel() {
        if (tasks.isEmpty()) {
            return "There are no alarms currently set.";
        }
        int count = tasks.size();
        for (ScheduledFuture<?> task : tasks.values()) {
            task.cancel(false);
        }
        tasks.clear();
        DatabaseManager.getInstance().cancelAllAlarms();
        return "Cancelled " + count + (count == 1 ? " alarm." : " alarms.");
    }

    /** Reloads all pending alarms from DB and reschedules them; call once on app startup. */
    public void restoreAlarmsFromDatabase() {
        List<DatabaseManager.Alarm> alarms =
                DatabaseManager.getInstance().getAllAlarms();
        int restored = 0, expired = 0;
        for (DatabaseManager.Alarm alarm : alarms) {
            if (alarm.triggerTime.isAfter(LocalDateTime.now())) {
                scheduleAlarm(alarm.label, alarm.triggerTime);
                restored++;
            } else {
                DatabaseManager.getInstance().markAlarmTriggered(alarm.label);
                expired++;
            }
        }
        System.out.printf("[AlarmScheduler] Restored %d alarm(s), discarded %d expired.%n",
                restored, expired);
    }

    /** Call from JVM shutdown hook. */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS))
                scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Internal scheduling ───────────────────────────────────────────────────

    private void scheduleAlarm(String label, LocalDateTime triggerTime) {
        long delayMs = Math.max(0,
                Duration.between(LocalDateTime.now(), triggerTime).toMillis());

        ScheduledFuture<?> task = scheduler.schedule(
                () -> fireAlarm(label), delayMs, TimeUnit.MILLISECONDS);

        tasks.put(label, task);
        System.out.printf("[AlarmScheduler] Scheduled '%s' in %d seconds.%n",
                label, delayMs / 1000);
    }

    private void fireAlarm(String label) {
        System.out.println("[AlarmScheduler] ALARM FIRED: " + label);

        try { Toolkit.getDefaultToolkit().beep(); }
        catch (Exception ignored) {}

        String message = label.equalsIgnoreCase("Alarm")
                ? "Your alarm is ringing!"
                : "Reminder: " + label + ". Time is up!";

        if (listener != null) {
            try { listener.onAlarmFired(message); }
            catch (Exception e) {
                System.out.println("[AlarmScheduler] TTS callback error: " + e.getMessage());
            }
        }

        DatabaseManager.getInstance().markAlarmTriggered(label);
        tasks.remove(label);
    }

    // ── Label extraction ──────────────────────────────────────────────────────

    private String extractLabel(String command) {
        String cmd = command.toLowerCase().trim();
        cmd = cmd.replaceAll(
                "^(please|can you|could you|hey rahbar|rahbar)\\s+", "");

        if (cmd.contains("remind me to ")) {
            String after = cmd.substring(cmd.indexOf("remind me to ") + 13);
            if (after.contains(" at "))
                after = after.substring(0, after.indexOf(" at "));
            after = after.replaceAll("\\d{1,2}(:\\d{2})?\\s*(am|pm)?$", "").trim();
            return after.isEmpty() ? "Reminder" : capitalise(after);
        }
        if (cmd.contains("reminder for ")) {
            String after = cmd.substring(cmd.indexOf("reminder for ") + 13);
            if (after.contains(" at "))
                after = after.substring(0, after.indexOf(" at "));
            return after.trim().isEmpty() ? "Reminder" : capitalise(after.trim());
        }
        return "Alarm";
    }

    // ── Time parsing ──────────────────────────────────────────────────────────

    /** Parses absolute (7am, 7:30am, 14:00, noon, midnight) and relative (in 30 minutes, in 2 hours) time expressions. */
    private LocalDateTime parseTime(String command) {
        String cmd = command.toLowerCase().trim();
        try {
            if (cmd.contains("noon"))
                return today(12, 0);
            if (cmd.contains("midnight"))
                return LocalDateTime.now().plusDays(1)
                        .withHour(0).withMinute(0).withSecond(0).withNano(0);

            // Normalize ASR variants: "p.m." / "p m" -> "pm",  "a.m." / "a m" -> "am"
            cmd = cmd.replaceAll("p\\.\\s*m\\.?", "pm")
                     .replaceAll("a\\.\\s*m\\.?", "am")
                     .replaceAll("\\bp\\s+m\\b",   "pm")
                     .replaceAll("\\ba\\s+m\\b",   "am");

            // Relative: "in X minutes/hours"
            if (cmd.contains(" in ") || cmd.startsWith("in ")) {
                LocalDateTime rel = parseRelative(cmd);
                if (rel != null) return rel;
            }

            // Absolute: 7am / 7:30am / 7 30 am / 14:00
            Pattern p = Pattern.compile(
                    "(\\d{1,2})(?::(\\d{2})|\\s+(\\d{2}))?\\s*(am|pm)?");
            Matcher m = p.matcher(cmd);
            if (!m.find()) return null;

            int hour   = Integer.parseInt(m.group(1));
            int minute = 0;
            if (m.group(2) != null) minute = Integer.parseInt(m.group(2));
            else if (m.group(3) != null) minute = Integer.parseInt(m.group(3));
            if (hour > 23 || minute > 59) return null;

            String ampm = m.group(4);
            if (ampm != null) {
                if (ampm.equals("pm") && hour != 12) hour += 12;
                if (ampm.equals("am") && hour == 12) hour  = 0;
            } else if (hour >= 1 && hour <= 11) {
                // No AM/PM detected (ASR dropped it): if the AM time has already
                // passed but PM hasn't, infer PM rather than rolling to tomorrow.
                LocalDateTime amTime = today(hour, minute);
                LocalDateTime pmTime = today(hour + 12, minute);
                LocalDateTime now    = LocalDateTime.now();
                if (amTime.isBefore(now) && pmTime.isAfter(now)) {
                    return pmTime;
                }
            }
            return today(hour, minute);

        } catch (Exception e) { return null; }
    }

    private LocalDateTime parseRelative(String cmd) {
        LocalDateTime result = LocalDateTime.now();
        boolean found = false;

        Matcher hours = Pattern.compile("(\\d+)\\s*hour").matcher(cmd);
        if (hours.find()) {
            result = result.plusHours(Long.parseLong(hours.group(1)));
            found  = true;
        }
        Matcher mins = Pattern.compile("(\\d+)\\s*min").matcher(cmd);
        if (mins.find()) {
            result = result.plusMinutes(Long.parseLong(mins.group(1)));
            found  = true;
        }
        return found ? result : null;
    }

    private LocalDateTime today(int hour, int minute) {
        return LocalDateTime.now()
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    private String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}