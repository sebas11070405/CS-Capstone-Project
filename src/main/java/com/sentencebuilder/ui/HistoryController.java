/*
 * Runs the History tab in history-view.fxml, which shows the generated sentence history so the
 * user can look for duplicates (spec 5, issue 50). The top table lists the most recently generated
 * sentences with the word each started from, the algorithm that made it, and when. The bottom
 * table lists sentences that were generated more than once, most repeated first.
 *
 * Sentences are saved raw by SentenceHistory and only formatted here for display, so duplicates
 * are still matched on the exact words. MainController calls reload whenever the tab is opened,
 * because sentences are generated on the Generate and Batch tabs. Database work runs on a
 * background thread so the window doesn't freeze.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database). Without them the tab still
 * opens, and the status line shows which key is missing.
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.GeneratedSentenceRepository;
import com.sentencebuilder.database.GeneratedSentenceRepository.DuplicateGroup;
import com.sentencebuilder.database.GeneratedSentenceRepository.GeneratedSentence;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;
import com.sentencebuilder.generator.SentenceFormatter;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

public class HistoryController {

    private static final int RECENT_LIMIT = 100;      // Enough to scroll through without a slow load
    private static final int DUPLICATE_LIMIT = 50;    // The most repeated ones matter most

    // Generation times are stored in UTC, so they're shown in the computer's own time zone
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    @FXML private TableView<GeneratedSentence> recentTable;
    @FXML private TableColumn<GeneratedSentence, String> recentSentenceColumn;
    @FXML private TableColumn<GeneratedSentence, String> recentStartColumn;
    @FXML private TableColumn<GeneratedSentence, String> recentAlgorithmColumn;
    @FXML private TableColumn<GeneratedSentence, OffsetDateTime> recentTimeColumn;

    @FXML private TableView<DuplicateGroup> duplicatesTable;
    @FXML private TableColumn<DuplicateGroup, String> duplicateSentenceColumn;
    @FXML private TableColumn<DuplicateGroup, Integer> duplicateTimesColumn;

    @FXML private Label statusLabel;   // What was loaded, or why it couldn't be

    private int latestLoad = 0;   // Only the newest load may fill the tables

    /** Both lists, read together so the tables always match each other. */
    private record History(List<GeneratedSentence> recent, List<DuplicateGroup> duplicates) {}

    /**
     * Hooks each column up to its field. Sentences are formatted only for display. The time column
     * keeps the real timestamp so clicking its header sorts by time, and only its text is formatted.
     */
    @FXML
    private void initialize() {
        recentSentenceColumn.setCellValueFactory(row ->
                new ReadOnlyObjectWrapper<>(SentenceFormatter.format(row.getValue().text())));
        recentStartColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().startWord()));
        recentAlgorithmColumn.setCellValueFactory(row ->
                new ReadOnlyObjectWrapper<>(algorithmLabel(row.getValue().algorithm())));
        recentTimeColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().generatedAt()));
        recentTimeColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(OffsetDateTime generatedAt, boolean empty) {
                super.updateItem(generatedAt, empty);
                setText(empty || generatedAt == null ? null
                        : TIME_FORMAT.format(generatedAt.atZoneSameInstant(ZoneId.systemDefault())));
            }
        });

        duplicateSentenceColumn.setCellValueFactory(row ->
                new ReadOnlyObjectWrapper<>(SentenceFormatter.format(row.getValue().text())));
        duplicateTimesColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().timesGenerated()));
    }

    /**
     * Reads the recent sentences and the duplicates on a background thread, then fills both
     * tables. If the tab is opened again before a load finishes, only the newest load is shown.
     */
    void reload() {
        int load = ++latestLoad;
        Task<History> task = new Task<>() {
            @Override
            protected History call() throws Exception {
                try (Connection conn = Database.getConnection()) {
                    GeneratedSentenceRepository repository = new GeneratedSentenceRepository(conn);
                    return new History(repository.findRecent(RECENT_LIMIT), repository.findDuplicates(DUPLICATE_LIMIT));
                }
            }
        };
        task.setOnSucceeded(event -> {
            if (load == latestLoad) {
                showHistory(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            if (load == latestLoad) {
                statusLabel.setText("Couldn't load the history: " + task.getException().getMessage());
            }
        });

        statusLabel.setText("Loading…");
        // A daemon thread doesn't keep the app running if the window is closed mid-load
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /** Puts both lists in their tables and sums them up in the status line. */
    private void showHistory(History history) {
        recentTable.getItems().setAll(history.recent());
        duplicatesTable.getItems().setAll(history.duplicates());

        if (history.recent().isEmpty()) {
            statusLabel.setText("");
            return;
        }
        String shown = history.recent().size() == RECENT_LIMIT
                ? "Showing the latest " + RECENT_LIMIT + " sentences"
                : "Showing all " + history.recent().size() + " generated sentences";
        int repeated = history.duplicates().size();
        statusLabel.setText(shown + ". " + (repeated == 0 ? "None were generated more than once."
                : repeated + (repeated == 1 ? " sentence was" : " sentences were") + " generated more than once."));
    }

    /**
     * Gives the readable name for a saved algorithm label. SentenceHistory saves Algorithm names,
     * so anything else (from older rows or other code) is shown as it was saved.
     */
    private static String algorithmLabel(String saved) {
        if (saved == null) {
            return "";
        }
        try {
            return switch (Algorithm.valueOf(saved)) {
                case WEIGHTED_RANDOM -> "Weighted random";
                case MOST_LIKELY -> "Most likely word";
                case TRIGRAM_BACKOFF -> "Trigram backoff";
            };
        } catch (IllegalArgumentException e) {
            return saved;
        }
    }
}
