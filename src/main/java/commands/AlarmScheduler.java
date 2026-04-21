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

/**
 * AlarmScheduler -- sets, persists, and fires alarms from voice commands.
 *
 * This class was previously entirely commented out.  Now fully implemented.
 *
 * Features:
 *   - Natural-language time parsing (absolute and relative)
 *   - ScheduledExecutorService for background firing
 *   - SQLite persistence so alarms survive app restarts
 *   - restoreAlarmsFromDatabase() to reschedule on startup
 *   - AlarmListener callback interface -- CommandRouter/Main wires this
 *     to PythonBridge.sendResponse() so alarms speak through TTS
 *   - No JavaFX dependency (popup removed; TTS callback used instead)
 *   - Thread-safe via ConcurrentHashMap
 */
public class AlarmScheduler {

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

    public void setAlarmListener(AlarmListener l) {
        this.listener = l;
    }

    /**
     * Parse command and schedule an alarm.
     * Returns a human-readable confirmation string for TTS.
     */
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

    /**
     * Reload all pending alarms from the DB and reschedule them.
     * Call once on app startup.
     */
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

    /**
     * Parses absolute and relative time expressions.
     * Supports:
     *   7 am, 7:30 am, 14:00, noon, midnight
     *   in 30 minutes, in 2 hours, in 1 hour and 30 minutes
     */
    private LocalDateTime parseTime(String command) {
        String cmd = command.toLowerCase().trim();
        try {
            if (cmd.contains("noon"))
                return today(12, 0);
            if (cmd.contains("midnight"))
                return LocalDateTime.now().plusDays(1)
                        .withHour(0).withMinute(0).withSecond(0).withNano(0);

            // Relative: "in X minutes/hours"
            if (cmd.contains(" in ") || cmd.startsWith("in ")) {
                LocalDateTime rel = parseRelative(cmd);
                if (rel != null) return rel;
            }

            // Absolute: 7am / 7:30am / 7 30 am / 14:00
            Pattern p = Pattern.compile(
                    "(\\d{1,2})(?::(\\d{2})|\\s+(\\d{2}))?\\s*(am|pm|AM|PM)?");
            Matcher m = p.matcher(cmd);
            if (!m.find()) return null;

            int hour   = Integer.parseInt(m.group(1));
            int minute = 0;
            if (m.group(2) != null) minute = Integer.parseInt(m.group(2));
            else if (m.group(3) != null) minute = Integer.parseInt(m.group(3));
            if (hour > 23 || minute > 59) return null;

            String ampm = m.group(4);
            if (ampm != null) {
                if (ampm.equalsIgnoreCase("pm") && hour != 12) hour += 12;
                if (ampm.equalsIgnoreCase("am") && hour == 12) hour  = 0;
            } else {
                // No AM/PM: hours 1-6 assumed PM
                if (hour >= 1 && hour <= 6) hour += 12;
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