package com.rahbar.ui.panels;

import com.rahbar.db.DatabaseManager;
import com.rahbar.widget.ArcReactorWidget;
import com.rahbar.widget.WidgetState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.collections.FXCollections;
import com.rahbar.db.DatabaseManager.CommandLog;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.ResourceBundle;

/** Dashboard controller — greeting, stat cards, hourly activity chart, recent commands table, status polling. */
public class DashboardController implements Initializable {

    // ── Greeting ──────────────────────────────────────────────────────────────
    @FXML private Label greetingLabel;
    @FXML private Label greetingSubLabel;

    // ── Status banner ─────────────────────────────────────────────────────────
    @FXML private Label statusIcon;
    @FXML private Label statusTitle;
    @FXML private Label statusDesc;
    @FXML private Label statusBadge;

    // ── Stat cards ────────────────────────────────────────────────────────────
    @FXML private Label statCommandsToday;
    @FXML private Label statCommandsChange;
    @FXML private Label statAlarms;
    @FXML private Label statAlarmsNext;
    @FXML private Label statApps;
    @FXML private Label statSuccessRate;

    // ── Activity chart ────────────────────────────────────────────────────────
    @FXML private BarChart<String, Number>  activityChart;
    @FXML private CategoryAxis              chartXAxis;
    @FXML private NumberAxis                chartYAxis;
    @FXML private Label                     chartPeriodLabel;

    // ── Recent commands table ─────────────────────────────────────────────────
    @FXML private TableView<CommandRow>            recentCommandsTable;
    @FXML private TableColumn<CommandRow, String>  colCmd;
    @FXML private TableColumn<CommandRow, String>  colType;
    @FXML private TableColumn<CommandRow, String>  colStatus;
    @FXML private TableColumn<CommandRow, String>  colTime;

    private volatile boolean polling = true;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setupTable();
        setupChart();
        loadData();
        startStatusPolling();
    }

    // ── Greeting — time-of-day + nickname from profile ────────────────────────

    private void buildGreeting() {
        int hour = LocalTime.now().getHour();
        String timeGreeting;
        if      (hour < 12) timeGreeting = "Good morning";
        else if (hour < 17) timeGreeting = "Good afternoon";
        else if (hour < 21) timeGreeting = "Good evening";
        else                timeGreeting = "Good night";

        String name = null;
        try {
            DatabaseManager db = DatabaseManager.getInstance();
            name = db.getSetting("profile_nickname");
            if (name == null || name.isBlank())
                name = db.getSetting("profile_first_name"); // fall back to first name
        } catch (Exception ignored) {}

        if (name != null && !name.isBlank()) {
            greetingLabel.setText(timeGreeting + ", " + name + ".");
        } else {
            greetingLabel.setText(timeGreeting + ".");
        }

        // Subtitle: today's date
        greetingSubLabel.setText(
                LocalDate.now().getDayOfWeek().toString().charAt(0)
                        + LocalDate.now().getDayOfWeek().toString().substring(1).toLowerCase()
                        + ", " + LocalDate.now().getMonth().toString().charAt(0)
                        + LocalDate.now().getMonth().toString().substring(1).toLowerCase()
                        + " " + LocalDate.now().getDayOfMonth()
        );
    }

    // ── Activity chart ────────────────────────────────────────────────────────

    private void setupChart() {
        activityChart.setStyle("-fx-background-color: transparent;");
        activityChart.setHorizontalGridLinesVisible(false);
        activityChart.setVerticalGridLinesVisible(false);
        activityChart.getStylesheets().add(
                getClass().getResource("/css/components.css").toExternalForm()
        );
        chartYAxis.setTickUnit(1);
        chartYAxis.setMinorTickCount(0);
    }

    private void populateChart() { // last 8 hours of command counts; zeros if DB not ready
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Commands");

        try {
            int[] hourlyCounts = DatabaseManager.getInstance().getCommandCountByHour(); // int[24]
            int currentHour = LocalTime.now().getHour();
            for (int i = 7; i >= 0; i--) {
                int h = (currentHour - i + 24) % 24;
                String label = String.format("%02d:00", h);
                int count = (hourlyCounts != null && h < hourlyCounts.length)
                        ? hourlyCounts[h] : 0;
                series.getData().add(new XYChart.Data<>(label, count));
            }
        } catch (Exception e) {
            int currentHour = LocalTime.now().getHour(); // show zeros if DB not ready
            for (int i = 7; i >= 0; i--) {
                int h = (currentHour - i + 24) % 24;
                series.getData().add(
                        new XYChart.Data<>(String.format("%02d:00", h), 0)
                );
            }
        }

        activityChart.getData().clear();
        activityChart.getData().add(series);

        series.getData().forEach(d -> {
            if (d.getNode() != null) {
                d.getNode().setStyle(
                        "-fx-bar-fill: -color-accent; -fx-background-radius: 0;"
                );
            }
        });
    }

    // ── Table setup ───────────────────────────────────────────────────────────

    private void setupTable() {
        colCmd.setCellValueFactory(new PropertyValueFactory<>("text"));
        colType.setCellValueFactory(new PropertyValueFactory<>("type"));
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        colTime.setCellValueFactory(new PropertyValueFactory<>("timestamp"));

        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                String color = switch (item.toUpperCase()) {
                    case "SUCCESS" -> "-color-success";
                    case "FAILED"  -> "-color-error";
                    default        -> "-color-text-muted";
                };
                setStyle("-fx-text-fill: " + color + ";");
            }
        });

        colType.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item);
                setStyle("-fx-text-fill: -color-text-secondary;");
            }
        });

        recentCommandsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadData() {
        Thread loader = new Thread(() -> {
            try {
                DatabaseManager db = DatabaseManager.getInstance();
                List<CommandLog> logs = db.getRecentCommands(5);
                int todayCount  = db.getCommandCountToday();
                int alarmCount  = db.getAllAlarms().size();
                int appCount    = db.getAppRegistryCount();
                int successRate = db.getSuccessRatePercent(50);
                String nextAlarm = db.getNextAlarmLabel();

                Platform.runLater(() -> {
                    buildGreeting();

                    statCommandsToday.setText(String.valueOf(todayCount));
                    statCommandsChange.setText(todayCount + " commands logged today");
                    statAlarms.setText(String.valueOf(alarmCount));
                    statAlarmsNext.setText(nextAlarm != null ? "Next: " + nextAlarm : "No alarms set");
                    statApps.setText(String.valueOf(appCount));
                    statSuccessRate.setText(successRate + "%");

                    var rows = FXCollections.<CommandRow>observableArrayList();
                    for (var log : logs)
                        rows.add(new CommandRow(log.getText(), log.getType(),
                                log.getStatus(), log.getTimestamp()));
                    recentCommandsTable.setItems(rows);

                    populateChart();
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    buildGreeting();
                    statCommandsToday.setText("0");
                    statAlarms.setText("0");
                    statApps.setText("0");
                    statSuccessRate.setText("—");
                    populateChart();
                });
            }
        });
        loader.setDaemon(true);
        loader.setName("rahbar-dashboard-loader");
        loader.start();
    }

    // ── Status banner polling ─────────────────────────────────────────────────

    private void startStatusPolling() {
        Thread poll = new Thread(() -> {
            WidgetState last = null;
            while (polling) {
                try { Thread.sleep(800); } catch (InterruptedException ignored) { break; }
                WidgetState current = ArcReactorWidget.getInstance().getState();
                if (current != last) {
                    last = current;
                    final WidgetState s = current;
                    Platform.runLater(() -> updateStatusBanner(s));
                }
            }
        });
        poll.setDaemon(true);
        poll.setName("rahbar-dashboard-poll");
        poll.start();
    }

    private void updateStatusBanner(WidgetState state) {
        switch (state) {
            case IDLE -> {
                statusIcon.setStyle("-fx-font-size: 20px; -fx-text-fill: #2ECC71;");
                statusTitle.setText("R.A.H.B.A.R is Active");
                statusDesc.setText("Listening for wake word: 'RAHBAR guide me'");
                statusBadge.getStyleClass().setAll("badge", "badge-success");
                statusBadge.setText("IDLE");
            }
            case LISTENING -> {
                statusIcon.setStyle("-fx-font-size: 20px; -fx-text-fill: #00B4D8;");
                statusTitle.setText("Listening...");
                statusDesc.setText("Speak your command now");
                statusBadge.getStyleClass().setAll("badge", "badge-info");
                statusBadge.setText("LISTENING");
            }
            case PROCESSING -> {
                statusIcon.setStyle("-fx-font-size: 20px; -fx-text-fill: #F39C12;");
                statusTitle.setText("Processing command...");
                statusDesc.setText("Working on it");
                statusBadge.getStyleClass().setAll("badge", "badge-warning");
                statusBadge.setText("PROCESSING");
            }
            case SPEAKING -> {
                statusIcon.setStyle("-fx-font-size: 20px; -fx-text-fill: #00B4D8;");
                statusTitle.setText("Speaking response...");
                statusDesc.setText("R.A.H.B.A.R is talking");
                statusBadge.getStyleClass().setAll("badge", "badge-info");
                statusBadge.setText("SPEAKING");
            }
            case ERROR -> {
                statusIcon.setStyle("-fx-font-size: 20px; -fx-text-fill: #E74C3C;");
                statusTitle.setText("Error occurred");
                statusDesc.setText("Something went wrong — check mic or internet connection");
                statusBadge.getStyleClass().setAll("badge", "badge-error");
                statusBadge.setText("ERROR");
            }
        }
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    @FXML
    private void onViewAllHistory() {
        try {
            var scene = recentCommandsTable.getScene();
            if (scene == null) return;
            Object ud = scene.getRoot().getUserData();
            if (ud instanceof com.rahbar.ui.MainWindowController mwc)
                mwc.openPanel("history");
        } catch (Exception e) {
            System.err.println("[DashboardController] Nav to history failed: " + e.getMessage());
        }
    }

    public void onHide() {
        polling = false;
    }

    // ── CommandRow model ──────────────────────────────────────────────────────

    public static class CommandRow {
        private final String text, type, status, timestamp;

        public CommandRow(String text, String type, String status, String timestamp) {
            this.text      = text      != null ? text      : "";
            this.type      = type      != null ? type      : "";
            this.status    = status    != null ? status    : "";
            this.timestamp = timestamp != null ? timestamp : "";
        }

        public String getText()      { return text; }
        public String getType()      { return type; }
        public String getStatus()    { return status; }
        public String getTimestamp() { return timestamp; }
    }
}