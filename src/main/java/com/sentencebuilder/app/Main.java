/*
 * Starts the JavaFX application and opens the main window. The window's layout
 * is in main-view.fxml and its buttons are handled by MainController. Run it
 * from the repository root with ./gradlew run.
 */

package com.sentencebuilder.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class Main extends Application {

    /** Hands control to JavaFX, which creates a Main and calls start on the UI thread. */
    public static void main(String[] args) {
        launch(args);
    }

    /** Loads the main view from main-view.fxml and shows it in an 800x600 window. */
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(Main.class.getResource("/com/sentencebuilder/ui/main-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 800, 600);
        stage.setTitle("Sentence Builder");
        stage.setScene(scene);
        stage.show();
    }
}
