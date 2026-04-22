package com.rahbar;

/**
 * Launcher — non-JavaFX entry point.
 *
 * IntelliJ/Java throws "JavaFX runtime components are missing" when the
 * main class directly extends javafx.application.Application and JavaFX
 * is on the classpath instead of the module path.
 *
 * This plain class has no JavaFX parent, so the check is bypassed.
 * It simply delegates to RahbarApp.main() which calls Application.launch().
 *
 * Run THIS class in IntelliJ, not RahbarApp.
 */
public class Launcher {
    public static void main(String[] args) {
        RahbarApp.main(args);
    }
}
