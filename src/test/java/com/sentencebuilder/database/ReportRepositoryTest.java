package com.sentencebuilder.database;

import com.sentencebuilder.database.ReportRepository.WordReportRow;
import com.sentencebuilder.database.ReportRepository.WordSort;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the shared database, so it needs .env and the Sherlock Holmes text imported. */
class ReportRepositoryTest
{

    private final ReportRepository repository = new ReportRepository();

    @Test
    void sortingByTotalPutsTheMostCommonWordFirst() throws SQLException {
        try (Connection conn = Database.getConnection()) {
            List<WordReportRow> rows = repository.wordReport(conn, WordSort.TOTAL, true, "", 0);

            assertFalse(rows.isEmpty());
            for (int ix = 1; ix < rows.size(); ix++) {
                assertTrue(rows.get(ix - 1).totalCount() >= rows.get(ix).totalCount());
            }
        }
    }

    @Test
    void filterKeepsOnlyWordsContainingTheText() throws SQLException {
        try (Connection conn = Database.getConnection()) {
            List<WordReportRow> rows = repository.wordReport(conn, WordSort.WORD, false, "HOLM", 0);

            assertFalse(rows.isEmpty());
            assertTrue(rows.stream().allMatch(row -> row.word().contains("holm")));
        }
    }

    @Test
    void wildcardCharactersInTheFilterAreTreatedAsPlainText() throws SQLException {
        try (Connection conn = Database.getConnection()) {
            assertTrue(repository.wordReport(conn, WordSort.WORD, false, "%", 0).isEmpty());
        }
    }

    @Test
    void savedSentencesShowUpAsDuplicatesAndRollBackCleanly() throws SQLException {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertTrue(repository.saveGenerated(conn, "zz test sentence", "the", "test"));
                assertTrue(repository.saveGenerated(conn, "zz test sentence", "the", "test"));

                assertTrue(repository.duplicateReport(conn).stream()
                        .anyMatch(row -> row.sentence().equals("zz test sentence")
                                && row.timesGenerated() == 2));
            } finally {
                conn.rollback();   // nothing is left in the shared database
            }
        }
    }
}