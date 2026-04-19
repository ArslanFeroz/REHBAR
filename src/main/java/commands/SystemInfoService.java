package commands;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class SystemInfoService {

    public String query(String command) {
        String cmd = command.toLowerCase();

        if (cmd.contains("time"))
            return "It is " + LocalTime.now().format(
                    DateTimeFormatter.ofPattern("h:mm a"));

        if (cmd.contains("date") || cmd.contains("today"))
            return "Today is " + LocalDate.now().format(
                    DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"));

        if (cmd.contains("battery"))
            return getBatteryInfo();

        if (cmd.contains("cpu") || cmd.contains("processor"))
            return getCpuUsage();

        if (cmd.contains("memory") || cmd.contains("ram"))
            return getMemoryUsage();

        return "I cannot retrieve that information.";
    }

    // ── CPU Usage ──
    private String getCpuUsage() {
        OperatingSystemMXBean osBean =
                ManagementFactory.getOperatingSystemMXBean();

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;

            // First call returns 0.0 — JVM needs time to measure
            // So we discard it and wait before calling again
            sunBean.getCpuLoad();

            try {
                Thread.sleep(500); // wait 500ms for accurate measurement
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            double cpuLoad = sunBean.getCpuLoad() * 100;

            if (cpuLoad < 0) {
                return "CPU usage is currently unavailable.";
            }

            return String.format("CPU usage is %.1f percent.", cpuLoad);
        }

        // Fallback for non-Windows
        double load = osBean.getSystemLoadAverage();
        if (load < 0) return "CPU usage is not available on this system.";
        return String.format("CPU load is %.1f percent.", load);
    }

    // ── Memory / RAM Usage ──
    private String getMemoryUsage() {
        OperatingSystemMXBean osBean =
                ManagementFactory.getOperatingSystemMXBean();

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;

            long totalRam = sunBean.getTotalMemorySize();
            long freeRam  = sunBean.getFreeMemorySize();
            long usedRam  = totalRam - freeRam;

            double totalGB = totalRam / (1024.0 * 1024.0 * 1024.0);
            double usedGB  = usedRam  / (1024.0 * 1024.0 * 1024.0);
            double freeGB  = freeRam  / (1024.0 * 1024.0 * 1024.0);

            int usedPercent = (int) ((usedRam * 100.0) / totalRam);

            return String.format(
                    "RAM usage is %d percent. " +
                            "Using %.1f GB out of %.1f GB. " +
                            "%.1f GB is free.",
                    usedPercent, usedGB, totalGB, freeGB
            );
        }

        // Fallback — JVM heap memory only
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long usedMB  = heap.getUsed() / (1024 * 1024);
        long totalMB = heap.getMax()  / (1024 * 1024);
        return String.format("JVM memory usage is %d MB out of %d MB.", usedMB, totalMB);
    }

    // ── Battery Info ──
    private String getBatteryInfo() {
        try {
            String[] command = {
                    "powershell",
                    "-Command",
                    "(Get-WmiObject -Class Win32_Battery).EstimatedChargeRemaining"
            };

            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line = reader.readLine();
            reader.close();
            process.waitFor();

            if (line == null || line.trim().isEmpty()) {
                return "No battery detected. This appears to be a desktop computer.";
            }

            int percent = Integer.parseInt(line.trim());

            String[] chargingCommand = {
                    "powershell",
                    "-Command",
                    "(Get-WmiObject -Class Win32_Battery).BatteryStatus"
            };

            Process chargingProcess = Runtime.getRuntime().exec(chargingCommand);
            BufferedReader chargingReader = new BufferedReader(
                    new InputStreamReader(chargingProcess.getInputStream()));

            String statusLine = chargingReader.readLine();
            chargingReader.close();
            chargingProcess.waitFor();

            boolean isCharging = statusLine != null &&
                    statusLine.trim().equals("2");

            String status  = isCharging ? "charging" : "on battery";
            String warning = "";

            if (percent <= 20 && !isCharging) {
                warning = " Warning: battery is low. Please plug in your charger.";
            }

            return String.format(
                    "Battery is at %d percent and is currently %s.%s",
                    percent, status, warning
            );

        } catch (NumberFormatException e) {
            return "Could not read battery level.";
        } catch (Exception e) {
            return "Could not retrieve battery information. " + e.getMessage();
        }
    }
}