/*
 * Runs the Auto-complete panel in autocomplete-view.fxml. The user types in a text box, and
 * once a word is finished by a space, comma, or period (Autocomplete.completedWord decides),
 * up to 5 likely next words appear as buttons, ranked by Autocomplete.getSuggestions. Nothing
 * is suggested while a word is still being typed.
 *
 * Every finished word counts as the user's choice, whether they typed it or clicked a
 * suggestion (a click just types the word and a space). A word that isn't in the database is
 * added with addWord, as the assignment asks. A known word is counted as chosen after the
 * previous word with Autocomplete.recordChoice, which raises that pair's user_selected count so
 * it ranks higher next time. The first word after a period has no previous word, so only the
 * word itself is added if it's new.
 *
 * Database work runs on one background thread, in the order the words were typed, so the
 * window never freezes and a new word is saved before its suggestions are looked up. Needs
 * DB_URL, DB_USER, and DB_PASSWORD in .env (see Database). Without them the panel still opens,
 * and the status text shows which key is missing.
 *
 * AI-assisted: Claude
 *
 * Prompt:
 * can you make the autocomplete tab?
 *
 * Follow-up prompt:
 * lets build the Auto-complete tab
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.autocomplete.Autocomplete;
import com.sentencebuilder.database.PostgresTransitionRepo;
import com.sentencebuilder.model.WordTransition;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Alert;
import javafx.scene.layout.FlowPane;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class AutocompleteController {

    private static final int SUGGESTION_LIMIT = 5;

    @FXML private TextArea inputArea;
    @FXML private FlowPane suggestionPane;
    @FXML private Label suggestionHint;   // Shown instead of buttons when nothing is suggested
    @FXML private Label statusLabel;

    private final PostgresTransitionRepo repo = new PostgresTransitionRepo();
    private final Autocomplete autocomplete = new Autocomplete(repo);

    // One background thread, so saves and lookups run in the order the words were typed
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);   // Doesn't keep the app open after the window closes
        return thread;
    });

    // The text up to the end of the last saved word. Extra spaces, or backspacing into text
    // that's already saved, don't count a word again
    private String savedText = null;

    private int latestLookup = 0;   // Only the newest suggestion lookup may update the buttons

    /** Starts watching what the user types. */
    @FXML
    private void initialize() {
        inputArea.textProperty().addListener((property, oldText, newText) -> onTextChanged(newText));
    }

    /** Empties the box, so the next word typed starts a new sentence. */
    @FXML
    private void onClear() {
        inputArea.clear();
        savedText = null;
        statusLabel.setText("");
        inputArea.requestFocus();
    }

    /**
     * Runs on every keystroke. When the text ends with a newly finished word, saves it with the
     * word before it, then shows suggestions for it. While a word is being typed, hides them.
     * A word only counts when the text has moved past what was already saved, so deleting back
     * to an earlier word doesn't count that word a second time.
     */
    private void onTextChanged(String text) {
        Optional<String> finished = Autocomplete.completedWord(text);
        if (finished.isEmpty()) {
            latestLookup++;   // Drops any lookup still running for an older word
            suggestionPane.getChildren().setAll(suggestionHint);
            return;
        }

        String word = finished.get();
        int wordEnd = lastWordEnd(text);
        String textSoFar = text.substring(0, wordEnd);
        if (savedText == null || !savedText.startsWith(textSoFar)) {
            savedText = textSoFar;
            saveWord(previousWord(text, wordEnd), word);
        }
        lookUpSuggestions(word);
    }

    /**
     * Saves a finished word in the background. A new word is added to the database. When there
     * is a previous word in the same sentence, the pair is counted as chosen.
     */
    private void saveWord(String previous, String word) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                boolean added = false;

                if (!repo.wordExists(word)) {
                    // Ask permission before adding new word
                    if(!confirmAddWord(word)){
                        return "\"" + word + "\" was not added to the database.";
                    }

                    repo.addWord(word);
                    added = true;
                }

                boolean recorded = false;

                if (previous != null && repo.wordExists(previous)) {
                    autocomplete.recordChoice(previous, word);
                    recorded = true;
                }

                if (added) {
                    return "Added the new word \"" + word + "\" to the database.";
                }
                return recorded ? "Counted \"" + word + "\" after \"" + previous + "\"." : "";
            }
        };

        task.setOnSucceeded(event -> {
            if (!task.getValue().isEmpty()) {
                statusLabel.setText(task.getValue());
            }
        });
        task.setOnFailed(event -> statusLabel.setText("Couldn't save \"" + word + "\": "
                + task.getException().getMessage()));
        worker.execute(task);
    }

    /**
     * Looks up the top next words for a finished word in the background and shows them as
     * buttons. A lookup that finishes after a newer one has started is ignored.
     */
    private void lookUpSuggestions(String word) {
        int lookup = ++latestLookup;

        Task<List<WordTransition>> task = new Task<>() {
            @Override
            protected List<WordTransition> call() {
                return autocomplete.getSuggestions(word, SUGGESTION_LIMIT);
            }
        };
        task.setOnSucceeded(event -> {
            if (lookup == latestLookup) {
                showSuggestions(word, task.getValue());
            }
        });
        task.setOnFailed(event -> {
            if (lookup == latestLookup) {
                suggestionPane.getChildren().setAll(suggestionHint);
                statusLabel.setText("Couldn't get suggestions: " + task.getException().getMessage());
            }
        });
        worker.execute(task);
    }

    /**
     * Shows one button per suggestion. Hovering shows how often the pair was seen in the text
     * and how often users picked it.
     */
    private void showSuggestions(String word, List<WordTransition> suggestions) {
        if (suggestions.isEmpty()) {
            suggestionPane.getChildren().setAll(new Label("No suggestions after \"" + word
                    + "\" yet. Type your own next word."));
            return;
        }

        List<Button> buttons = new ArrayList<>();
        for (WordTransition suggestion : suggestions) {
            Button button = new Button(suggestion.getNextWord());
            button.setTooltip(new Tooltip("Seen " + suggestion.getOccurrences() + " times after \""
                    + word + "\", picked " + suggestion.getUserSelected() + " times"));
            button.setOnAction(event -> typeWord(suggestion.getNextWord()));
            buttons.add(button);
        }
        suggestionPane.getChildren().setAll(buttons);
    }

    /**
     * Types a clicked suggestion into the box followed by a space, as if the user typed it, so
     * it's saved and counted the same way.
     */
    private void typeWord(String word) {
        String text = inputArea.getText();
        boolean needsSpace = !text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1));
        inputArea.appendText((needsSpace ? " " : "") + word + " ");
        inputArea.positionCaret(inputArea.getLength());
        inputArea.requestFocus();
    }

    /**
     * Finds the word before the one ending at wordEnd, or null if there isn't one or a period
     * ends the sentence between them ("the end. Then" has no pair "end then").
     */
    private static String previousWord(String text, int wordEnd) {
        int wordStart = wordEnd;
        while (wordStart > 0 && !isWordBreak(text.charAt(wordStart - 1))) {
            wordStart--;
        }

        String before = text.substring(0, wordStart);
        String breaksBefore = before.substring(lastWordEnd(before));
        if (breaksBefore.contains(".")) {
            return null;
        }
        return Autocomplete.completedWord(before).orElse(null);
    }

    /** Returns where the last word ends, just before any spaces, commas, or periods after it. */
    private static int lastWordEnd(String text) {
        int end = text.length();
        while (end > 0 && isWordBreak(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /** The same word breaks Autocomplete.completedWord uses: whitespace, a comma, or a period. */
    private static boolean isWordBreak(char character) {
        return Character.isWhitespace(character) || character == ',' || character == '.';
    }

    /** An unknown word alerts a confirmation prompt to appear for user to select whether or not to invoke saveWord **/
    private boolean confirmAddWord(String word) throws Exception{
        //Hold user's decision
        FutureTask<Boolean> confirm = new FutureTask<>(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);

            alert.setTitle("Unknown Word");
            alert.setHeaderText("This word is not in the database.");
            alert.setContentText("Would you like to add \"" + word + "\" to the database?");

            alert.getButtonTypes().setAll(
                    ButtonType.YES,
                    ButtonType.NO
            );

            return alert.showAndWait().filter(response -> response == ButtonType.YES).isPresent();
        });

        //Display confirmation
        Platform.runLater(confirm);

        return confirm.get();
    }
}
