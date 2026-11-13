/*
 * Runs the word lookup panel in words-view.fxml. Type a word to see its counts and the
 * words that come after and before it, then fix its counts if they look wrong. Database
 * work runs off the JavaFX thread so the window doesn't freeze.
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.WordDetailRepository;
import com.sentencebuilder.database.WordDetailRepository.Neighbor;
import com.sentencebuilder.database.WordDetailRepository.WordInfo;
import com.sentencebuilder.database.WordEditRepository;
import com.sentencebuilder.tokenizer.Tokenizer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

public class WordsController {

    private static final int NEIGHBOR_LIMIT = 20;   // Rows per table, common words have thousands

    @FXML private TextField wordField;
    @FXML private Button lookupButton;
    @FXML private Label headingLabel;
    @FXML private Label countsLabel;
    @FXML private Label followersTitle;
    @FXML private Label predecessorsTitle;
    @FXML private TableView<Neighbor> followersTable;
    @FXML private TableColumn<Neighbor, String> followerWordColumn;
    @FXML private TableColumn<Neighbor, Integer> followerCountColumn;
    @FXML private TableColumn<Neighbor, Integer> followerPickColumn;
    @FXML private TableView<Neighbor> predecessorsTable;
    @FXML private TableColumn<Neighbor, String> predecessorWordColumn;
    @FXML private TableColumn<Neighbor, Integer> predecessorCountColumn;
    @FXML private TableColumn<Neighbor, Integer> predecessorPickColumn;
    @FXML private TextField totalField;
    @FXML private TextField startField;
    @FXML private TextField endField;
    @FXML private Button saveButton;
    @FXML private Label statusLabel;

    private String currentWord;   // The word on screen, null until a lookup finds one

    /** Everything one lookup finds, so the three queries come back together. */
    private record Lookup(Optional<WordInfo> info, List<Neighbor> followers, List<Neighbor> predecessors) {}

    /** Hooks each table column up to a Neighbor field. Records don't work with PropertyValueFactory. */
    @FXML
    private void initialize() {
        followerWordColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().word()));
        followerCountColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().occurrences()));
        followerPickColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().userSelected()));
        predecessorWordColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().word()));
        predecessorCountColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().occurrences()));
        predecessorPickColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().userSelected()));
    }

    /** Normalizes the typed word like imported text, then looks it up in the background. */
    @FXML
    protected void onLookup() {
        String word = Tokenizer.normalizeWord(wordField.getText());
        if (word == null) {
            clearResults();
            setBusy(false);
            statusLabel.setText("That doesn't look like a single word");
            return;
        }
        lookUp(word, "");
    }

    /** Reads the three number fields and saves them for the current word. */
    @FXML
    protected void onSave() {
        int total;
        int start;
        int end;
        try {
            total = Integer.parseInt(totalField.getText().trim());
            start = Integer.parseInt(startField.getText().trim());
            end = Integer.parseInt(endField.getText().trim());
        } catch (NumberFormatException e) {
            statusLabel.setText("Counts need to be whole numbers");
            return;
        }

        String word = currentWord;
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                // inTransaction commits, and rolls back if anything throws
                return Database.inTransaction(conn -> new WordEditRepository(conn).updateCounts(word, total, start, end));
            }
        };
        task.setOnSucceeded(event -> {
            if (task.getValue()) {
                lookUp(word, "Saved");
            } else {
                statusLabel.setText(word + " isn't in the database anymore");
                setBusy(false);
            }
        });
        // Bad counts land here too, with the repository's readable message
        task.setOnFailed(event -> {
            statusLabel.setText("Couldn't save: " + task.getException().getMessage());
            setBusy(false);
        });
        setBusy(true);
        new Thread(task).start();
    }

    /** Runs the three queries for a word off the UI thread, then fills the screen. */
    private void lookUp(String word, String message) {
        Task<Lookup> task = new Task<>() {
            @Override
            protected Lookup call() throws Exception {
                try (Connection conn = Database.getConnection()) {
                    WordDetailRepository repository = new WordDetailRepository(conn);
                    return new Lookup(repository.findWord(word),
                            repository.findFollowers(word, NEIGHBOR_LIMIT),
                            repository.findPredecessors(word, NEIGHBOR_LIMIT));
                }
            }
        };
        task.setOnSucceeded(event -> {
            show(word, task.getValue());
            if (!message.isEmpty()) {
                statusLabel.setText(message);
            }
            setBusy(false);
        });
        task.setOnFailed(event -> {
            clearResults();
            statusLabel.setText("Lookup failed: " + task.getException().getMessage());
            setBusy(false);
        });
        statusLabel.setText("Looking up " + word + "…");
        setBusy(true);
        new Thread(task).start();
    }

    /** Fills the labels, tables, and edit fields, or says the word isn't stored. */
    private void show(String word, Lookup lookup) {
        if (lookup.info().isEmpty()) {
            clearResults();
            statusLabel.setText(word + " isn't in the database yet");
            return;
        }
        WordInfo info = lookup.info().get();
        currentWord = info.word();
        headingLabel.setText(info.word());
        countsLabel.setText(String.format("Seen %,d times, starts a sentence %,d times, ends one %,d times",
                info.totalCount(), info.startCount(), info.endCount()));
        followersTitle.setText("Comes after \"" + info.word() + "\"");
        predecessorsTitle.setText("Comes before \"" + info.word() + "\"");
        followersTable.setItems(FXCollections.observableArrayList(lookup.followers()));
        predecessorsTable.setItems(FXCollections.observableArrayList(lookup.predecessors()));
        totalField.setText(String.valueOf(info.totalCount()));
        startField.setText(String.valueOf(info.startCount()));
        endField.setText(String.valueOf(info.endCount()));
        statusLabel.setText("");
    }

    /** Empties the results and locks the edit form, since there's no word to edit. */
    private void clearResults() {
        currentWord = null;
        headingLabel.setText("");
        countsLabel.setText("");
        followersTitle.setText("Comes after");
        predecessorsTitle.setText("Comes before");
        followersTable.getItems().clear();
        predecessorsTable.getItems().clear();
        totalField.clear();
        startField.clear();
        endField.clear();
    }

    /** Locks the buttons while a query runs, and keeps the edit form off when there's no word. */
    private void setBusy(boolean busy) {
        lookupButton.setDisable(busy);
        saveButton.setDisable(busy || currentWord == null);
        totalField.setDisable(busy || currentWord == null);
        startField.setDisable(busy || currentWord == null);
        endField.setDisable(busy || currentWord == null);
    }
}
