package com.sentencebuilder.repository;

import com.sentencebuilder.repository.ReportRows.DuplicateRow;
import com.sentencebuilder.repository.ReportRows.WordReportRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the shared database, so it needs .env and the Sherlock Holmes text imported. */
class JdbcReportRepositoryTest {

    private final ReportRepository repository = new JdbcReportRepository();

    @Test
    void sortingByTotalPutsTheMostCommonWordFirst() {
        List<WordReportRow> rows = repository.wordReport(WordSort.TOTAL, true, "", 0);

        assertFalse(rows.isEmpty());
        for (int ix = 1; ix < rows.size(); ix++) {
            assertTrue(rows.get(ix - 1).totalCount() >= rows.get(ix).totalCount());
        }
    }

    @Test
    void filterKeepsOnlyWordsContainingTheText() {
        List<WordReportRow> rows = repository.wordReport(WordSort.WORD, false, "HOLM", 0);

        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(row -> row.word().contains("holm")));
    }

    @Test
    void minimumCountRemovesRareWords() {
        List<WordReportRow> rows = repository.wordReport(WordSort.WORD, false, "", 50);

        assertTrue(rows.stream().allMatch(row -> row.totalCount() >= 50));
    }

    @Test
    void wildcardCharactersInTheFilterAreTreatedAsPlainText() {
        assertTrue(repository.wordReport(WordSort.WORD, false, "%", 0).isEmpty());
    }

    @Test
    void everyDuplicateWasGeneratedAtLeastTwice() {
        // The table may be empty, so this only checks the rows that do come back
        for (DuplicateRow row : repository.duplicateReport()) {
            assertTrue(row.timesGenerated() >= 2);
        }
    }
}