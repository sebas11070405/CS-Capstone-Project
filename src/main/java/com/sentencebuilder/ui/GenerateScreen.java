/*
 * The Generate screen. The top half makes one sentence from a starting word the
 * user types, with the algorithm picked in the drop-down (Weighted random, Most
 * frequent next word, or Trigram backoff). The bottom half makes a batch of
 * sentences from random starting words, and Add to corpus feeds that batch back
 * into the database with Importer.importGenerated, so the new sentences count
 * like imported text.
 *
 * The screen is a VBox, so it can be placed straight into a layout or into FXML
 * as <GenerateScreen/>. All database work runs on a background thread so the
 * window doesn't freeze, because every generated word is a database query.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database). Without them the
 * screen still opens, and the status text shows which key is missing.
 *
 * AI-assisted: Claude

 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.StartWordRepository;
import com.sentencebuilder.generator.AbstractGenerator;
import com.sentencebuilder.generator.FrequentWordGenerator;
import com.sentencebuilder.generator.MarkovGenerator;
import com.sentencebuilder.generator.TrigramBackoffGenerator;
import com.sentencebuilder.importer.CorpusStats;
import com.sentencebuilder.importer.Importer;
import com.sentencebuilder.database.PostgresTransitionRepo;
import com.sentencebuilder.database.PostgresTrigramRepo;
import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.tokenizer.Tokenizer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GenerateScreen extends VBox {

    private static final String WEIGHTED = "Weighted random";
    private static final String MOST_FREQUENT = "Most frequent next word";
    private static final String TRIGRAM = "Trigram backoff";

    private static final int MAX_WORDS = 20;       // Ends a sentence that never reaches a dead end
    private static final int MAX_RANDOM = 100;     // Each sentence is many queries, so cap the batch

    private final TextField startWordField = new TextField();       // The word to start with
    private final ComboBox<String> algorithmBox = new ComboBox<>(); // Which algorithm to use
    private final Button generateButton = new Button("Generate");
    private final Label resultLabel = new Label("Type a starting word and press Generate.");
    private final TextField countField = new TextField("10");       // How many random sentences
    private final Button randomButton = new Button("Generate random sentences");
    private final ListView<String> sentenceList = new ListView<>();  // The latest random batch
    private final Button addButton = new Button("Add to corpus");
    private final Label statusLabel = new Label();                  // Messages for the bottom half

    /** Builds the screen's controls and lays them out top to bottom. */
    public GenerateScreen() {
        super(8);

        Label title = new Label("Generate Sentences");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        // Top half: starting word, algorithm, and the Generate button on one row.
        // Pressing Enter in the word box generates too.
        startWordField.setPromptText("e.g. the");
        startWordField.setOnAction(event -> generate());
        algorithmBox.getItems().addAll(WEIGHTED, MOST_FREQUENT, TRIGRAM);
        algorithmBox.getSelectionModel().selectFirst();
        algorithmBox.setMaxWidth(Double.MAX_VALUE);
        generateButton.setOnAction(event -> generate());

        VBox wordColumn = new VBox(3, new Label("Starting word"), startWordField);
        VBox algorithmColumn = new VBox(3, new Label("Algorithm"), algorithmBox);
        HBox.setHgrow(wordColumn, Priority.ALWAYS);
        HBox.setHgrow(algorithmColumn, Priority.ALWAYS);
        HBox topRow = new HBox(8, wordColumn, algorithmColumn, generateButton);
        topRow.setAlignment(Pos.BOTTOM_LEFT);   // Lines the button up with the boxes

        // Bordered box that shows the generated sentence
        resultLabel.setWrapText(true);
        resultLabel.setMaxWidth(Double.MAX_VALUE);
        resultLabel.setMinHeight(45);
        resultLabel.setStyle("-fx-border-color: #aaaaaa; -fx-padding: 10; -fx-background-color: #f2f2f2;");

        // Bottom half: how many, the random batch, and Add to corpus. The list takes
        // whatever height is left over.
        countField.setPrefColumnCount(4);
        randomButton.setOnAction(event -> generateRandom());
        HBox countRow = new HBox(8, countField, randomButton);

        sentenceList.setPlaceholder(new Label("Generated sentences will appear here."));
        VBox.setVgrow(sentenceList, Priority.ALWAYS);

        addButton.setOnAction(event -> addToCorpus());
        HBox addRow = new HBox(12, addButton, statusLabel);
        addRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(
                title, topRow, resultLabel,
                new Separator(),
                new Label("How many"), countRow,
                sentenceList, addRow);
        setPadding(new Insets(16));
    }

    /**
     * Normalizes the typed word the same way imported text is, then generates one
     * sentence from it in the background with the chosen algorithm. Says so instead
     * if the word was never imported, since every algorithm would stop right away.
     */
    private void generate() {
        String word = Tokenizer.normalizeWord(startWordField.getText());
        if (word == null) {
            resultLabel.setText("Please enter a single word.");
            return;
        }
        String algorithm = algorithmBox.getValue();

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
     * Checks the count, then makes that many sentences in the background, each from
     * a random word that has started a sentence before. Progress shows in the status
     * label because a batch can take a while.
     */
    private void generateRandom() {
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
        String algorithm = algorithmBox.getValue();

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                PostgresTransitionRepo bigrams = new PostgresTransitionRepo();
                List<String> sentences = new ArrayList<>();
                try (Connection conn = Database.getConnection()) {
                    StartWordRepository startWords = new StartWordRepository(conn);

                    // Pick a fresh random start word for every sentence, so the same word
                    // can come up more than once, like the spec's random sentences
                    for (int iSentence = 0; iSentence < total; iSentence++) {
                        updateMessage("Generating sentence " + (iSentence + 1) + " of " + total + "…");
                        String startWord = startWords.randomStartWord().orElseThrow(() ->
                                new IllegalStateException("No word has started a sentence yet. Import a file first."));
                        sentences.add(createGenerator(algorithm, startWord, bigrams).generateSentence());
                    }
                }
                return sentences;
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

        // Show the task's progress messages while it runs
        statusLabel.textProperty().bind(task.messageProperty());
        setBusy(true);
        start(task);
    }

    /**
     * Saves the sentences in the list as a generated batch, so their words, pairs,
     * and triples are counted like an imported file. Clears the list afterward so
     * the same batch isn't added twice by accident.
     */
    private void addToCorpus() {
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
     * Makes the generator for the algorithm named in the drop-down, starting from
     * the given word. Every generator extends AbstractGenerator, so the callers run
     * them all the same way. Trigram backoff gets no second word, so it starts from
     * bigrams and switches to trigrams once the sentence has two words.
     */
    private static AbstractGenerator createGenerator(String algorithm, String startWord,
                                                     TransitionRepository bigrams) {
        return switch (algorithm) {
            case MOST_FREQUENT -> new FrequentWordGenerator(MAX_WORDS, bigrams, startWord);
            case TRIGRAM -> new TrigramBackoffGenerator(MAX_WORDS, bigrams, new PostgresTrigramRepo(),
                    startWord, null);
            default -> new MarkovGenerator(MAX_WORDS, bigrams, startWord);
        };
    }

    /** Shows the bottom half's final message and turns the buttons back on. */
    private void finishTask(String message) {
        statusLabel.textProperty().unbind();
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