package commands;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/** Answers voice queries about time, date, battery (via PowerShell), CPU, RAM, and disk usage. */
public class SystemInfoService implements CommandHandler {

    @Override
    public String execute(String input) { return query(input); }

    public String query(String command) {
        String cmd = command.toLowerCase();

        // battery MUST come before time to avoid "battery time" hitting time branch
        if (cmd.contains("battery"))
            return getBatteryInfo();

        if (cmd.contains("time"))
            return "It is " + LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"));

        if (cmd.contains("date") || cmd.contains("today") || cmd.contains("day"))
            return "Today is " + LocalDate.now().format(
                    DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"));

        if (cmd.contains("cpu") || cmd.contains("processor"))
            return getCpuUsage();

        if (cmd.contains("memory") || cmd.contains("ram"))
            return getMemoryUsage();

        if (cmd.contains("disk") || cmd.contains("storage") ||
                cmd.contains("space") || cmd.contains("drive"))
            return getDiskUsage();

        return "I can tell you the time, date, battery level, " +
                "CPU usage, RAM usage, or disk space. What would you like to know?";
    }

    // ── CPU ───────────────────────────────────────────────────────────────────

    private String getCpuUsage() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            sunBean.getCpuLoad(); // first call always 0.0 -- discard
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "CPU reading was interrupted. Please try again.";
            }
            double load = sunBean.getCpuLoad() * 100;
            if (load < 0) return "CPU usage is currently unavailable.";
            return String.format("CPU usage is %.1f percent.", load);
        }

        double load = osBean.getSystemLoadAverage();
        if (load < 0) return "CPU usage is not available on this system.";
        return String.format("CPU load average is %.1f.", load);
    }

    // ── RAM ───────────────────────────────────────────────────────────────────

    private String getMemoryUsage() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            long total   = sunBean.getTotalMemorySize();
            long free    = sunBean.getFreeMemorySize();
            long used    = total - free;
            double totalGB = total / (1024.0 * 1024.0 * 1024.0);
            double usedGB  = used  / (1024.0 * 1024.0 * 1024.0);
            double freeGB  = free  / (1024.0 * 1024.0 * 1024.0);
            int pct = (int) ((used * 100.0) / total);
            return String.format(
                    "RAM usage is %d percent. " +
                            "You are using %.1f GB out of %.1f GB total. " +
                            "%.1f GB is free.", pct, usedGB, totalGB, freeGB);
        }

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long usedMB  = heap.getUsed() / (1024 * 1024);
        long totalMB = heap.getMax()  / (1024 * 1024);
        return String.format("JVM heap memory: %d MB used out of %d MB.", usedMB, totalMB);
    }

    // ── Disk ──────────────────────────────────────────────────────────────────

    private String getDiskUsage() {
        java.io.File root = new java.io.File(
                System.getProperty("user.home").substring(0, 3)); // e.g. "C:\"
        long total = root.getTotalSpace();
        long free  = root.getUsableSpace();
        if (total == 0) return "Could not read disk information.";
        long used  = total - free;
        double totalGB = total / (1024.0 * 1024.0 * 1024.0);
        double usedGB  = used  / (1024.0 * 1024.0 * 1024.0);
        double freeGB  = free  / (1024.0 * 1024.0 * 1024.0);
        int pct = (int) ((used * 100.0) / total);
        return String.format(
                "Your main drive is %d percent full. " +
                        "%.1f GB used out of %.1f GB total. " +
                        "%.1f GB is free.", pct, usedGB, totalGB, freeGB);
    }

    // ── Battery ───────────────────────────────────────────────────────────────

    /**
     * Runs ONE PowerShell command that returns both charge level and charging
     * status as two lines.  Pays the process-startup cost once instead of twice.
     *
     * Safety:
     *   - Output drained BEFORE waitFor() to prevent OS pipe deadlock.
     *   - waitFor(5, SECONDS) so a hung PowerShell never blocks the thread.
     */
    private String getBatteryInfo() {
        String psScript =
                "$b = Get-WmiObject Win32_Battery; " +
                        "if ($b) { $b.EstimatedChargeRemaining; $b.BatteryStatus } " +
                        "else { 'NONE' }";

        try {
            Process process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command", psScript)
                    .redirectErrorStream(true).start();

            String line1 = null, line2 = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                line1 = reader.readLine();
                line2 = reader.readLine();
            }

            boolean done = process.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return "Battery query timed out. Please try again.";
            }

            if (line1 == null || line1.trim().isEmpty() || "NONE".equals(line1.trim())) {
                return "No battery detected. This appears to be a desktop computer.";
            }

            int     percent    = Integer.parseInt(line1.trim());
            boolean isCharging = "2".equals(line2 == null ? "" : line2.trim());
            String  status     = isCharging ? "charging" : "on battery";
            String  warning    = (!isCharging && percent <= 20)
                    ? " Warning: battery is low. Please plug in your charger." : "";

            return String.format(
                    "Battery is at %d percent and is currently %s.%s",
                    percent, status, warning);

        } catch (NumberFormatException e) {
            return "Could not read the battery level.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Battery query was interrupted. Please try again.";
        } catch (IOException e) {
            return "Could not run the battery query. " + e.getMessage();
        }
    }
}