/*
 * Handles the controls in main-view.fxml. Import Text File opens a file
 * chooser, imports the chosen .txt file into the database on a background
 * thread, and shows the word counts or the error in the status label.
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.importer.CorpusStats;
import com.sentencebuilder.importer.Importer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;

import java.io.File;

public class MainController {
    @FXML
    private Button importButton;

    @FXML
    private Label statusLabel;

    /**
     * Lets the user pick a text file and imports it. The button stays disabled
     * until the import finishes so a second import can't start on top of it.
     */
    @FXML
    protected void onImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Text File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
        // Start in the project's sample texts when the app is run from the repository root
        File texts = new File("texts");
        if (texts.isDirectory()) {
            chooser.setInitialDirectory(texts);
        }
        File file = chooser.showOpenDialog(importButton.getScene().getWindow());
        if (file == null) {
            return;
        }

        // Import off the JavaFX thread so the window doesn't freeze on large files.
        // The success and failure handlers run back on the JavaFX thread.
        Task<CorpusStats> task = new Task<>() {
            @Override
            protected CorpusStats call() throws Exception {
                return new Importer().importFile(file.toPath());
            }
        };
        task.setOnSucceeded(event -> {
            CorpusStats stats = task.getValue();
            statusLabel.setText(String.format("Imported %s: %,d words, %,d distinct",
                    file.getName(), stats.wordCount(), stats.words().size()));
            importButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            statusLabel.setText("Import failed: " + task.getException().getMessage());
            importButton.setDisable(false);
        });

        importButton.setDisable(true);
        statusLabel.setText("Importing " + file.getName() + "…");
        new Thread(task).start();
    }
}
