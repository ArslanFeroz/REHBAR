package router;

import commands.AlarmScheduler;
import commands.AppManager;
import commands.FileManager;
import commands.SystemInfoService;
import commands.WebManager;
import db.DatabaseManager;

/** Dispatches classified intents to the correct command handler; supports confirm-delete gate and alarm TTS callback. */
public class CommandRouter {

    private final AppManager        appManager  = new AppManager();
    private final FileManager       fileManager = new FileManager();
    private final WebManager        webManager  = new WebManager();
    private final AlarmScheduler    alarmSched  = new AlarmScheduler();
    private final SystemInfoService sysInfo     = new SystemInfoService();
    private final DatabaseManager   db          = DatabaseManager.getInstance();

    // ── Confirm-delete state machine ──────────────────────────────────────────
    /** Non-null when we are waiting for the user to say "yes" or "no". */
    private volatile String pendingDeleteName = null;

    public CommandRouter() {
        alarmSched.restoreAlarmsFromDatabase();
    }

    /** Wires a callback to PythonBridge.sendResponse() so background alarms speak via TTS. */
    public void setAlarmTtsCallback(AlarmScheduler.AlarmListener cb) {
        alarmSched.setAlarmListener(cb);
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    public String route(String commandText, String intent) {

        // ── Confirm-delete intercept: if we are waiting for a yes/no reply ────
        if (pendingDeleteName != null) {
            return handleConfirmReply(commandText);
        }

        String result = switch (intent) {
            case "OPEN_APP"    -> appManager.open(commandText);
            case "CLOSE_APP"   -> appManager.close(commandText);
            case "CREATE_FILE" -> fileManager.create(commandText);
            case "DELETE_FILE" -> handleDelete(commandText);
            case "RENAME_FILE" -> fileManager.rename(commandText);
            case "WEB_SEARCH"  -> webManager.search(commandText);
            case "OPEN_SITE"   -> webManager.openSite(commandText);
            case "SET_ALARM"    -> alarmSched.set(commandText);
            case "CANCEL_ALARM" -> alarmSched.cancel();
            case "SYSTEM_INFO" -> sysInfo.query(commandText);
            case "UNKNOWN"     ->
                    "I didn't quite catch that. Could you rephrase your command?";
            default ->
                    "I don't know how to handle that command type: " + intent;
        };

        // Log everything except UNKNOWN (noise in the DB)
        if (!intent.equals("UNKNOWN")) {
            db.logCommand(commandText, intent,
                    result.toLowerCase().contains("error") ? "FAILED" : "SUCCESS");
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete with optional confirmation gate
    // ─────────────────────────────────────────────────────────────────────────

    /** Asks for spoken confirmation before deleting (if pref_confirm_delete=true), otherwise deletes immediately. */
    private String handleDelete(String commandText) {
        String raw = fileManager.delete(commandText);

        if (!raw.startsWith("CONFIRM_DELETE:")) {
            // FileManager handled it without needing confirmation (e.g. not found)
            return raw;
        }

        String name = raw.substring("CONFIRM_DELETE:".length());

        // Check user preference
        boolean confirmRequired;
        try {
            confirmRequired = !"false".equals(db.getSetting("pref_confirm_delete"));
        } catch (Exception e) {
            confirmRequired = true; // safe default
        }

        if (confirmRequired) {
            pendingDeleteName = name;
            return "Are you sure you want to delete " + name + "? Say yes to confirm or no to cancel.";
        } else {
            // Auto-confirm
            return fileManager.confirmDelete(name);
        }
    }

    /** Returns true when waiting for a yes/no delete confirmation reply. */
    public boolean hasPendingConfirmation() {
        return pendingDeleteName != null;
    }

    /** Handles yes/no reply for a pending delete; always resets pending state. */
    private String handleConfirmReply(String commandText) {
        String name = pendingDeleteName;
        pendingDeleteName = null; // always reset

        String lower = commandText.trim().toLowerCase();
        if (lower.startsWith("yes") || lower.startsWith("yeah") || lower.startsWith("yep")
                || lower.equals("confirm") || lower.equals("sure") || lower.equals("do it")
                || lower.equals("ok") || lower.equals("okay")) {
            String result = fileManager.confirmDelete(name);
            db.logCommand("delete " + name, "DELETE_FILE",
                    result.toLowerCase().contains("error") ? "FAILED" : "SUCCESS");
            return result;
        } else {
            // Cancelled
            db.logCommand("delete " + name + " (cancelled)", "DELETE_FILE", "SUCCESS");
            return "Deletion of " + name + " cancelled.";
        }
    }

    /** Call from JVM shutdown hook. */
    public void shutdown() {
        alarmSched.shutdown();
    }
}
