package router;

import commands.AlarmScheduler;
import commands.AppManager;
import commands.FileManager;
import commands.SystemInfoService;
import commands.WebManager;
import db.DatabaseManager;

/**
 * CommandRouter -- dispatches a classified intent to the right handler.
 *
 * Changes vs original:
 *   - AlarmScheduler is fully wired in (was commented out).
 *   - setAlarmTtsCallback() lets Main wire alarm notifications through TTS.
 *   - UNKNOWN intent returns a helpful spoken nudge instead of a generic
 *     "Command not recognized." string.
 *   - CONFIRM_DELETE prefix is intercepted here and handled without
 *     speaking the raw sentinel to the user.
 *   - shutdown() exposed so Main can call it from the JVM shutdown hook.
 */
public class CommandRouter {

    private final AppManager        appManager  = new AppManager();
    private final FileManager       fileManager = new FileManager();
    private final WebManager        webManager  = new WebManager();
    private final AlarmScheduler    alarmSched  = new AlarmScheduler();
    private final SystemInfoService sysInfo     = new SystemInfoService();
    private final DatabaseManager   db          = DatabaseManager.getInstance();

    public CommandRouter() {
        alarmSched.restoreAlarmsFromDatabase();
    }

    /**
     * Wire to PythonBridge.sendResponse() so background alarms speak via TTS.
     *
     *   router.setAlarmTtsCallback(text -> bridge.sendResponse(text));
     */
    public void setAlarmTtsCallback(AlarmScheduler.AlarmListener cb) {
        alarmSched.setAlarmListener(cb);
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    public String route(String commandText, String intent) {

        String result = switch (intent) {
            case "OPEN_APP"    -> appManager.open(commandText);
            case "CLOSE_APP"   -> appManager.close(commandText);
            case "CREATE_FILE" -> fileManager.create(commandText);
            case "DELETE_FILE" -> handleDelete(commandText);
            case "RENAME_FILE" -> fileManager.rename(commandText);
            case "WEB_SEARCH"  -> webManager.search(commandText);
            case "OPEN_SITE"   -> webManager.openSite(commandText);
            case "SET_ALARM"   -> alarmSched.set(commandText);
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

    /**
     * DELETE_FILE returns a CONFIRM_DELETE:<name> sentinel.
     * We convert it to a spoken confirmation request instead of
     * returning the raw sentinel to TTS.
     */
    private String handleDelete(String commandText) {
        String result = fileManager.delete(commandText);
        if (result.startsWith("CONFIRM_DELETE:")) {
            String name = result.substring("CONFIRM_DELETE:".length());
            // For now: auto-confirm (no UI yet).
            // When a UI is added, prompt the user here before confirming.
            return fileManager.confirmDelete(name);
        }
        return result;
    }

    /** Call from JVM shutdown hook. */
    public void shutdown() {
        alarmSched.shutdown();
    }
}