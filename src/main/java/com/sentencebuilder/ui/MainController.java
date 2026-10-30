/*
 * Handles the controls in main-view.fxml. Import Text File opens a file
 * chooser, imports the chosen .txt file into the database on a background
 * thread, and shows its progress in the progress bar and status label, then
 * the word counts or the error.
 *
 * Prompt: see src/main/java/com/sentencebuilder/importer/Importer.java
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.importer.CorpusStats;
import com.sentencebuilder.importer.Importer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.stage.FileChooser;

import java.io.File;

public class MainController {
    @FXML
    private Button importButton;

    @FXML
    private ProgressBar progressBar;   // Shown only while an import runs

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

        // Import off the JavaFX thread so the window doesn't freeze on large files. updateProgress and
        // updateMessage are safe to call from that thread, and the success and failure handlers run
        // back on the JavaFX thread.
        Task<CorpusStats> task = new Task<>() {
            @Override
            protected CorpusStats call() throws Exception {
                return new Importer().importFile(file.toPath(), (fraction, message) -> {
                    updateProgress(fraction, 1);
                    updateMessage(message);
                });
            }
        };
        task.setOnSucceeded(event -> {
            CorpusStats stats = task.getValue();
            finishImport(String.format("Imported %s: %,d words, %,d distinct, %,d word pairs, %,d word triples",
                    file.getName(), stats.wordCount(), stats.words().size(), stats.bigrams().size(),
                    stats.trigrams().size()));
        });
        task.setOnFailed(event -> finishImport("Import failed: " + task.getException().getMessage()));

        // Show the task's progress while it runs
        importButton.setDisable(true);
        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        statusLabel.textProperty().bind(task.messageProperty());

        // A daemon thread doesn't keep the app running if the window is closed mid-import
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /** Hides the progress bar, shows the final status, and lets the user import again. */
    private void finishImport(String status) {
        progressBar.progressProperty().unbind();
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        statusLabel.textProperty().unbind();
        statusLabel.setText(status);
        importButton.setDisable(false);
    }
}
