/*
AI Assisted: Claude Sonnet 5.5

Prompt used: In the sentence builder assignment, implement BatchController class in
com.sentencebuilder.ui for creating Batch tab similar to MainController and WordsController.
There is already a backend: SentenceBatchService.generateBatch returns List<String>, and Importer.importGenerated 
saves a batch. Connect those controls: count field, ComboBox with algorithms, max words field, Generate button,
ListView of sentences, Add to Corpus button, ProgressBar, and Label of status. Validate both numbers
and give user notifications about errors in the status Label. Use Task for running generation and
saving operations on a separate thread and disable buttons during it. Keep original sentences in a field
and return them from forDisplay() method so that only raw sentences are saved. Display proper names
of algorithms, count repetitions, and disable Add to Corpus after successfully adding. Do not modify
SentenceBatchService and Importer classes.


Description:
Runs the Batch tab in batch-view.fxml. Generate makes the chosen number of sentences with
SentenceBatchService, each from a randomly picked starting word, and lists them. Add to Corpus
saves the listed sentences with Importer.importGenerated, so they count toward later generation
the way an imported file does. Database work runs off the JavaFX thread so the window doesn't
freeze.

The list keeps the raw sentences and only formats them for display (see forDisplay), so what
gets saved is always the raw text.

Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database), and some imported text to start
sentences from.
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.generator.SentenceBatchService;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;
import com.sentencebuilder.importer.CorpusStats;
import com.sentencebuilder.importer.Importer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

import java.util.HashSet;
import java.util.List;

public class BatchController {

    private static final int MAX_SENTENCES = 100;   // Keeps a batch short enough to read on screen

    @FXML private TextField countField;
    @FXML private ComboBox<Algorithm> algorithmBox;
    @FXML private TextField maxWordsField;
    @FXML private Button generateButton;
    @FXML private ListView<String> resultsList;
    @FXML private Button addButton;
    @FXML private ProgressBar progressBar;   // Shown only while the database is busy
    @FXML private Label statusLabel;

    // Raw text of the sentences on screen, which is what Add to Corpus saves
    private List<String> batch = List.of();
    private boolean addable = false;   // True while the batch on screen hasn't been added yet

    /** Fills the algorithm drop-down with readable names and starts with the first one selected. */
    @FXML
    private void initialize() {
        algorithmBox.getItems().setAll(Algorithm.values());
        // The enum names are for code, so the drop-down shows plain wording instead
        algorithmBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Algorithm algorithm) {
                return algorithm == null ? "" : algorithmLabel(algorithm);
            }

            @Override
            public Algorithm fromString(String label) {
                return null;   // The box isn't editable, so nothing is ever typed into it
            }
        });
        algorithmBox.getSelectionModel().selectFirst();
        addButton.setDisable(true);
    }

    /**
     * Reads the three settings, then generates a batch on a background thread and shows it when
     * it's done. A bad number is reported in the status line and nothing is generated.
     */
    @FXML
    private void onGenerate() {
        Integer sentenceCount = readWholeNumber(countField);
        Integer maxWords = readWholeNumber(maxWordsField);
        if (sentenceCount == null || maxWords == null) {
            statusLabel.setText("Enter whole numbers for the sentence count and maximum words");
            return;
        }
        if (sentenceCount < 1 || sentenceCount > MAX_SENTENCES) {
            statusLabel.setText("Choose between 1 and " + MAX_SENTENCES + " sentences");
            return;
        }
        if (maxWords < 1) {
            statusLabel.setText("Maximum words must be at least 1");
            return;
        }
        Algorithm algorithm = algorithmBox.getValue();

        // Generating reads word counts from the database once per word, so it can take seconds
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                return new SentenceBatchService().generateBatch(sentenceCount, algorithm, maxWords);
            }
        };
        task.setOnSucceeded(event -> showBatch(task.getValue()));
        task.setOnFailed(event -> {
            setBusy(false);
            statusLabel.setText("Couldn't generate: " + describe(task.getException()));
        });
        run(task, "Generating " + sentenceCount + " sentences…");
    }

    /**
     * Saves the sentences on screen into the database on a background thread. The button stays
     * disabled afterward, so the same batch can't be added twice by an accidental double click.
     */
    @FXML
    private void onAddToCorpus() {
        List<String> sentences = batch;
        if (sentences.isEmpty()) {
            return;
        }
        Task<CorpusStats> task = new Task<>() {
            @Override
            protected CorpusStats call() throws Exception {
                return new Importer().importGenerated(sentences);
            }
        };
        task.setOnSucceeded(event -> {
            addable = false;
            setBusy(false);
            statusLabel.setText(String.format("Added %d sentences to the corpus (%,d words)",
                    sentences.size(), task.getValue().wordCount()));
        });
        task.setOnFailed(event -> {
            setBusy(false);
            statusLabel.setText("Couldn't add the batch: " + describe(task.getException()));
        });
        run(task, "Adding " + sentences.size() + " sentences to the corpus…");
    }

    /**
     * Puts a new batch on screen and allows it to be added. Mentions repeats in the status line,
     * because the starting words are picked independently and the same sentence can come up twice.
     */
    private void showBatch(List<String> sentences) {
        batch = List.copyOf(sentences);
        addable = true;
        resultsList.getItems().setAll(sentences.stream().map(BatchController::forDisplay).toList());
        setBusy(false);

        int repeats = sentences.size() - new HashSet<>(sentences).size();
        String status = "Generated " + sentences.size() + " sentences";
        statusLabel.setText(repeats == 0 ? status : status + ", " + repeats + " of them repeats");
    }

    /** Shows the busy state and runs the task on its own thread. */
    private void run(Task<?> task, String message) {
        setBusy(true);
        statusLabel.setText(message);
        // A daemon thread doesn't keep the app running if the window is closed mid-task
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Disables the buttons and shows the progress bar while the database is busy, so a second task
     * can't start on top of the first. Add to Corpus only comes back if there is a batch to add.
     */
    private void setBusy(boolean busy) {
        generateButton.setDisable(busy);
        addButton.setDisable(busy || !addable);
        progressBar.setVisible(busy);
        progressBar.setManaged(busy);
    }

    /** Reads a whole number from the text field, or returns null if it isn't one. */
    private static Integer readWholeNumber(TextField field) {
        try {
            return Integer.valueOf(field.getText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Gives the wording the drop-down shows for each algorithm. */
    private static String algorithmLabel(Algorithm algorithm) {
        return switch (algorithm) {
            case MOST_LIKELY -> "Most likely word";
            case WEIGHTED_RANDOM -> "Weighted random";
            case TRIGRAM_BACKOFF -> "Trigram (falls back to pairs)";
        };
    }

    /**
     * Returns the text a sentence is shown as. For now that is the raw sentence. Once
     * SentenceFormatter is on main, this is the one place to call SentenceFormatter.format, so the
     * list reads like real sentences while batch keeps the raw text that gets saved.
     */
    private static String forDisplay(String rawSentence) {
        return rawSentence;
    }

    /** Turns a failure into the message the user sees. Most of ours already say what to do next. */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}