package com.rahbar.ui;

import com.rahbar.db.DatabaseManager;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class OnboardingWindow {

    private static final double W = 480.0;
    private static final double H = 380.0;

    private Stage    stage;
    private Runnable onComplete;

    // Step 1 inputs
    private TextField fieldName;
    private TextField fieldNickname;

    // Step 2 input
    private ThemeManager.Theme selectedTheme = ThemeManager.Theme.NAVY_CYAN;

    // Current step (0-indexed)
    private int currentStep = 0;
    private StackPane stepArea;
    private Button    btnNext;
    private Label     stepIndicator;

    public void show(Runnable onComplete) {
        this.onComplete = onComplete;
        Platform.runLater(this::build);
    }

    private void build() {
        VBox root = new VBox(0);
        root.setStyle(
            "-fx-background-color: #020124;" +
            "-fx-border-color: #003D4D;" +
            "-fx-border-width: 1px;"
        );

        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 20, 14, 20));
        header.setStyle("-fx-background-color: #020135; -fx-border-color: #003D4D; -fx-border-width: 0 0 1 0;");

        Label logo = new Label("R.A.H.B.A.R");
        logo.setStyle("-fx-text-fill: #00B4D8; -fx-font-size: 15px; -fx-font-weight: bold;" +
                      "-fx-font-family: 'Consolas', 'Courier New', monospace;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        stepIndicator = new Label("Step 1 of 3");
        stepIndicator.setStyle("-fx-text-fill: #4A7A8A; -fx-font-size: 10px;" +
                               "-fx-font-family: 'Consolas', 'Courier New', monospace;");

        header.getChildren().addAll(logo, headerSpacer, stepIndicator);

        stepArea = new StackPane();
        stepArea.setPadding(new Insets(28, 32, 20, 32));
        VBox.setVgrow(stepArea, Priority.ALWAYS);

        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(14, 20, 14, 20));
        footer.setStyle("-fx-background-color: #020135; -fx-border-color: #003D4D; -fx-border-width: 1 0 0 0;");

        // Progress dots
        HBox dots = new HBox(8);
        dots.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < 3; i++) {
            Label dot = new Label("●");
            dot.setId("step-dot-" + i);
            dot.setStyle("-fx-text-fill: " + (i == 0 ? "#00B4D8" : "#003D4D") +
                         "; -fx-font-size: 8px;");
            dots.getChildren().add(dot);
        }

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        btnNext = new Button("Next  ›");
        btnNext.setStyle(
            "-fx-background-color: #00B4D8; -fx-text-fill: #020124;" +
            "-fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;" +
            "-fx-font-family: 'Consolas', 'Courier New', monospace;" +
            "-fx-padding: 8 20 8 20; -fx-border-radius: 0; -fx-background-radius: 0;"
        );
        btnNext.setOnAction(e -> advanceStep());

        footer.getChildren().addAll(dots, footerSpacer, btnNext);

        root.getChildren().addAll(header, stepArea, footer);

        Scene scene = new Scene(root, W, H);
        scene.setFill(Color.TRANSPARENT);

        try { // apply theme CSS so onboarding matches app style
            String themeName = DatabaseManager.getInstance().getSetting("theme");
            ThemeManager.Theme t = ThemeManager.getInstance().fromStoredName(themeName);
            ThemeManager.getInstance().init(scene, t);
        } catch (Exception ignored) {
            ThemeManager.getInstance().init(scene, ThemeManager.Theme.NAVY_CYAN);
        }

        stage = new Stage();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setResizable(false);
        stage.setTitle("R.A.H.B.A.R — Setup");
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);

        centerOnScreen();
        showStep(0);
        stage.show();
    }

    private void showStep(int step) {
        currentStep = step;
        stepIndicator.setText("Step " + (step + 1) + " of 3");
        btnNext.setText(step == 2 ? "Finish  ✓" : "Next  ›");

        stepArea.getScene().getRoot().lookupAll("[id]").forEach(n -> {
            if (n.getId() != null && n.getId().startsWith("step-dot-")) {
                int dotIdx = Integer.parseInt(n.getId().substring(9));
                n.setStyle("-fx-text-fill: " + (dotIdx <= step ? "#00B4D8" : "#003D4D") +
                           "; -fx-font-size: 8px;");
            }
        });

        VBox content = switch (step) {
            case 0  -> buildStep1();
            case 1  -> buildStep2();
            default -> buildStep3();
        };

        content.setOpacity(0);
        stepArea.getChildren().setAll(content);

        FadeTransition ft = new FadeTransition(Duration.millis(200), content);
        ft.setFromValue(0); ft.setToValue(1); ft.play();
    }

    private VBox buildStep1() {
        VBox box = new VBox(18);
        box.setAlignment(Pos.TOP_LEFT);

        Label title = styledTitle("Welcome to R.A.H.B.A.R");
        Label sub   = styledSub("Let's get you set up. First, tell us your name.");

        Label lblName = styledFormLabel("Your First Name");
        fieldName = new TextField();
        fieldName.setPromptText("e.g. Tony");
        styleTextField(fieldName);

        try {
            String saved = DatabaseManager.getInstance().getSetting("profile_first_name");
            if (saved != null && !saved.isBlank()) fieldName.setText(saved);
        } catch (Exception ignored) {}

        Label lblNick = styledFormLabel("What should R.A.H.B.A.R call you?");
        fieldNickname = new TextField();
        fieldNickname.setPromptText("e.g. Boss, Tony, Sir");
        styleTextField(fieldNickname);

        try {
            String saved = DatabaseManager.getInstance().getSetting("profile_nickname");
            if (saved != null && !saved.isBlank()) fieldNickname.setText(saved);
        } catch (Exception ignored) {}

        Label hint = styledBody("This name appears in greetings: 'Good morning, Tony.'");

        box.getChildren().addAll(title, sub, lblName, fieldName, lblNick, fieldNickname, hint);
        return box;
    }

    private VBox buildStep2() {
        VBox box = new VBox(16);
        box.setAlignment(Pos.TOP_LEFT);

        Label title = styledTitle("Choose Your Theme");
        Label sub   = styledSub("Pick the color palette you want. You can change this any time.");

        HBox row1 = new HBox(12);
        HBox row2 = new HBox(12);

        row1.getChildren().addAll(
            buildThemeOption(ThemeManager.Theme.NAVY_CYAN,      "#03045E", "#00B4D8", "Navy + Cyan",      "Jarvis classic"),
            buildThemeOption(ThemeManager.Theme.BLACK_BLUE,     "#000000", "#3D7EFF", "Black + Blue",     "Dark sci-fi")
        );
        row2.getChildren().addAll(
            buildThemeOption(ThemeManager.Theme.CHARCOAL_GREEN, "#111411", "#39FF14", "Charcoal + Green", "Terminal hacker"),
            buildThemeOption(ThemeManager.Theme.PURPLE_GOLD,    "#0E0620", "#FFD700", "Purple + Gold",    "Premium luxury")
        );

        box.getChildren().addAll(title, sub, row1, row2);
        return box;
    }

    private VBox buildStep3() {
        VBox box = new VBox(18);
        box.setAlignment(Pos.TOP_LEFT);

        Label title = styledTitle("Test Your Microphone");
        Label sub   = styledSub("R.A.H.B.A.R will listen for your voice. Say the wake phrase to test it.");

        // Wake phrase display
        VBox phraseBox = new VBox(8);
        phraseBox.setPadding(new Insets(14));
        phraseBox.setStyle("-fx-background-color: #030860; -fx-border-color: #00B4D8; -fx-border-width: 1px;");

        Label phraseLabel = new Label("Say this phrase:");
        phraseLabel.setStyle("-fx-text-fill: #4A7A8A; -fx-font-size: 11px;" +
                             "-fx-font-family: 'Consolas', 'Courier New', monospace;");

        Label phrase = new Label("\"RAHBAR guide me\"");
        phrase.setStyle("-fx-text-fill: #00B4D8; -fx-font-size: 18px; -fx-font-weight: bold;" +
                        "-fx-font-family: 'Consolas', 'Courier New', monospace;");

        phraseBox.getChildren().addAll(phraseLabel, phrase);

        Label note = styledBody(
            "If R.A.H.B.A.R responds, your microphone is working correctly.\n" +
            "If not, check your mic settings and try again after setup."
        );

        Label skip = new Label("You can skip this and test later from the Settings panel.");
        skip.setStyle("-fx-text-fill: #4A7A8A; -fx-font-size: 10px;" +
                      "-fx-font-family: 'Consolas', 'Courier New', monospace;");

        box.getChildren().addAll(title, sub, phraseBox, note, skip);
        return box;
    }

    private void advanceStep() {
        if (currentStep == 0) {
            String name = fieldName.getText().trim();
            String nick = fieldNickname.getText().trim();
            if (name.isEmpty()) {
                fieldName.setStyle(fieldName.getStyle() + "-fx-border-color: #E74C3C;");
                return;
            }
            if (nick.isEmpty()) nick = name;
            try {
                DatabaseManager db = DatabaseManager.getInstance();
                db.saveSetting("profile_first_name", name);
                db.saveSetting("profile_nickname",   nick);
            } catch (Exception e) {
                System.err.println("[Onboarding] Could not save name: " + e.getMessage());
            }
            showStep(1);

        } else if (currentStep == 1) {
            try {
                DatabaseManager.getInstance().saveSetting("theme", selectedTheme.name());
                ThemeManager.getInstance().applyTheme(selectedTheme);
            } catch (Exception e) {
                System.err.println("[Onboarding] Could not save theme: " + e.getMessage());
            }
            showStep(2);

        } else {
            finish();
        }
    }

    private void finish() {
        try {
            DatabaseManager.getInstance().saveSetting("first_launch", "false");
        } catch (Exception e) {
            System.err.println("[Onboarding] Could not mark first_launch done: " + e.getMessage());
        }
        stage.close();
        if (onComplete != null) onComplete.run();
    }

    private VBox buildThemeOption(ThemeManager.Theme theme, String bg, String accent,
                                   String name, String desc) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setPrefWidth(190);
        card.setCursor(javafx.scene.Cursor.HAND);
        updateThemeCardStyle(card, theme == selectedTheme, accent, bg);

        HBox swatches = new HBox(4);
        Region s1 = swatch(bg);
        Region s2 = swatch(accent);
        swatches.getChildren().addAll(s1, s2);

        Label lName = new Label(name);
        lName.setStyle("-fx-text-fill: #E8F4F8; -fx-font-size: 12px; -fx-font-weight: bold;" +
                       "-fx-font-family: 'Consolas', 'Courier New', monospace;");
        Label lDesc = new Label(desc);
        lDesc.setStyle("-fx-text-fill: #4A7A8A; -fx-font-size: 10px;");

        card.getChildren().addAll(swatches, lName, lDesc);
        card.setOnMouseClicked(e -> {
            selectedTheme = theme;
            if (card.getParent() instanceof HBox row) {
                VBox grid = (VBox) row.getParent();
                grid.getChildren().forEach(child -> {
                    if (child instanceof HBox r) {
                        r.getChildren().forEach(c -> {
                            if (c instanceof VBox tc && tc.getUserData() instanceof ThemeManager.Theme t) {
                                updateThemeCardStyle(tc, t == selectedTheme,
                                    themeAccent(t), themeBg(t));
                            }
                        });
                    }
                });
            }
            updateThemeCardStyle(card, true, accent, bg);
            ThemeManager.getInstance().applyTheme(theme);
        });
        card.setUserData(theme);
        return card;
    }

    private void updateThemeCardStyle(VBox card, boolean active, String accent, String bg) {
        card.setStyle(
            "-fx-background-color: " + (active ? bg : "#020135") + ";" +
            "-fx-border-color: " + (active ? accent : "#003D4D") + ";" +
            "-fx-border-width: " + (active ? "2" : "1") + "px;"
        );
    }

    private Region swatch(String color) {
        Region r = new Region();
        r.setPrefWidth(20); r.setPrefHeight(20);
        r.setStyle("-fx-background-color: " + color + ";");
        return r;
    }

    private String themeAccent(ThemeManager.Theme t) {
        return switch (t) {
            case BLACK_BLUE     -> "#3D7EFF";
            case CHARCOAL_GREEN -> "#39FF14";
            case PURPLE_GOLD    -> "#FFD700";
            default             -> "#00B4D8";
        };
    }

    private String themeBg(ThemeManager.Theme t) {
        return switch (t) {
            case BLACK_BLUE     -> "#000000";
            case CHARCOAL_GREEN -> "#111411";
            case PURPLE_GOLD    -> "#0E0620";
            default             -> "#03045E";
        };
    }


    private Label styledTitle(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #E8F4F8; -fx-font-size: 18px; -fx-font-weight: bold;" +
                   "-fx-font-family: 'Consolas', 'Courier New', monospace;");
        return l;
    }

    private Label styledSub(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #90E0EF; -fx-font-size: 12px;" +
                   "-fx-font-family: 'Consolas', 'Courier New', monospace;");
        l.setWrapText(true);
        return l;
    }

    private Label styledFormLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #4A7A8A; -fx-font-size: 10px; -fx-font-weight: bold;" +
                   "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-letter-spacing: 1px;");
        return l;
    }

    private Label styledBody(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #4A7A8A; -fx-font-size: 11px;");
        l.setWrapText(true);
        return l;
    }

    private void styleTextField(TextField f) {
        f.setStyle(
            "-fx-background-color: #030860; -fx-text-fill: #E8F4F8;" +
            "-fx-prompt-text-fill: #4A7A8A; -fx-border-color: #003D4D;" +
            "-fx-border-width: 1px; -fx-border-radius: 0; -fx-background-radius: 0;" +
            "-fx-padding: 8 12 8 12; -fx-font-size: 13px;" +
            "-fx-font-family: 'Consolas', 'Courier New', monospace;"
        );
    }

    private void centerOnScreen() {
        javafx.geometry.Rectangle2D screen =
            javafx.stage.Screen.getPrimary().getVisualBounds();
        stage.setX((screen.getWidth()  - W) / 2.0);
        stage.setY((screen.getHeight() - H) / 2.0);
    }

    public static boolean isFirstLaunch() {
        try {
            String val = DatabaseManager.getInstance().getSetting("first_launch");
            return val == null || "false".equals(val);
        } catch (Exception e) {
            return true; // assume first launch if DB not ready
        }
    }
}
