/*
 * Runs the Generate panel in generate-view.fxml. The top half makes one sentence from a
 * starting word the user types, with the algorithm picked in the drop-down (Weighted random,
 * Most frequent next word, or Trigram backoff). The bottom half makes a batch of sentences
 * from random starting words with SentenceBatchService, and Add to corpus feeds that batch
 * back into the database with Importer.importGenerated, so the new sentences count like
 * imported text.
 *
 * All database work runs on a background thread so the window doesn't freeze, because every
 * generated word is a database query.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database). Without them the panel still
 * opens, and the status text shows which key is missing.
 *
 * AI-assisted: Claude, Claude Code
 *
 * Prompt:
 * TODO(Mahale03): paste the prompt that produced the original GenerateScreen word for word
 *
 * Follow-up prompt:
 * Ok, can you refactor pr 40 to follow the guidelines we requested?
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.PostgresTransitionRepo;
import com.sentencebuilder.database.PostgresTrigramRepo;
import com.sentencebuilder.generator.AbstractGenerator;
import com.sentencebuilder.generator.FrequentWordGenerator;
import com.sentencebuilder.generator.MarkovGenerator;
import com.sentencebuilder.generator.SentenceBatchService;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;
import com.sentencebuilder.generator.TrigramBackoffGenerator;
import com.sentencebuilder.importer.CorpusStats;
import com.sentencebuilder.importer.Importer;
import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.tokenizer.Tokenizer;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GenerateController {

    private static final int MAX_WORDS = 20;       // Ends a sentence that never reaches a dead end
    private static final int MAX_RANDOM = 100;     // Each sentence is many queries, so cap the batch

    @FXML private TextField startWordField;        // The word to start with
    @FXML private ComboBox<Algorithm> algorithmBox;
    @FXML private Button generateButton;
    @FXML private Label resultLabel;
    @FXML private TextField countField;            // How many random sentences
    @FXML private Button randomButton;
    @FXML private ListView<String> sentenceList;   // The latest random batch, which Add to corpus saves
    @FXML private Button addButton;
    @FXML private Label statusLabel;               // Messages for the bottom half

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
            setBusy(false);
        });
        task.setOnFailed(event -> {
            resultLabel.setText("Couldn't generate: " + task.getException().getMessage());
            setBusy(false);
        });

        resultLabel.setText("Generating…");
        setBusy(true);
        start(task);
    }

    /**
     * Checks the count, then has SentenceBatchService make that many sentences in the
     * background, each from a random word that has started a sentence before.
     */
    @FXML
    private void onGenerateRandom() {
        int count;
        try {
            count = Integer.parseInt(countField.getText().trim());
        } catch (NumberFormatException e) {
            count = 0;   // Not a number, so the range check below rejects it
        }
        if (count < 1 || count > MAX_RANDOM) {
            statusLabel.setText("Enter a number from 1 to " + MAX_RANDOM + ".");
            return;
        }
        int total = count;
        Algorithm algorithm = algorithmBox.getValue();

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                return new SentenceBatchService().generateBatch(total, algorithm, MAX_WORDS);
            }
        };
        task.setOnSucceeded(event -> {
            sentenceList.getItems().setAll(task.getValue());
            finishTask("Made " + total + " sentences. Add to corpus saves them as new text.");
        });
        task.setOnFailed(event -> {
            sentenceList.getItems().clear();
            finishTask("Couldn't generate: " + task.getException().getMessage());
        });

        statusLabel.setText("Generating " + total + " sentences…");
        setBusy(true);
        start(task);
    }

    /**
     * Saves the sentences in the list as a generated batch, so their words, pairs,
     * and triples are counted like an imported file. Clears the list afterward so
     * the same batch isn't added twice by accident.
     */
    @FXML
    private void onAddToCorpus() {
        List<String> sentences = new ArrayList<>(sentenceList.getItems());
        if (sentences.isEmpty()) {
            statusLabel.setText("Generate some random sentences first.");
            return;
        }

        Task<CorpusStats> task = new Task<>() {
            @Override
            protected CorpusStats call() throws Exception {
                return new Importer().importGenerated(sentences);
            }
        };
        task.setOnSucceeded(event -> {
            sentenceList.getItems().clear();
            finishTask(String.format("Added %d sentences (%,d words) to the corpus.",
                    sentences.size(), task.getValue().wordCount()));
        });
        task.setOnFailed(event -> finishTask("Couldn't add them: " + task.getException().getMessage()));

        statusLabel.setText("Adding to the corpus…");
        setBusy(true);
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

    /** Shows the bottom half's final message and turns the buttons back on. */
    private void finishTask(String message) {
        statusLabel.setText(message);
        setBusy(false);
    }

    /** Turns the buttons off while a task runs, so a second one can't start on top of it. */
    private void setBusy(boolean busy) {
        generateButton.setDisable(busy);
        randomButton.setDisable(busy);
        addButton.setDisable(busy);
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
