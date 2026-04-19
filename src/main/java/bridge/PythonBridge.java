package bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;

public class PythonBridge {
    private Process pythonProcess;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public void start() throws Exception {

        String pythonExecutable;
        String workingDir;

        boolean isPackaged = isRunningAsPackaged();

        if (isPackaged) {
            String appDir = getAppDirectory();
            pythonExecutable = appDir + "/bridge.exe";
            workingDir       = appDir;

            System.out.println("[PRODUCTION MODE]");
            System.out.println("App directory : " + appDir);

        } else {
            String projectRoot = System.getProperty("user.dir");
            String os          = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                pythonExecutable = projectRoot +
                        "/Rehbar_python/python/venv/Scripts/python.exe";
            } else {
                pythonExecutable = projectRoot +
                        "/Rehbar_python/python/venv/bin/python";
            }

            workingDir = projectRoot + "/Rehbar_python/python";

            System.out.println("[DEVELOPMENT MODE]");
            System.out.println("Project root  : " + projectRoot);
        }

        System.out.println("Executable    : " + pythonExecutable);

        File exeFile = new File(pythonExecutable);
        if (!exeFile.exists()) {
            throw new RuntimeException(
                    "\n❌ Python executable not found: " + pythonExecutable +
                            (isPackaged
                                    ? "\n   Make sure bridge.exe is in the same folder as RAHBAR.exe"
                                    : "\n   Make sure venv is created. Run setup.bat first.")
            );
        }

        ProcessBuilder pb;

        if (isPackaged) {
            pb = new ProcessBuilder(pythonExecutable);
        } else {
            String bridgePath = workingDir + "/bridge.py";

            File bridgeFile = new File(bridgePath);
            if (!bridgeFile.exists()) {
                throw new RuntimeException(
                        "\n❌ bridge.py not found at: " + bridgePath
                );
            }
            pb = new ProcessBuilder(pythonExecutable, bridgePath);
        }

        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);
        pb.inheritIO();

        pythonProcess = pb.start();
        System.out.println("Python bridge process started...");

        // ─────────────────────────────────────────────────────────────
        // FIX: Increased initial wait to 5 s and retries to 10 so slow
        //      machines (or first-time classifier training) have time to
        //      start before we declare a connection failure.
        // ─────────────────────────────────────────────────────────────
        Thread.sleep(5000);

        for (int i = 0; i < 10; i++) {
            try {
                socket = new Socket("localhost", 9999);

                // ─────────────────────────────────────────────────────
                // FIX: Set a 20-second read timeout on the Java side so
                //      readCommand() never blocks forever if Python hangs.
                // ─────────────────────────────────────────────────────
                socket.setSoTimeout(20_000);

                reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "UTF-8"));
                writer = new PrintWriter(socket.getOutputStream(), true); // autoFlush=true

                System.out.println("✅ Connected to Python bridge!");
                return;
            } catch (ConnectException e) {
                System.out.println("Retrying connection " + (i + 1) + "/10...");
                Thread.sleep(1000);
            }
        }

        throw new RuntimeException(
                "❌ Could not connect to Python bridge after 10 attempts."
        );
    }

    private boolean isRunningAsPackaged() {
        String jpackageAppPath = System.getProperty("jpackage.app-path");
        return jpackageAppPath != null;
    }

    private String getAppDirectory() {
        String appPath = System.getProperty("jpackage.app-path");
        return new File(appPath).getParent();
    }

    /**
     * Reads one JSON line sent by Python.
     * Returns null if the connection was closed cleanly.
     * Throws SocketTimeoutException if Python doesn't send anything within
     * the socket's SO_TIMEOUT period — caller can decide to retry or abort.
     */
    public String readCommand() throws Exception {
        return reader.readLine();
    }

    /**
     * Sends a plain-text response line back to Python.
     * PrintWriter with autoFlush=true guarantees the data is pushed
     * immediately — no manual flush needed.
     */
    public void sendResponse(String text) {
        writer.println(text);
        // autoFlush is true, but call flush() explicitly as a safety net
        writer.flush();
    }

    public void shutdown() {
        try {
            // ─────────────────────────────────────────────────────────
            // FIX: Flush + close the writer BEFORE closing the socket
            //      so Python receives the SHUTDOWN line intact.
            // ─────────────────────────────────────────────────────────
            if (writer != null) {
                writer.println("SHUTDOWN");
                writer.flush();
                writer.close();
            }
            if (reader != null) reader.close();
            if (socket != null && !socket.isClosed()) socket.close();
            if (pythonProcess != null) {
                pythonProcess.destroy();
                System.out.println("Python bridge shut down.");
            }
        } catch (Exception e) {
            System.out.println("Shutdown error: " + e.getMessage());
        }
    }
}
