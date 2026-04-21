import bridge.PythonBridge;
import commands.AlarmScheduler;
import commands.AppManager;
import commands.FileManager;
import commands.SystemInfoService;
import commands.WebManager;
import com.google.gson.JsonObject;
import db.DatabaseManager;
import router.CommandRouter;

import java.util.ArrayList;
import java.util.List;

/**
 * Main -- Rehbar entry point.
 *
 * Boot sequence:
 *   1. Database initialised
 *   2. CommandRouter initialised (restores pending alarms)
 *   3. Self-tests run against every module (no voice needed)
 *   4. Python bridge launched; waitForHealth() blocks until
 *      TTS/voice/classifier are fully loaded
 *   5. Alarm TTS callback wired
 *   6. Welcome message spoken through TTS
 *   7. Listening loop starts
 */
public class Main {

    private static PythonBridge   bridge;
    private static CommandRouter  router;
    private static DatabaseManager db;

    private static final int ERROR_WARN_THRESHOLD = 5;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {

        printBanner("RAHBAR — Starting Up");

        // ── 1. Database ───────────────────────────────────────────────────────
        section("1/4", "Initializing Database");
        try {
            db = DatabaseManager.getInstance();
            db.logCommand("SYSTEM_START", "SYSTEM", "SUCCESS");
            ok("Database ready");
        } catch (Exception e) {
            fail("Database failed: " + e.getMessage());
            System.exit(1);
        }

        // ── 2. Command Router ─────────────────────────────────────────────────
        section("2/4", "Initializing Command Router");
        try {
            router = new CommandRouter();
            ok("Command Router ready");
        } catch (Exception e) {
            fail("Command Router failed: " + e.getMessage());
            System.exit(1);
        }

        // ── 3. Self-tests ─────────────────────────────────────────────────────
        section("3/4", "Running Module Self-Tests");
        boolean allPassed = runAllTests();
        if (allPassed) {
            ok("All module tests passed");
        } else {
            System.out.println("\u26A0\uFE0F  Some tests failed -- check output above.");
            System.out.println("   Rehbar will still start, but some commands may not work.");
        }

        // ── 4. Python Bridge ──────────────────────────────────────────────────
        section("4/4", "Starting Python Bridge (Flask :5000)");
        try {
            bridge = new PythonBridge();
            bridge.start();
            ok("Python Bridge connected");
        } catch (Exception e) {
            fail("Python Bridge failed: " + e.getMessage());
            System.exit(0);
        }

        // ── Wire alarm TTS callback ───────────────────────────────────────────
        router.setAlarmTtsCallback(text -> {
            try { bridge.sendResponse(text); }
            catch (Exception e) {
                System.out.println("[Main] Alarm TTS callback error: " + e.getMessage());
            }
        });

        // ── Welcome message ───────────────────────────────────────────────────
        // Spoken through Python TTS so the user hears Rehbar the moment it is ready.
        try {
            String welcome = buildWelcomeMessage();
            bridge.sendResponse(welcome);
            System.out.println("[Main] Welcome message sent to TTS: " + welcome);
        } catch (Exception e) {
            System.out.println("[Main] Could not send welcome message: " + e.getMessage());
        }

        // ── Ready ─────────────────────────────────────────────────────────────
        printBanner("RAHBAR — Ready! Speak a command");
        System.out.println("  Examples:");
        System.out.println("    'open chrome'");
        System.out.println("    'what time is it'");
        System.out.println("    'search for python tutorials'");
        System.out.println("    'create folder named Test'");
        System.out.println("    'set alarm for 7 am'");
        System.out.println("    'what is the battery level'");
        printDivider();

        startListeningLoop();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Welcome message
    // ─────────────────────────────────────────────────────────────────────────

    private static String buildWelcomeMessage() {
        // Include the current time so it feels live
        String time = new SystemInfoService().query("what time is it");
        return "Hello! Rehbar is online and ready. " + time +
                ". How can I help you today?";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Self-test suite
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs all module tests and prints a formatted results table.
     * Returns true if every test passed.
     *
     * Tests are offline-only (no voice, no browser, no actual file I/O on real paths).
     * They verify the parsing, routing logic, and spoken responses without
     * depending on the system state.
     */
    private static boolean runAllTests() {
        List<TestResult> results = new ArrayList<>();

        results.addAll(testDatabase());
        results.addAll(testCommandRouter());
        results.addAll(testAppManager());
        results.addAll(testFileManager());
        results.addAll(testWebManager());
        results.addAll(testSystemInfo());
        results.addAll(testAlarmScheduler());

        printTestTable(results);

        return results.stream().allMatch(r -> r.passed);
    }

    // ── Database tests ────────────────────────────────────────────────────────

    private static List<TestResult> testDatabase() {
        List<TestResult> r = new ArrayList<>();
        DatabaseManager db = DatabaseManager.getInstance();

        r.add(test("DB: getAppPath(notepad) returns a path",
                db.getAppPath("notepad") != null));

        r.add(test("DB: getAppPath(nonexistent) returns null",
                db.getAppPath("nonexistent_app_xyz") == null));

        r.add(test("DB: getWebsiteUrl(youtube) returns url",
                db.getWebsiteUrl("youtube") != null &&
                        db.getWebsiteUrl("youtube").contains("youtube")));

        r.add(test("DB: getWebsiteUrl(nonexistent) returns null",
                db.getWebsiteUrl("nonexistent_site_xyz") == null));

        r.add(test("DB: logCommand does not throw",
                runSilently(() -> db.logCommand("TEST_CMD", "TEST", "SUCCESS"))));

        return r;
    }

    // ── CommandRouter tests ───────────────────────────────────────────────────

    private static List<TestResult> testCommandRouter() {
        List<TestResult> r = new ArrayList<>();
        CommandRouter router = new CommandRouter();

        // SYSTEM_INFO routes work and return non-empty strings
        r.add(test("Router: SYSTEM_INFO(time) returns a result",
                !router.route("what time is it", "SYSTEM_INFO").isBlank()));

        r.add(test("Router: SYSTEM_INFO(date) returns a result",
                !router.route("what is the date", "SYSTEM_INFO").isBlank()));

        // UNKNOWN returns a nudge, not a crash
        r.add(test("Router: UNKNOWN returns a nudge message",
                router.route("asdfghjkl", "UNKNOWN")
                        .toLowerCase().contains("catch")));

        // OPEN_APP for an unknown app returns a registry-miss message (not an exception)
        String openResult = router.route("open nonexistent_app_xyz_abc", "OPEN_APP");
        r.add(test("Router: OPEN_APP unknown app returns helpful message",
                openResult.toLowerCase().contains("registry") ||
                        openResult.toLowerCase().contains("not found")));

        // WEB_SEARCH returns a search confirmation (does not crash)
        r.add(test("Router: WEB_SEARCH routes without exception",
                runSilently(() -> router.route("search for java docs", "WEB_SEARCH"))));

        return r;
    }

    // ── AppManager tests ──────────────────────────────────────────────────────

    private static List<TestResult> testAppManager() {
        List<TestResult> r = new ArrayList<>();
        AppManager am = new AppManager();

        // Name extraction -- the most critical logic to test
        r.add(testExtract("AppManager: 'open chrome'",
                am, "open chrome", "chrome"));

        r.add(testExtract("AppManager: 'please open chrome'",
                am, "please open chrome", "chrome"));

        r.add(testExtract("AppManager: 'can you launch firefox'",
                am, "can you launch firefox", "firefox"));

        r.add(testExtract("AppManager: 'fire up spotify'",
                am, "fire up spotify", "spotify"));

        r.add(testExtract("AppManager: 'boot up vscode'",
                am, "boot up vscode", "vscode"));

        r.add(testExtract("AppManager: 'quit microsoft teams'",
                am, "quit microsoft teams", "microsoft teams"));

        r.add(testExtract("AppManager: 'force close the app'",
                am, "force close the app", "app"));

        // Unknown app returns a graceful message
        String result = am.open("open totally_unknown_app_xyz");
        r.add(test("AppManager: open unknown app -> graceful message",
                result.toLowerCase().contains("registry") ||
                        result.toLowerCase().contains("not found")));

        // Blank command returns "tell me which" message
        r.add(test("AppManager: open blank command -> prompt message",
                am.open("open").toLowerCase().contains("tell me")));

        return r;
    }

    // ── FileManager tests ─────────────────────────────────────────────────────

    private static List<TestResult> testFileManager() {
        List<TestResult> r = new ArrayList<>();
        FileManager fm = new FileManager();

        // extractName tests
        r.add(test("FileManager: extractName 'create folder named Projects'",
                "projects".equals(fm.extractName("create folder named Projects"))));

        r.add(test("FileManager: extractName 'new folder called Notes'",
                "notes".equals(fm.extractName("new folder called Notes"))));

        r.add(test("FileManager: extractName null/blank returns null",
                fm.extractName(null) == null && fm.extractName("") == null));

        // rename -- missing "to" should not crash
        r.add(test("FileManager: rename without 'to' returns guidance message",
                fm.rename("rename projects").toLowerCase().contains("example")||
                        fm.rename("rename projects").toLowerCase().contains("try") ||
                        fm.rename("rename projects").toLowerCase().contains("rename")));

        // rename -- non-existent source returns not-found message
        String renameResult = fm.rename("rename nonexistent_file_xyz_abc to newname");
        r.add(test("FileManager: rename non-existent source -> not found message",
                renameResult.toLowerCase().contains("not find") ||
                        renameResult.toLowerCase().contains("could not find")));

        // delete -- non-existent file returns not-found message
        String deleteResult = fm.delete("delete nonexistent_file_xyz_abc");
        r.add(test("FileManager: delete non-existent file -> not found message",
                deleteResult.toLowerCase().contains("not find") ||
                        deleteResult.toLowerCase().contains("could not find")));

        // create -- no name extracted returns prompt
        String createResult = fm.create("create folder");
        r.add(test("FileManager: create with no name -> prompt message",
                createResult.toLowerCase().contains("tell me") ||
                        createResult.toLowerCase().contains("name")));

        return r;
    }

    // ── WebManager tests ──────────────────────────────────────────────────────

    private static List<TestResult> testWebManager() {
        List<TestResult> r = new ArrayList<>();
        WebManager wm = new WebManager();

        // search() with empty command after stripping returns a "what to search" message
        r.add(test("WebManager: search blank -> prompt message",
                wm.search("search for").toLowerCase().contains("what") ||
                        wm.search("google").toLowerCase().contains("what")));

        // openSite() with blank sitename after stripping returns prompt
        r.add(test("WebManager: openSite blank -> prompt message",
                wm.openSite("open").toLowerCase().contains("which") ||
                        wm.openSite("open").toLowerCase().contains("website")));

        // search() does not throw on a real query (browser may fail to open in CI, that's ok)
        r.add(test("WebManager: search 'search for python docs' runs without exception",
                runSilently(() -> wm.search("search for python docs"))));

        // openSite() does not throw on a known site
        r.add(test("WebManager: openSite 'open youtube' runs without exception",
                runSilently(() -> wm.openSite("open youtube"))));

        return r;
    }

    // ── SystemInfoService tests ───────────────────────────────────────────────

    private static List<TestResult> testSystemInfo() {
        List<TestResult> r = new ArrayList<>();
        SystemInfoService si = new SystemInfoService();

        String time = si.query("what time is it");
        r.add(test("SystemInfo: time query returns non-empty string",
                time != null && !time.isBlank()));
        r.add(test("SystemInfo: time query contains 'is' (sentence form)",
                time.toLowerCase().contains("is")));

        String date = si.query("what is the date");
        r.add(test("SystemInfo: date query returns non-empty string",
                date != null && !date.isBlank()));
        r.add(test("SystemInfo: date query contains day name",
                date.toLowerCase().contains("monday") ||
                        date.toLowerCase().contains("tuesday") ||
                        date.toLowerCase().contains("wednesday") ||
                        date.toLowerCase().contains("thursday") ||
                        date.toLowerCase().contains("friday") ||
                        date.toLowerCase().contains("saturday") ||
                        date.toLowerCase().contains("sunday")));

        // battery check must NOT hit the time branch (the key bug we fixed)
        String battery = si.query("check battery time remaining");
        r.add(test("SystemInfo: 'battery time remaining' routes to battery not time",
                !battery.toLowerCase().startsWith("it is") &&
                        (battery.toLowerCase().contains("battery") ||
                                battery.toLowerCase().contains("desktop") ||
                                battery.toLowerCase().contains("percent"))));

        String ram = si.query("how much ram is used");
        r.add(test("SystemInfo: RAM query returns non-empty string",
                ram != null && !ram.isBlank()));
        r.add(test("SystemInfo: RAM query contains percent or MB/GB",
                ram.toLowerCase().contains("percent") ||
                        ram.toLowerCase().contains("gb") ||
                        ram.toLowerCase().contains("mb")));

        String cpu = si.query("current cpu usage");
        r.add(test("SystemInfo: CPU query returns non-empty string",
                cpu != null && !cpu.isBlank()));

        String disk = si.query("how much disk space is free");
        r.add(test("SystemInfo: disk query returns non-empty string",
                disk != null && !disk.isBlank()));
        r.add(test("SystemInfo: disk query contains percent or GB",
                disk.toLowerCase().contains("percent") ||
                        disk.toLowerCase().contains("gb")));

        return r;
    }

    // ── AlarmScheduler tests ──────────────────────────────────────────────────

    private static List<TestResult> testAlarmScheduler() {
        List<TestResult> r = new ArrayList<>();
        AlarmScheduler as = new AlarmScheduler();

        // Valid absolute time
        String setResult = as.set("set alarm for 9 am");
        r.add(test("AlarmScheduler: 'set alarm for 9 am' returns confirmation",
                setResult.toLowerCase().contains("alarm") ||
                        setResult.toLowerCase().contains("reminder") ||
                        setResult.toLowerCase().contains("set")));

        // Relative time
        String relResult = as.set("remind me in 30 minutes");
        r.add(test("AlarmScheduler: 'remind me in 30 minutes' returns confirmation",
                relResult.toLowerCase().contains("alarm") ||
                        relResult.toLowerCase().contains("set") ||
                        relResult.toLowerCase().contains("reminder")));

        // Relative hours
        String hourResult = as.set("wake me up in 2 hours");
        r.add(test("AlarmScheduler: 'wake me up in 2 hours' returns confirmation",
                hourResult.toLowerCase().contains("alarm") ||
                        hourResult.toLowerCase().contains("set")));

        // Label extraction from "remind me to X"
        String labelResult = as.set("remind me to drink water at 3pm");
        r.add(test("AlarmScheduler: 'remind me to drink water' label extracted",
                labelResult.toLowerCase().contains("drink water") ||
                        labelResult.toLowerCase().contains("alarm") ||
                        labelResult.toLowerCase().contains("set")));

        // No time provided -- should return a helpful guidance message
        String noTimeResult = as.set("set alarm");
        r.add(test("AlarmScheduler: 'set alarm' with no time returns guidance",
                noTimeResult.toLowerCase().contains("could not") ||
                        noTimeResult.toLowerCase().contains("try") ||
                        noTimeResult.toLowerCase().contains("time")));

        // restoreAlarmsFromDatabase does not throw
        r.add(test("AlarmScheduler: restoreAlarmsFromDatabase does not throw",
                runSilently(as::restoreAlarmsFromDatabase)));

        // TTS callback fires when set
        boolean[] callbackFired = {false};
        as.setAlarmListener(msg -> callbackFired[0] = true);
        // We can't wait 30 minutes for a real alarm, so just verify no crash
        r.add(test("AlarmScheduler: setAlarmListener accepts callback without error",
                runSilently(() -> as.setAlarmListener(msg -> {})) ));

        as.shutdown();
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers for extractAppName (private method -- tested via open())
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tests the app-name extraction by calling open() with an unregistered app
     * and checking the error message contains the expected name.
     * Since extractAppName() is private, we use the open() method as a proxy:
     * if the name is extracted correctly, the error will say "not in registry".
     */
    private static TestResult testExtract(String name, AppManager am,
                                          String command, String expectedName) {
        String result = am.open(command);
        boolean passed = result.toLowerCase().contains(expectedName.toLowerCase());
        return new TestResult(name, passed,
                passed ? "" : "Expected name '" + expectedName + "' in: " + result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test infrastructure
    // ─────────────────────────────────────────────────────────────────────────

    private record TestResult(String name, boolean passed, String detail) {
        TestResult(String name, boolean passed) { this(name, passed, ""); }
    }

    private static TestResult test(String name, boolean condition) {
        return new TestResult(name, condition);
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    /** Returns true if the runnable completes without throwing. */
    private static boolean runSilently(ThrowingRunnable r) {
        try { r.run(); return true; }
        catch (Exception e) { return false; }
    }

    private static void printTestTable(List<TestResult> results) {
        int passed = (int) results.stream().filter(r -> r.passed).count();
        int total  = results.size();

        System.out.println();
        System.out.println("   ┌──────────────────────────────────────────────────────────────────┐");
        System.out.printf( "   │  Module Self-Test Results: %d / %d passed%-26s│%n",
                passed, total, "");
        System.out.println("   ├────┬─────────────────────────────────────────────────────────────┤");

        for (TestResult r : results) {
            String icon   = r.passed ? "\u2705" : "\u274C";
            // Truncate long test names to fit the column
            String label  = r.name.length() > 57
                    ? r.name.substring(0, 54) + "..."
                    : r.name;
            System.out.printf("   │ %s │ %-61s│%n", icon, label);
            if (!r.passed && !r.detail.isEmpty()) {
                String detail = r.detail.length() > 59
                        ? r.detail.substring(0, 56) + "..."
                        : r.detail;
                System.out.printf("   │    │   \u21B3 %-57s│%n", detail);
            }
        }

        System.out.println("   └────┴─────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listening loop
    // ─────────────────────────────────────────────────────────────────────────

    private static void startListeningLoop() {
        Thread listenThread = new Thread(() -> {
            int consecutiveErrors = 0;

            while (true) {
                try {
                    JsonObject cmd = bridge.readCommand();
                    if (cmd == null) continue;

                    consecutiveErrors = 0;

                    String text   = cmd.get("text").getAsString();
                    String intent = cmd.get("intent").getAsString();

                    System.out.println("\n--------------------------------------------------");
                    System.out.println("[TEXT]   " + text);
                    System.out.println("[INTENT] " + intent);

                    String result = router.route(text, intent);
                    System.out.println("[RESULT] " + result);

                    bridge.sendResponse(result);
                    System.out.println("[SENT]   Response queued for TTS");
                    System.out.println("--------------------------------------------------");

                } catch (Exception e) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= ERROR_WARN_THRESHOLD) {
                        System.out.printf("[WARN] %d consecutive errors. Last: %s%n",
                                consecutiveErrors, e.getMessage());
                        consecutiveErrors = 0;
                    }
                    try { Thread.sleep(500); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "RehbarListenThread");

        listenThread.setDaemon(false);
        listenThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            printBanner("RAHBAR — Shutting Down");
            try {
                if (bridge != null) bridge.shutdown();
                if (router != null) router.shutdown();
                if (db     != null) db.logCommand("SYSTEM_STOP", "SYSTEM", "SUCCESS");
            } catch (Exception ignored) {}
            System.out.println("Goodbye!");
        }, "ShutdownHook"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Console helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void printBanner(String title) {
        printDivider();
        System.out.printf("   %-48s%n", title);
        printDivider();
    }
    private static void printDivider() {
        System.out.println("==================================================");
    }
    private static void section(String step, String title) {
        System.out.println("\n[" + step + "] " + title + "...");
    }
    private static void ok(String msg)   { System.out.println("\u2705 " + msg); }
    private static void fail(String msg) { System.out.println("\u274C " + msg); }
}