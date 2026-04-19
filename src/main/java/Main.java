//package main;

import bridge.PythonBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import db.DatabaseManager;
import router.CommandRouter;

public class Main {

    private static PythonBridge bridge;
    private static CommandRouter router;
    private static DatabaseManager db;

    public static void main(String[] args) {

        System.out.println("==================================================");
        System.out.println("              RAHBAR — Starting Up                ");
        System.out.println("==================================================");

        // ── Step 1: Initialize Database ──
        System.out.println("\n[1/3] Initializing Database...");
        try {
            db = DatabaseManager.getInstance();
            System.out.println("✅ Database ready");

            // Quick DB test — log a startup entry
            db.logCommand("SYSTEM_START", "SYSTEM", "SUCCESS");
            System.out.println("✅ Database write test passed");

        } catch (Exception e) {
            System.out.println("❌ Database failed: " + e.getMessage());
            System.out.println("   Check that SQLite driver is in pom.xml");
            System.exit(1);
        }

        // ── Step 2: Initialize Command Router ──
        System.out.println("\n[2/3] Initializing Command Router...");
        try {
            router = new CommandRouter();
            System.out.println("✅ Command Router ready");

            // Quick router test without Python
            System.out.println("\n   Running quick command route tests...");
//            testRouter();

        } catch (Exception e) {
            System.out.println("❌ Command Router failed: " + e.getMessage());
            System.exit(1);
        }

        // ── Step 3: Start Python Bridge ──
        System.out.println("\n[3/3] Starting Python Bridge...");
        try {
            bridge = new PythonBridge();
            bridge.start();
            System.out.println("✅ Python Bridge connected");

        } catch (Exception e) {
            System.out.println("❌ Python Bridge failed: " + e.getMessage());
            System.out.println("   Common causes:");
            System.out.println("   1. venv not found — run setup.bat first");
            System.out.println("   2. bridge.py has an error — check Python console");
            System.out.println("   3. Port 9999 already in use — restart IntelliJ");
            System.exit(0);
        }

        // ── All systems ready ──
        System.out.println("\n==================================================");
        System.out.println("        RAHBAR — Ready! Speak a command           ");
        System.out.println("==================================================");
        System.out.println("  Say things like:");
        System.out.println("  - 'open chrome'");
        System.out.println("  - 'what time is it'");
        System.out.println("  - 'search for python tutorials'");
        System.out.println("  - 'create folder named Test'");
        System.out.println("  - 'what is the battery level'");
        System.out.println("==================================================\n");

        // ── Step 4: Start Main Listening Loop ──
        startListeningLoop();
    }

    // ── Tests CommandRouter directly without needing voice ──
    private static void testRouter() {
        String[][] testCases = {
                // { command,                    intent        }
                { "what time is it",             "SYSTEM_INFO" },
                { "what is the date",            "SYSTEM_INFO" },
                { "check battery",               "SYSTEM_INFO" },
                { "check memory",                "SYSTEM_INFO" },
                { "open chrome",                 "OPEN_APP"    },
                { "open notepad",                "OPEN_APP"    },
                { "search for java tutorials",   "WEB_SEARCH"  },
                { "open youtube",                "OPEN_SITE"   },
                { "create folder named TestDir", "CREATE_FILE" },
                { "close notepad",               "CLOSE_APP"   },
        };

        System.out.println("   ┌─────────────────────────────────────────────────────┐");
        System.out.println("   │            Command Router Test Results               │");
        System.out.println("   ├──────────────────────────────┬──────────────────────┤");
        System.out.println("   │ Command                      │ Result               │");
        System.out.println("   ├──────────────────────────────┼──────────────────────┤");

        for (String[] testCase : testCases) {
            String command = testCase[0];
            String intent  = testCase[1];

            try {
                String result = router.route(command, intent);

                // Truncate long results for display
                String display = result.length() > 20
                        ? result.substring(0, 20) + "..."
                        : result;

                System.out.printf("   │ %-28s │ %-20s │%n", command, display);

            } catch (Exception e) {
                System.out.printf("   │ %-28s │ ❌ %-17s │%n", command, e.getMessage());
            }
        }

        System.out.println("   └──────────────────────────────┴──────────────────────┘");
    }

    // ── Main loop: listens for voice commands from Python ──
    private static void startListeningLoop() {
        Thread listenThread = new Thread(() -> {
            while (true) {
                try {
                    // 1. Wait for Python to send a voice command
                    String json = bridge.readCommand();

                    if (json == null || json.trim().isEmpty()) {
                        continue;
                    }

                    System.out.println("\n--------------------------------------------------");
                    System.out.println("[RECEIVED] " + json);

                    // 2. Parse JSON from Python
                    JsonObject obj    = JsonParser.parseString(json).getAsJsonObject();
                    String text       = obj.get("text").getAsString();
                    String intent     = obj.get("intent").getAsString();

                    System.out.println("[TEXT]     " + text);
                    System.out.println("[INTENT]   " + intent);

                    // 3. Route to correct module
                    String result = router.route(text, intent);

                    System.out.println("[RESULT]   " + result);

                    // 4. Send result back to Python for TTS
                    bridge.sendResponse(result);

                    System.out.println("[SENT]     ✅ Response sent to Python");
                    System.out.println("--------------------------------------------------");

                } catch (Exception e) {
                    System.out.println("❌ Loop error: " + e.getMessage());
                    System.out.println("   Retrying in 1 second...");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });

        // daemon = false keeps app alive even after main() finishes
        listenThread.setDaemon(false);
        listenThread.start();

        // ── Shutdown hook — runs when user closes the app ──
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n==================================================");
            System.out.println("              RAHBAR — Shutting Down              ");
            System.out.println("==================================================");
            try {
                if (bridge != null) bridge.shutdown();
                if (db != null) db.logCommand("SYSTEM_STOP", "SYSTEM", "SUCCESS");
            } catch (Exception e) {
                // ignore errors on shutdown
            }
            System.out.println("Goodbye!");
        }));
    }
}