package com.rahbar.ui.panels;

import com.rahbar.db.DatabaseManager;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/** Registry controller — app and website alias tables backed by SQLite, changes take effect immediately. */
public class RegistryController implements Initializable {

    // ── App registry tab ──────────────────────────────────────────────────────
    @FXML private TextField appAlias;
    @FXML private TextField appPath;
    @FXML private Label     appFeedback;
    @FXML private Label     appCountLabel;

    @FXML private TableView<RegistryRow>              appTable;
    @FXML private TableColumn<RegistryRow, String>    colAppAlias;
    @FXML private TableColumn<RegistryRow, String>    colAppPath;
    @FXML private TableColumn<RegistryRow, Void>      colAppAction;

    // ── Website registry tab ──────────────────────────────────────────────────
    @FXML private TextField webAlias;
    @FXML private TextField webUrl;
    @FXML private Label     webFeedback;
    @FXML private Label     webCountLabel;

    @FXML private TableView<RegistryRow>              webTable;
    @FXML private TableColumn<RegistryRow, String>    colWebAlias;
    @FXML private TableColumn<RegistryRow, String>    colWebUrl;
    @FXML private TableColumn<RegistryRow, Void>      colWebAction;

    // ── Data ──────────────────────────────────────────────────────────────────
    private ObservableList<RegistryRow> appRows = FXCollections.observableArrayList();
    private ObservableList<RegistryRow> webRows = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        setupAppTable();
        setupWebTable();
        loadApps();
        loadWebsites();
    }

    // ── App table setup ───────────────────────────────────────────────────────

    private void setupAppTable() {
        colAppAlias.setCellValueFactory(new PropertyValueFactory<>("alias"));
        colAppPath.setCellValueFactory(new PropertyValueFactory<>("value"));
        colAppAction.setCellFactory(col -> new TableCell<>() {
            private final Button deleteBtn = new Button("Remove");
            {
                deleteBtn.getStyleClass().add("btn-danger");
                deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 10 4 10;");
                deleteBtn.setOnAction(e -> {
                    RegistryRow row = getTableView().getItems().get(getIndex());
                    confirmAndDeleteApp(row);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteBtn);
            }
        });

        appTable.setItems(appRows);
        appTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    // ── Website table setup ───────────────────────────────────────────────────

    private void setupWebTable() {
        colWebAlias.setCellValueFactory(new PropertyValueFactory<>("alias"));
        colWebUrl.setCellValueFactory(new PropertyValueFactory<>("value"));

        colWebAction.setCellFactory(col -> new TableCell<>() {
            private final Button deleteBtn = new Button("Remove");
            {
                deleteBtn.getStyleClass().add("btn-danger");
                deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 10 4 10;");
                deleteBtn.setOnAction(e -> {
                    RegistryRow row = getTableView().getItems().get(getIndex());
                    confirmAndDeleteWebsite(row);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteBtn);
            }
        });

        webTable.setItems(webRows);
        webTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    // ── Load data from DB ─────────────────────────────────────────────────────

    private void loadApps() {
        try {
            appRows.clear();
            List<String[]> apps = DatabaseManager.getInstance().getAllAppAliases();
            for (String[] app : apps) {
                appRows.add(new RegistryRow(app[0], app[1]));
            }
            appCountLabel.setText(appRows.size() + " apps registered");
        } catch (Exception e) {
            System.err.println("[RegistryController] Failed to load apps: " + e.getMessage());
        }
    }

    private void loadWebsites() {
        try {
            webRows.clear();
            List<String[]> sites = DatabaseManager.getInstance().getAllWebsiteAliases();
            for (String[] site : sites) {
                webRows.add(new RegistryRow(site[0], site[1]));
            }
            webCountLabel.setText(webRows.size() + " websites registered");
        } catch (Exception e) {
            System.err.println("[RegistryController] Failed to load websites: " + e.getMessage());
        }
    }

    // ── App handlers ──────────────────────────────────────────────────────────

    @FXML
    private void onBrowseApp() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Executable");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Executable Files", "*.exe", "*.bat", "*.cmd")
        );
        chooser.setInitialDirectory(new File("C:\\Program Files"));
        File file = chooser.showOpenDialog(appPath.getScene().getWindow());
        if (file != null) {
            appPath.setText(file.getAbsolutePath());
            if (appAlias.getText().isBlank()) { // auto-fill alias from filename
                String name = file.getName().replace(".exe", "").toLowerCase();
                appAlias.setText(name);
            }
        }
    }

    @FXML
    private void onAddApp() {
        String alias = appAlias.getText().trim().toLowerCase();
        String path  = appPath.getText().trim();

        if (alias.isEmpty()) {
            showFeedback(appFeedback, "Please enter a voice alias.", false);
            appAlias.requestFocus();
            return;
        }
        if (path.isEmpty()) {
            showFeedback(appFeedback, "Please enter or browse for the executable path.", false);
            appPath.requestFocus();
            return;
        }
        if (!new File(path).exists()) {
            showFeedback(appFeedback, "File not found at that path. Check it exists.", false);
            return;
        }

        try {
            DatabaseManager.getInstance().addAppAlias(alias, path);
            appAlias.clear();
            appPath.clear();
            loadApps();
            showFeedback(appFeedback, "✓  '" + alias + "' added successfully.", true);
        } catch (Exception e) {
            showFeedback(appFeedback,
                alias + " already exists. Remove it first to update.", false);
        }
    }

    private void confirmAndDeleteApp(RegistryRow row) {
        Alert confirm = buildConfirmDialog(
            "Remove '" + row.getAlias() + "'?",
            "This will remove the voice shortcut for '" + row.getAlias() + "'. " +
            "You can re-add it any time."
        );
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                DatabaseManager.getInstance().removeAppAlias(row.getAlias());
                loadApps();
            } catch (Exception e) {
                showFeedback(appFeedback, "Error removing app: " + e.getMessage(), false);
            }
        }
    }

    // ── Website handlers ──────────────────────────────────────────────────────

    @FXML
    private void onAddWebsite() {
        String alias = webAlias.getText().trim().toLowerCase();
        String url   = webUrl.getText().trim();

        if (alias.isEmpty()) {
            showFeedback(webFeedback, "Please enter a voice alias.", false);
            webAlias.requestFocus();
            return;
        }
        if (url.isEmpty()) {
            showFeedback(webFeedback, "Please enter a URL.", false);
            webUrl.requestFocus();
            return;
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) { // prepend scheme if omitted
            url = "https://" + url;
        }

        try {
            DatabaseManager.getInstance().addWebsiteAlias(alias, url);
            webAlias.clear();
            webUrl.clear();
            loadWebsites();
            showFeedback(webFeedback, "✓  '" + alias + "' added successfully.", true);
        } catch (Exception e) {
            showFeedback(webFeedback,
                alias + " already exists. Remove it first to update.", false);
        }
    }

    private void confirmAndDeleteWebsite(RegistryRow row) {
        Alert confirm = buildConfirmDialog(
            "Remove '" + row.getAlias() + "'?",
            "This will remove the voice shortcut for '" + row.getAlias() + "'."
        );
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                DatabaseManager.getInstance().removeWebsiteAlias(row.getAlias());
                loadWebsites();
            } catch (Exception e) {
                showFeedback(webFeedback, "Error removing website: " + e.getMessage(), false);
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private Alert buildConfirmDialog(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.getDialogPane().setStyle(
            "-fx-background-color: #03045E; -fx-border-color: #00B4D8; -fx-border-width: 1px;"
        );
        return alert;
    }

    private void showFeedback(Label label, String message, boolean success) {
        label.setText(message);
        label.setStyle("-fx-text-fill: " +
            (success ? "-color-success" : "-color-error") + "; -fx-font-size: 12px;");
        label.setVisible(true);
        PauseTransition pause = new PauseTransition(Duration.seconds(3));
        pause.setOnFinished(e -> label.setVisible(false));
        pause.play();
    }

    // ── Row model ─────────────────────────────────────────────────────────────

    public static class RegistryRow {
        private final String alias;
        private final String value;

        public RegistryRow(String alias, String value) {
            this.alias = alias != null ? alias : "";
            this.value = value != null ? value : "";
        }

        public String getAlias() { return alias; }
        public String getValue() { return value; }
    }
}
