import bridge.PythonBridge;
import com.google.gson.JsonObject;
import db.DatabaseManager;
import router.CommandRouter;

/** Headless entry point: DB → CommandRouter → PythonBridge → listening loop. */
public class Main {

    private static PythonBridge  bridge;
    private static CommandRouter router;
    private static DatabaseManager db;

    private static final int ERROR_WARN_THRESHOLD = 5;

    public static void main(String[] args) {

        printBanner("RAHBAR — Starting Up");

        // ── 1. Database ───────────────────────────────────────────────────────
        section("1/3", "Initializing Database");
        try {
            db = DatabaseManager.getInstance();
            db.logCommand("SYSTEM_START", "SYSTEM", "SUCCESS");
            ok("Database ready");
        } catch (Exception e) {
            fail("Database failed: " + e.getMessage());
            System.exit(1);
        }

        // ── 2. Command Router ─────────────────────────────────────────────────
        section("2/3", "Initializing Command Router");
        try {
            router = new CommandRouter();
            ok("Command Router ready");
        } catch (Exception e) {
            fail("Command Router failed: " + e.getMessage());
            System.exit(1);
        }

        // ── 3. Python Bridge ──────────────────────────────────────────────────
        section("3/3", "Starting Python Bridge (Flask :5000)");
        try {
            bridge = new PythonBridge();
            bridge.start();
            ok("Python Bridge connected");
        } catch (Exception e) {
            fail("Python Bridge failed: " + e.getMessage());
            System.exit(0);
        }

        // ── 4. Wire alarm TTS callback ────────────────────────────────────────
        router.setAlarmTtsCallback(text -> {
            try { bridge.sendResponse(text); }
            catch (Exception e) {
                System.out.println("[Main] Alarm TTS callback error: " + e.getMessage());
            }
        });

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

    // ── Listening loop ────────────────────────────────────────────────────────

    private static void startListeningLoop() {
        Thread listenThread = new Thread(() -> {
            int consecutiveErrors = 0;

            while (true) {
                try {
                    JsonObject cmd = bridge.readCommand(); // null on 204 (no speech yet)
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

    // ── Console helpers ───────────────────────────────────────────────────────

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