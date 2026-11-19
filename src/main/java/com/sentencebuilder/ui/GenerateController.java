/*
 * Runs the Generate panel in generate-view.fxml. It makes one sentence from a starting word
 * the user types, with the algorithm picked in the drop-down (Weighted random, Most frequent
 * next word, or Trigram backoff). Batches of sentences from random starting words are on the
 * Batch tab (BatchController).
 *
 * Database work runs on a background thread so the window doesn't freeze, because every
 * generated word is a database query.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database). Without them the panel still
 * opens, and the result text shows which key is missing.
 *
 * AI-assisted: Claude, Claude Code
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.PostgresTransitionRepo;
import com.sentencebuilder.database.PostgresTrigramRepo;
import com.sentencebuilder.generator.AbstractGenerator;
import com.sentencebuilder.generator.FrequentWordGenerator;
import com.sentencebuilder.generator.MarkovGenerator;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;
import com.sentencebuilder.generator.TrigramBackoffGenerator;
import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.tokenizer.Tokenizer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

import java.util.Optional;

public class GenerateController {

    private static final int MAX_WORDS = 20;   // Ends a sentence that never reaches a dead end

    @FXML private TextField startWordField;    // The word to start with
    @FXML private ComboBox<Algorithm> algorithmBox;
    @FXML private Button generateButton;
    @FXML private Label resultLabel;           // The generated sentence, or why there isn't one

    /** Fills the algorithm drop-down with readable names and starts on Weighted random. */
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
        // Weighted random gives the most varied sentences, so it's the default
        algorithmBox.getSelectionModel().select(Algorithm.WEIGHTED_RANDOM);
    }

    /**
     * Normalizes the typed word the same way imported text is, then generates one
     * sentence from it in the background with the chosen algorithm. Says so instead
     * if the word was never imported, since every algorithm would stop right away.
     */
    @FXML
    private void onGenerate() {
        String word = Tokenizer.normalizeWord(startWordField.getText());
        if (word == null) {
            resultLabel.setText("Please enter a single word.");
            return;
        }
        Algorithm algorithm = algorithmBox.getValue();

        // An empty result means the word isn't in the database
        Task<Optional<String>> task = new Task<>() {
            @Override
            protected Optional<String> call() {
                PostgresTransitionRepo bigrams = new PostgresTransitionRepo();
                if (!bigrams.wordExists(word)) {
                    return Optional.empty();
                }
                return Optional.of(createGenerator(algorithm, word, bigrams).generateSentence());
            }
        };
        task.setOnSucceeded(event -> {
            resultLabel.setText(task.getValue()
                    .orElse("\"" + word + "\" isn't in the database yet. Import some text that uses it."));
            generateButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            resultLabel.setText("Couldn't generate: " + task.getException().getMessage());
            generateButton.setDisable(false);
        });

        resultLabel.setText("Generating…");
        // Off while generating, so a second sentence can't start on top of the first
        generateButton.setDisable(true);
        start(task);
    }

    /**
     * Makes the generator for the chosen algorithm, starting from the given word.
     * The switch covers every Algorithm, so adding one without a generator here is
     * a compile error instead of a silent fallback. Trigram backoff gets no second
     * word, so it starts from bigrams and switches to trigrams once the sentence
     * has two words.
     */
    private static AbstractGenerator createGenerator(Algorithm algorithm, String startWord,
                                                     TransitionRepository bigrams) {
        return switch (algorithm) {
            case MOST_LIKELY -> new FrequentWordGenerator(MAX_WORDS, bigrams, startWord);
            case WEIGHTED_RANDOM -> new MarkovGenerator(MAX_WORDS, bigrams, startWord);
            case TRIGRAM_BACKOFF -> new TrigramBackoffGenerator(MAX_WORDS, bigrams, new PostgresTrigramRepo(),
                    startWord, null);
        };
    }

    /** Gives the wording the drop-down shows for each algorithm. */
    private static String algorithmLabel(Algorithm algorithm) {
        return switch (algorithm) {
            case WEIGHTED_RANDOM -> "Weighted random";
            case MOST_LIKELY -> "Most frequent next word";
            case TRIGRAM_BACKOFF -> "Trigram backoff";
        };
    }

    /**
     * Runs a task on its own thread. A daemon thread doesn't keep the app open if
     * the window is closed while it runs.
     */
    private static void start(Task<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }
}
