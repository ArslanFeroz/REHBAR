package com.rahbar.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainWindow {

    private static final double WINDOW_WIDTH  = 960.0; // overridden by setMaximized(true)
    private static final double WINDOW_HEIGHT = 620.0;

    private Stage                stage;
    private MainWindowController controller;
    private boolean              built = false;

    public void show() {
        if (!built) { build(); built = true; }
        stage.show();
        stage.toFront();
    }

    public void hide() { if (stage != null) stage.hide(); }

    public boolean isShowing() { return stage != null && stage.isShowing(); }

    public MainWindowController getController() { return controller; }


    private void build() {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/MainWindow.fxml"));

        Parent root;
        try {
            root = loader.load();
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                    "Failed to load MainWindow.fxml — check src/main/resources/fxml/MainWindow.fxml", e);
        }

        controller = loader.getController();

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);

        ThemeManager.Theme savedTheme = loadSavedTheme();
        ThemeManager.getInstance().init(scene, savedTheme);

        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setResizable(true);
        stage.setTitle("R.A.H.B.A.R");
        stage.setScene(scene);

        controller.setStage(stage);

        // Tell the controller the scene is ready so it can store userData
        controller.onSceneReady();

        stage.setMaximized(true);
    }

    private ThemeManager.Theme loadSavedTheme() {
        try {
            String saved = com.rahbar.db.DatabaseManager.getInstance().getSetting("theme");
            return ThemeManager.getInstance().fromStoredName(saved);
        } catch (Exception e) {
            return ThemeManager.Theme.NAVY_CYAN;
        }
    }

}