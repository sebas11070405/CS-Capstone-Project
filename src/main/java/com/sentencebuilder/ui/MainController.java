/*
 * Handles the controls in main-view.fxml. Import Text File opens a file
 * chooser, imports the chosen .txt file into the database on a background
 * thread, and shows its progress in the progress bar and status label, then
 * the word counts or the error.
 *
 * Prompt: see src/main/java/com/sentencebuilder/importer/Importer.java
 *
 * Below that, the Import tab lists every imported file and generated batch
 * with its word count and import time (spec 1), plus a total. The list
 * reloads after each import and whenever the tab is opened, so batches added
 * from other tabs show up too.
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.SourceFileRepository;
import com.sentencebuilder.database.SourceFileRepository.SourceFile;
import com.sentencebuilder.importer.CorpusStats;
import com.sentencebuilder.importer.Importer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;

import java.io.File;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

public class MainController {

    // Import times are stored in UTC, so they're shown in the computer's own time zone
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    @FXML
    private Tab importTab;

    @FXML
    private Button importButton;

    @FXML
    private ProgressBar progressBar;   // Shown only while an import runs

    @FXML
    private Label statusLabel;

    @FXML private TableView<SourceFile> sourcesTable;
    @FXML private TableColumn<SourceFile, String> sourceNameColumn;
    @FXML private TableColumn<SourceFile, String> sourceTypeColumn;
    @FXML private TableColumn<SourceFile, Integer> sourceWordsColumn;
    @FXML private TableColumn<SourceFile, OffsetDateTime> sourceTimeColumn;
    @FXML private Label sourcesSummary;   // How many files and batches, and their words in all

    /**
     * Hooks each table column up to a SourceFile field and loads the list. The time column keeps
     * the real timestamp so clicking its header sorts by time, and only its text is formatted.
     */
    @FXML
    private void initialize() {
        sourceNameColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().fileName()));
        sourceTypeColumn.setCellValueFactory(row ->
                new ReadOnlyObjectWrapper<>(row.getValue().generated() ? "Generated" : "File"));
        sourceWordsColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().wordCount()));
        sourceTimeColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().importedAt()));
        sourceTimeColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(OffsetDateTime importedAt, boolean empty) {
                super.updateItem(importedAt, empty);
                setText(empty || importedAt == null ? null
                        : TIME_FORMAT.format(importedAt.atZoneSameInstant(ZoneId.systemDefault())));
            }
        });

        // Batches can be added from other tabs, so reload whenever the user comes back here
        importTab.setOnSelectionChanged(event -> {
            if (importTab.isSelected()) {
                loadSources();
            }
        });
        loadSources();
    }

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
            finishImport(String.format("Imported %s: %,d words, %,d distinct",
                    file.getName(), stats.wordCount(), stats.words().size()));
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
        loadSources();
    }

    /**
     * Reads every imported file and generated batch on a background thread, then fills the table
     * and the total below it. If the database can't be reached, the total line says why instead.
     */
    private void loadSources() {
        Task<List<SourceFile>> task = new Task<>() {
            @Override
            protected List<SourceFile> call() throws Exception {
                try (Connection conn = Database.getConnection()) {
                    return new SourceFileRepository(conn).findAll();
                }
            }
        };
        task.setOnSucceeded(event -> showSources(task.getValue()));
        task.setOnFailed(event ->
                sourcesSummary.setText("Couldn't load the imported files: " + task.getException().getMessage()));

        // A daemon thread doesn't keep the app running if the window is closed mid-load
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /** Puts the sources in the table and sums them up as files, batches, and words in all. */
    private void showSources(List<SourceFile> sources) {
        sourcesTable.getItems().setAll(sources);
        if (sources.isEmpty()) {
            sourcesSummary.setText("");
            return;
        }
        long batches = sources.stream().filter(SourceFile::generated).count();
        long files = sources.size() - batches;
        long words = sources.stream().mapToLong(SourceFile::wordCount).sum();
        sourcesSummary.setText(String.format("%,d %s and %,d generated %s, %,d words in all",
                files, files == 1 ? "file" : "files", batches, batches == 1 ? "batch" : "batches", words));
    }
}
