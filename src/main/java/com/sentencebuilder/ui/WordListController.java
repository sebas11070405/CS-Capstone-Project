/*
 * Runs the Word List tab in word-list-view.fxml. Shows every stored word with its counts, one page
 * at a time. Clicking a column header sorts the whole list by that column, the filter row narrows
 * it, and Prev and Next move between pages. The sorting, filtering, and paging all happen in the
 * database (WordListRepository), so a corpus with thousands of words stays fast. Database work runs
 * off the JavaFX thread so the window doesn't freeze.
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.WordDetailRepository.WordInfo;
import com.sentencebuilder.database.WordListRepository;
import com.sentencebuilder.database.WordListRepository.SortColumn;
import com.sentencebuilder.database.WordListRepository.WordFilter;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.SortType;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

public class WordListController {

    private static final int PAGE_SIZE = 50;

    @FXML private TextField prefixField;
    @FXML private TextField minTotalField;
    @FXML private CheckBox startersBox;
    @FXML private CheckBox endersBox;
    @FXML private Button applyButton;
    @FXML private Button clearButton;
    @FXML private TableView<WordInfo> wordTable;
    @FXML private TableColumn<WordInfo, String> wordColumn;
    @FXML private TableColumn<WordInfo, Integer> totalColumn;
    @FXML private TableColumn<WordInfo, Integer> startColumn;
    @FXML private TableColumn<WordInfo, Integer> endColumn;
    @FXML private Button prevButton;
    @FXML private Button nextButton;
    @FXML private Button refreshButton;
    @FXML private Label pageLabel;
    @FXML private Label statusLabel;

    private Map<TableColumn<WordInfo, ?>, SortColumn> sortColumns;   // Which database column each header sorts
    private WordFilter filter = WordFilter.none();   // The filter last applied, not what's half typed
    private int pageIndex;       // Page on screen, counting from 0
    private int pageCount = 1;   // Pages for the current filter, at least 1
    private int latestLoad;      // Id of the newest query, so a slow older one can't overwrite it
    private boolean loading;     // True while the newest query is running
    private boolean reloadQueued;   // Lets one click that fires two sort events reload only once

    /** One page of results, plus the total so the screen can say how many pages there are. */
    private record Page(List<WordInfo> words, long total, int pageIndex) {}

    /** Hooks up the columns, hands sorting to the database, and loads the first page. */
    @FXML
    private void initialize() {
        wordColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().word()));
        totalColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().totalCount()));
        startColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().startCount()));
        endColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().endCount()));
        sortColumns = Map.of(wordColumn, SortColumn.WORD, totalColumn, SortColumn.TOTAL,
                startColumn, SortColumn.START, endColumn, SortColumn.END);

        // The table must not sort its 50 rows itself. The database sorts everything and the table
        // just shows the arrow, so this policy does nothing.
        wordTable.setSortPolicy(table -> true);
        wordTable.getSortOrder().add(wordColumn);   // Alphabetical by default
        wordTable.getSortOrder().addListener((ListChangeListener<TableColumn<WordInfo, ?>>) change -> sortChanged());
        for (TableColumn<WordInfo, ?> column : sortColumns.keySet()) {
            column.sortTypeProperty().addListener((observable, oldType, newType) -> sortChanged());
        }

        updateControls();
        loadPage(0);
    }

    /** Applies the filter row, starting back at the first page. */
    @FXML
    protected void onApply() {
        int minTotal = 0;
        String text = minTotalField.getText().trim();
        if (!text.isEmpty()) {
            try {
                minTotal = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                minTotal = -1;   // Rejected below with the same message as a negative number
            }
            if (minTotal < 0) {
                statusLabel.setText("Min count needs to be a whole number, 0 or more");
                return;
            }
        }
        filter = new WordFilter(prefixField.getText(), minTotal, startersBox.isSelected(), endersBox.isSelected());
        loadPage(0);
    }

    /** Empties the filter row and shows every word again. */
    @FXML
    protected void onClear() {
        prefixField.clear();
        minTotalField.clear();
        startersBox.setSelected(false);
        endersBox.setSelected(false);
        filter = WordFilter.none();
        loadPage(0);
    }

    /** Goes to the previous page. */
    @FXML
    protected void onPrev() {
        loadPage(pageIndex - 1);
    }

    /** Goes to the next page. */
    @FXML
    protected void onNext() {
        loadPage(pageIndex + 1);
    }

    /** Reloads the current page, for after words were imported or edited in another tab. */
    @FXML
    protected void onRefresh() {
        loadPage(pageIndex);
    }

    /**
     * Reloads from the first page after a header click. One click can change both the sort column
     * and its direction, so the reload waits a moment and runs once.
     */
    private void sortChanged() {
        if (reloadQueued) {
            return;
        }
        reloadQueued = true;
        Platform.runLater(() -> {
            reloadQueued = false;
            loadPage(0);
        });
    }

    /** Runs the count and the page query in the background, then fills the table. */
    private void loadPage(int page) {
        // Third click on a header clears the sort, which falls back to alphabetical
        TableColumn<WordInfo, ?> sorted = wordTable.getSortOrder().isEmpty() ? wordColumn : wordTable.getSortOrder().get(0);
        SortColumn sortBy = sortColumns.get(sorted);
        boolean descending = !wordTable.getSortOrder().isEmpty() && sorted.getSortType() == SortType.DESCENDING;
        WordFilter used = filter;
        int load = ++latestLoad;

        Task<Page> task = new Task<>() {
            @Override
            protected Page call() throws Exception {
                try (Connection conn = Database.getConnection()) {
                    WordListRepository repository = new WordListRepository(conn);
                    long total = repository.countWords(used);
                    // Words can disappear between loads, so never ask for a page past the end
                    int lastPage = (int) Math.max(0, (total - 1) / PAGE_SIZE);
                    int safePage = Math.max(0, Math.min(page, lastPage));
                    return new Page(repository.findWords(used, sortBy, descending, PAGE_SIZE, safePage * PAGE_SIZE),
                            total, safePage);
                }
            }
        };
        task.setOnSucceeded(event -> {
            if (load != latestLoad) {
                return;   // A newer query is on its way
            }
            Page result = task.getValue();
            pageIndex = result.pageIndex();
            pageCount = (int) Math.max(1, (result.total() + PAGE_SIZE - 1) / PAGE_SIZE);
            // Swap rows in place. Giving the table a new list makes it forget which column is sorted
            wordTable.getItems().setAll(result.words());
            statusLabel.setText(String.format("%,d words", result.total()));
            loading = false;
            updateControls();
        });
        task.setOnFailed(event -> {
            if (load != latestLoad) {
                return;
            }
            statusLabel.setText("Couldn't load words: " + task.getException().getMessage());
            loading = false;
            updateControls();
        });

        statusLabel.setText("Loading…");
        loading = true;
        updateControls();
        Thread thread = new Thread(task);
        thread.setDaemon(true);   // Doesn't keep the app open if the window closes mid-query
        thread.start();
    }

    /** Shows "Page X of Y" and turns Prev and Next off at the ends or while a query runs. */
    private void updateControls() {
        pageLabel.setText("Page " + (pageIndex + 1) + " of " + pageCount);
        prevButton.setDisable(loading || pageIndex <= 0);
        nextButton.setDisable(loading || pageIndex >= pageCount - 1);
    }
}
