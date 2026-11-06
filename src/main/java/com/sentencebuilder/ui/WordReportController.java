/*
 * Handles the controls in word-report-view.fxml. Refresh runs
 * ReportRepository.wordReport on a background thread with the chosen text
 * filter, minimum count, sort column and direction, then shows the rows in the
 * table. Sorting happens in the SQL, so the column headers aren't clickable.
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.ReportRepository;
import com.sentencebuilder.database.ReportRepository.WordReportRow;
import com.sentencebuilder.database.ReportRepository.WordSort;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

import java.sql.Connection;
import java.util.List;

public class WordReportController {
    @FXML private TextField filterField;
    @FXML private Spinner<Integer> minCountSpinner;
    @FXML private ComboBox<WordSort> sortChoice;
    @FXML private CheckBox descendingCheck;
    @FXML private Button refreshButton;
    @FXML private TableView<WordReportRow> table;
    @FXML private TableColumn<WordReportRow, String> wordColumn;
    @FXML private TableColumn<WordReportRow, Integer> totalColumn;
    @FXML private TableColumn<WordReportRow, Integer> startColumn;
    @FXML private TableColumn<WordReportRow, Integer> endColumn;
    @FXML private Label statusLabel;

    private final ReportRepository reports = new ReportRepository();

    /** Called by JavaFX after the FXML is loaded: sets up the table columns and the filter controls. */
    @FXML
    private void initialize() {
        wordColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().word()));
        totalColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().totalCount()));
        startColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().startCount()));
        endColumn.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(row.getValue().endCount()));

        minCountSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, Integer.MAX_VALUE, 0));

        sortChoice.setItems(FXCollections.observableArrayList(WordSort.values()));
        sortChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(WordSort sort) {
                return switch (sort) {
                    case WORD -> "Word";
                    case TOTAL -> "Total";
                    case START -> "Sentence starts";
                    case END -> "Sentence ends";
                };
            }

            @Override
            public WordSort fromString(String text) {
                return null; // the combo box isn't editable
            }
        });
        sortChoice.setValue(WordSort.TOTAL);

        statusLabel.setText("Press Refresh to load the report.");
    }

    /**
     * Runs the report with the current controls. The button stays disabled until
     * the query finishes so two queries can't race to fill the table.
     */
    @FXML
    protected void onRefresh() {
        // Read the controls here on the JavaFX thread; the task must not touch them
        String text = filterField.getText();
        WordSort sort = sortChoice.getValue();
        boolean descending = descendingCheck.isSelected();
        int minCount = readMinCount();

        Task<List<WordReportRow>> task = new Task<>() {
            @Override
            protected List<WordReportRow> call() throws Exception {
                try (Connection conn = openConnection()) {
                    return reports.wordReport(conn, sort, descending, text, minCount);
                }
            }
        };
        task.setOnSucceeded(event -> {
            List<WordReportRow> rows = task.getValue();
            table.setItems(FXCollections.observableArrayList(rows));
            statusLabel.setText(String.format("%,d words", rows.size()));
            refreshButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            statusLabel.setText("Report failed: " + task.getException().getMessage());
            refreshButton.setDisable(false);
        });

        refreshButton.setDisable(true);
        statusLabel.setText("Loading…");
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /** Returns the spinner's number, committing anything typed but not yet confirmed; bad text counts as 0. */
    private int readMinCount() {
        try {
            // Typed text only becomes the spinner's value on Enter or focus loss, so read the editor directly
            int value = Integer.parseInt(minCountSpinner.getEditor().getText().trim());
            return Math.max(0, value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Opens a database connection the same way the importer does. */
    private Connection openConnection() throws Exception {
        // TODO: replace with the project's real connection call (whatever Importer uses)
        return Database.getConnection();
    }
}