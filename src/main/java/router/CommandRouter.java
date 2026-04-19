package router;

import commands.AppManager;
import commands.FileManager;
import commands.SystemInfoService;
import commands.WebManager;
import db.DatabaseManager;

public class CommandRouter {
    private AppManager appManager = new AppManager();
    private FileManager fileManager = new FileManager();
    private WebManager webManager = new WebManager();
//    private AlarmScheduler alarmScheduler = new AlarmScheduler();
    private SystemInfoService sysInfo = new SystemInfoService();
    private DatabaseManager db = DatabaseManager.getInstance();

    public String route(String commandText, String intent) {
        String result;
        switch (intent) {
            case "OPEN_APP"   -> result = appManager.open(commandText);
            case "CLOSE_APP"  -> result = appManager.close(commandText);
            case "CREATE_FILE"-> result = fileManager.create(commandText);
            case "DELETE_FILE"-> result = fileManager.delete(commandText);
            case "RENAME_FILE"-> result = fileManager.rename(commandText);
            case "WEB_SEARCH" -> result = webManager.search(commandText);
            case "OPEN_SITE"  -> result = webManager.openSite(commandText);
//            case "SET_ALARM"  -> result = alarmScheduler.set(commandText);
            case "SYSTEM_INFO"-> result = sysInfo.query(commandText);
            default           -> result = "Command not recognized.";
        }
        // Log every command to SQLite
        db.logCommand(commandText, intent, result.contains("Error") ? "FAILED" : "SUCCESS");
        return result;
    }
}
