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

    @FXML
    protected void onImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Text File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File texts = new File("texts");
        if (texts.isDirectory()) {
            chooser.setInitialDirectory(texts);
        }
        File file = chooser.showOpenDialog(importButton.getScene().getWindow());
        if (file == null) {
            return;
        }

        Task<CorpusStats> task = new Task<>() {
            @Override
            protected CorpusStats call() throws Exception {
                return new Importer().importFile(file.toPath());
            }
        };
        task.setOnSucceeded(e -> {
            CorpusStats stats = task.getValue();
            statusLabel.setText(String.format("Imported %s: %,d words, %,d distinct",
                    file.getName(), stats.wordCount(), stats.words().size()));
            importButton.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.setText("Import failed: " + task.getException().getMessage());
            importButton.setDisable(false);
        });

        importButton.setDisable(true);
        statusLabel.setText("Importing " + file.getName() + "…");
        new Thread(task).start();
    }
}
