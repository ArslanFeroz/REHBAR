package com.rahbar.ui.panels;

import com.rahbar.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import com.rahbar.db.DatabaseManager.CommandLog;

import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * HistoryController
 *
 * Loads the full command_log table from SQLite, displays it in a filterable
 * TableView. Search and type/status dropdowns filter using JavaFX's
 * FilteredList — no additional DB queries on filter change.
 *
 * Load limit: 500 most recent commands (configurable via LOAD_LIMIT).
 * For projects with very large history, increase this or add pagination.
 */
public class HistoryController implements Initializable {

    private static final int LOAD_LIMIT = 500;

    // ── FXML nodes ────────────────────────────────────────────────────────────
    @FXML private TextField  searchField;
    @FXML private ComboBox<String> filterType;
    @FXML private ComboBox<String> filterStatus;

    @FXML private TableView<CommandRow>              historyTable;
    @FXML private TableColumn<CommandRow, String>    colText;
    @FXML private TableColumn<CommandRow, String>    colType;
    @FXML private TableColumn<CommandRow, String>    colStatus;
    @FXML private TableColumn<CommandRow, String>    colTimestamp;

    @FXML private Label rowCountLabel;

    // ── Data ──────────────────────────────────────────────────────────────────
    private ObservableList<CommandRow> allRows;
    private FilteredList<CommandRow>   filteredRows;

    // ─────────────────────────────────────────────────────────────────────────
    // Initialize
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        setupTable();
        setupFilters();
        loadHistory();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Table setup
    // ─────────────────────────────────────────────────────────────────────────

    private void setupTable() {
        colText.setCellValueFactory(new PropertyValueFactory<>("text"));
        colType.setCellValueFactory(new PropertyValueFactory<>("type"));
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        colTimestamp.setCellValueFactory(new PropertyValueFactory<>("timestamp"));

        // Status column coloring
        colStatus.setCellFactory(col -> new TableCell<CommandRow, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                String color;
                if ("SUCCESS".equalsIgnoreCase(item)) {
                    color = "-color-success";
                } else if ("FAILED".equalsIgnoreCase(item)) {
                    color = "-color-error";
                } else {
                    color = "-color-text-muted";
                }
                setStyle("-fx-text-fill: " + color + ";");
            }
        });

        // Type column coloring
        colType.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item);
                setStyle("-fx-text-fill: -color-text-secondary;");
            }
        });

        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter setup
    // ─────────────────────────────────────────────────────────────────────────

    private void setupFilters() {
        // Type dropdown options (match the intent strings from CommandRouter)
        filterType.setItems(FXCollections.observableArrayList(
            "All Types", "OPEN_APP", "CLOSE_APP", "CREATE_FILE",
            "DELETE_FILE", "RENAME_FILE", "WEB_SEARCH", "OPEN_SITE",
            "SET_ALARM", "SYSTEM_INFO", "WAKE_WORD"
        ));
        filterType.setValue("All Types");

        filterStatus.setItems(FXCollections.observableArrayList(
            "All Statuses", "SUCCESS", "FAILED", "CANCELLED"
        ));
        filterStatus.setValue("All Statuses");

        // Wire listeners — any change re-applies the combined predicate
        searchField.textProperty().addListener((obs, old, val) -> applyFilter());
        filterType.valueProperty().addListener((obs, old, val) -> applyFilter());
        filterStatus.valueProperty().addListener((obs, old, val) -> applyFilter());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data loading
    // ─────────────────────────────────────────────────────────────────────────

    private void loadHistory() {
        try {
            List<CommandLog> logs =
                DatabaseManager.getInstance().getRecentCommands(LOAD_LIMIT);

            allRows = FXCollections.observableArrayList();
            for (var log : logs) {
                allRows.add(new CommandRow(
                    log.getText(), log.getType(), log.getStatus(), log.getTimestamp()
                ));
            }

            filteredRows = new FilteredList<>(allRows, p -> true);
            historyTable.setItems(filteredRows);
            updateRowCount();

        } catch (Exception e) {
            System.err.println("[HistoryController] Failed to load history: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filtering
    // ─────────────────────────────────────────────────────────────────────────

    private void applyFilter() {
        if (filteredRows == null) return;

        String search = searchField.getText().toLowerCase().trim();
        String type   = filterType.getValue();
        String status = filterStatus.getValue();

        filteredRows.setPredicate(row -> {
            // Search filter
            boolean matchesSearch = search.isEmpty()
                || row.getText().toLowerCase().contains(search)
                || row.getType().toLowerCase().contains(search);

            // Type filter
            boolean matchesType = type == null || type.equals("All Types")
                || row.getType().equalsIgnoreCase(type);

            // Status filter
            boolean matchesStatus = status == null || status.equals("All Statuses")
                || row.getStatus().equalsIgnoreCase(status);

            return matchesSearch && matchesType && matchesStatus;
        });

        updateRowCount();
    }

    private void updateRowCount() {
        int count = filteredRows != null ? filteredRows.size() : 0;
        rowCountLabel.setText(count + (count == 1 ? " command" : " commands"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button handlers
    // ─────────────────────────────────────────────────────────────────────────

    @FXML
    private void onClearFilters() {
        searchField.clear();
        filterType.setValue("All Types");
        filterStatus.setValue("All Statuses");
    }

    @FXML
    private void onRefresh() {
        loadHistory();
        applyFilter();
    }

    @FXML
    private void onClearAllHistory() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear History");
        confirm.setHeaderText("Clear all command history?");
        confirm.setContentText("This will permanently delete all " +
            (allRows != null ? allRows.size() : 0) + " logged commands. This cannot be undone.");

        // Style the dialog to match our dark theme
        confirm.getDialogPane().setStyle(
            "-fx-background-color: #03045E;" +
            "-fx-border-color: #00B4D8; -fx-border-width: 1px;"
        );
        confirm.getDialogPane().lookup(".content.label")
            .setStyle("-fx-text-fill: #90E0EF;");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                DatabaseManager.getInstance().clearCommandLog();
                loadHistory();
            } catch (Exception e) {
                System.err.println("[HistoryController] Failed to clear history: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Row model
    // ─────────────────────────────────────────────────────────────────────────

    public static class CommandRow {
        private final String text;
        private final String type;
        private final String status;
        private final String timestamp;

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
