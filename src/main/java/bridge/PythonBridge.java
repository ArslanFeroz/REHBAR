package bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * PythonBridge — HTTP REST client for the Python bridge (Flask :5000).
 *
 * Why HTTP instead of raw TCP sockets:
 *   - No newline-framing bugs, no makefile() deadlocks on Windows.
 *   - waitForHealth() polls /health: returns 503 while Python loads,
 *     200 only when every component is ready. No Thread.sleep() guessing.
 *   - A transient error on one request never corrupts the session.
 *
 * /health  -> 503 (loading) or 200 (ready)
 * /command -> 200 + JSON {text, intent} or 204 (nothing heard yet)
 * /speak   -> POST {"text": "..."}
 */
public class PythonBridge {

    private static final String BASE_URL         = "http://127.0.0.1:5000";
    private static final int    CONNECT_TIMEOUT  = 3_000;
    private static final int    READ_TIMEOUT     = 12_000;
    private static final int    MAX_HEALTH_TRIES = 90;    // 90 * 2s = 3 min max
    private static final int    HEALTH_INTERVAL  = 2_000;

    private Process pythonProcess;

    // ── Startup ───────────────────────────────────────────────────────────────

    public void start() throws Exception {
        killPortProcess(5000);   // clear any stale process from a previous run
        launchPythonProcess();
        System.out.println("[Bridge] Waiting for Python bridge to be ready...");
        waitForHealth();
        System.out.println("[Bridge] \u2705 Python bridge is ready!");
    }

    /**
     * On Windows: kill whatever process is currently bound to the port.
     * Prevents the "stale Flask process" bug where Java connects to a zombie
     * from the previous run and immediately gets 200 before Python loads.
     */
    private void killPortProcess(int port) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return;
        try {
            Process netstat = new ProcessBuilder(
                    "cmd", "/c",
                    "netstat -ano | findstr :" + port + " | findstr LISTENING")
                    .redirectErrorStream(true).start();
            String pid = null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(netstat.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        String[] parts = line.split("\\s+");
                        if (parts.length > 0) { pid = parts[parts.length - 1]; break; }
                    }
                }
            }
            netstat.waitFor();
            if (pid != null && !pid.isEmpty() && !pid.equals("0")) {
                System.out.println("[Bridge] Killing stale process on port " + port +
                        " (PID " + pid + ")...");
                new ProcessBuilder("taskkill", "/F", "/PID", pid)
                        .redirectErrorStream(true).start().waitFor();
                Thread.sleep(500);
            }
        } catch (Exception e) {
            System.out.println("[Bridge] Note: could not check port " + port + ": " + e.getMessage());
        }
    }

    private void launchPythonProcess() throws Exception {
        String pythonExecutable, workingDir;
        boolean isPackaged = isRunningAsPackaged();

        if (isPackaged) {
            String appDir    = getAppDirectory();
            pythonExecutable = appDir + "/bridge.exe";
            workingDir       = appDir;
            System.out.println("[Bridge] PRODUCTION MODE -- app dir: " + appDir);
        } else {
            String projectRoot = System.getProperty("user.dir");
            String os          = System.getProperty("os.name").toLowerCase();
            pythonExecutable   = os.contains("win")
                    ? projectRoot + "/Rehbar_python/python/venv/Scripts/python.exe"
                    : projectRoot + "/Rehbar_python/python/venv/bin/python";
            workingDir = projectRoot + "/Rehbar_python/python";
            System.out.println("[Bridge] DEVELOPMENT MODE -- project root: " + projectRoot);
        }
        System.out.println("[Bridge] Executable : " + pythonExecutable);

        if (!new File(pythonExecutable).exists()) {
            throw new RuntimeException(
                    "\n\u274C Python executable not found: " + pythonExecutable +
                            (isPackaged
                                    ? "\n   Make sure bridge.exe is beside RAHBAR.exe"
                                    : "\n   Run setup.bat to create the venv first."));
        }

        ProcessBuilder pb;
        if (isPackaged) {
            pb = new ProcessBuilder(pythonExecutable);
        } else {
            String bridgePath = workingDir + "/bridge.py";
            if (!new File(bridgePath).exists())
                throw new RuntimeException("\n\u274C bridge.py not found at: " + bridgePath);
            pb = new ProcessBuilder(pythonExecutable, bridgePath);
        }
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);
        pb.inheritIO();

        pythonProcess = pb.start();
        System.out.println("[Bridge] Python process started (PID: " +
                pythonProcess.pid() + ")");
    }

    /**
     * Polls /health until 200.
     *   ConnectException -> process not up yet, silent wait
     *   503              -> Flask up, components still loading
     *   200              -> everything ready, return
     */
    private void waitForHealth() throws Exception {
        boolean flaskSeen = false;
        for (int i = 1; i <= MAX_HEALTH_TRIES; i++) {
            try {
                int code = getStatusCode(BASE_URL + "/health");
                if (code == 200) return;
                if (code == 503) {
                    if (!flaskSeen) {
                        System.out.println("[Bridge] Flask up -- waiting for components...");
                        flaskSeen = true;
                    } else {
                        System.out.printf("[Bridge] Still loading... (%d/%d)%n", i, MAX_HEALTH_TRIES);
                    }
                }
            } catch (Exception e) {
                if (i == 1 || i % 5 == 0)
                    System.out.printf("[Bridge] Waiting for Python process... (%d/%d)%n",
                            i, MAX_HEALTH_TRIES);
            }
            Thread.sleep(HEALTH_INTERVAL);
        }
        throw new RuntimeException(
                "\u274C Python bridge not ready after " +
                        (MAX_HEALTH_TRIES * HEALTH_INTERVAL / 1000) + "s.\n" +
                        "   1. venv not found -- run setup.bat\n" +
                        "   2. bridge.py crashed -- check Python console\n" +
                        "   3. Port 5000 in use by another app");
    }

    // ── Main loop helpers ──────────────────────────────────────────────────────

    /**
     * Long-polls /command.
     * Returns JsonObject {text, intent} on 200.
     * Returns null on 204 (no speech yet) -- caller retries immediately.
     */
    public JsonObject readCommand() throws Exception {
        HttpURLConnection conn = openConn(BASE_URL + "/command", "GET");
        conn.setReadTimeout(READ_TIMEOUT);
        int code = conn.getResponseCode();
        if (code == 204) { conn.disconnect(); return null; }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("Unexpected /command HTTP " + code);
        }
        String body = readBody(conn);
        conn.disconnect();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /** POST /speak -- sends TTS text to Python. */
    public void sendResponse(String text) throws Exception {
        HttpURLConnection conn = openConn(BASE_URL + "/speak", "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = ("{\"text\":\"" + escapeJson(text) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200) throw new IOException("Unexpected /speak HTTP " + code);
    }

    // ── Listening control ─────────────────────────────────────────────────────

    /** Tell Python to start capturing voice (widget entered LISTENING). */
    public void startListening() throws Exception {
        postNoBody(BASE_URL + "/listen/start");
    }

    /** Tell Python to stop capturing voice (widget left LISTENING). */
    public void stopListening() throws Exception {
        postNoBody(BASE_URL + "/listen/stop");
    }

    /** Tell Python to reload voice/TTS settings from the DB. */
    public void reloadSettings() throws Exception {
        postNoBody(BASE_URL + "/settings/reload");
    }

    // ── Shutdown ───────────────────────────────────────────────────────────────

    public void shutdown() {
        forceShutdown();
    }

    /** Kills the Python process and all its children (used by shutdown hook too). */
    public void forceShutdown() {
        if (pythonProcess != null && pythonProcess.isAlive()) {
            try {
                // Kill child processes first (e.g. sub-spawned processes)
                pythonProcess.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception ignored) {}
            pythonProcess.destroyForcibly();
            System.out.println("[Bridge] Python process forcibly terminated.");
        }
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private HttpURLConnection openConn(String urlStr, String method) throws Exception {
        @SuppressWarnings("deprecation")
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private int getStatusCode(String urlStr) throws Exception {
        HttpURLConnection conn = openConn(urlStr, "GET");
        conn.setConnectTimeout(2_000);
        conn.setReadTimeout(2_000);
        try { return conn.getResponseCode(); }
        finally { conn.disconnect(); }
    }

    private void postNoBody(String urlStr) throws Exception {
        HttpURLConnection conn = openConn(urlStr, "POST");
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(3_000);
        conn.setDoOutput(false);
        conn.setFixedLengthStreamingMode(0);
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200) throw new IOException("Unexpected HTTP " + code + " from " + urlStr);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private boolean isRunningAsPackaged() {
        return System.getProperty("jpackage.app-path") != null;
    }

    private String getAppDirectory() {
        return new File(System.getProperty("jpackage.app-path")).getParent();
    }
}