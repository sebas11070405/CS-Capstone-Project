/*
 * Checks that imports add to existing word counts and that the same text is
 * rejected even under another file name. Also checks that generated batches
 * are saved as 'generated', can repeat, keep their sentences apart, don't
 * block a file with the same text, and roll back completely on failure.
 *
 * They need a valid .env because they write to the shared Supabase database.
 * Each test runs inside a transaction that is rolled back, so nothing is left
 * behind. The rollback test commits nothing because its import fails. Run
 * them with ./gradlew test.
 *
 * AI-assisted: Claude Code (generated-batch tests)
 *
 * Prompt: see src/main/java/com/sentencebuilder/importer/Importer.java
 */

package com.sentencebuilder.importer;

import com.sentencebuilder.database.Database;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterTest {

    // Made up so they can't collide with real words already in the shared database
    private static final String WORD = "zzqtest";
    private static final String OTHER = "zzqother";

    // Two generated sentences with no final punctuation. Counted together, they would add
    // OTHER -> OTHER, which a separate count never does.
    private static final List<String> BATCH = List.of("Zzqtest zzqother", "zzqother zzqtest");

    /** Database work a test passes in as a lambda. Unlike Runnable, it may throw. */
    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

    /** Runs the work in a transaction and always rolls it back, even if the work fails. */
    private static void inRolledBackTransaction(DatabaseWork work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    private static CorpusStats stats(String text) throws Exception {
        return CorpusStats.from(new StringReader(text));
    }

    @Test
    void differentTextsAddToExistingCounts() throws Exception {
        inRolledBackTransaction(conn -> {
            Importer.write(conn, "first.txt", stats("Zzqtest one. Zzqtest two zzqtest."));
            assertCounts(conn, 3, 2, 1);

            Importer.write(conn, "second.txt", stats("Zzqtest three."));
            assertCounts(conn, 4, 3, 1);
        });
    }

    @Test
    void sameTextIsRejectedEvenUnderAnotherName() throws Exception {
        inRolledBackTransaction(conn -> {
            Importer.write(conn, "original.txt", stats("Zzqtest one. Zzqtest two zzqtest."));

            DuplicateImportException thrown = assertThrows(DuplicateImportException.class, () ->
                    Importer.write(conn, "renamed.txt", stats("ZZQTEST one.\r\nZzqtest two zzqtest.")));
            assertTrue(thrown.getMessage().contains("original.txt"), thrown.getMessage());
            assertCounts(conn, 3, 2, 1);
        });
    }

    @Test
    void generatedBatchesCanRepeat() throws Exception {
        inRolledBackTransaction(conn -> {
            CorpusStats batch = CorpusStats.fromSentences(BATCH);
            Importer.writeGenerated(conn, "zzqtest batch", batch);
            Importer.writeGenerated(conn, "zzqtest batch", batch);

            assertCounts(conn, 4, 2, 2);
            assertEquals(2, bigramCount(conn, WORD, OTHER));
            assertEquals(2, bigramCount(conn, OTHER, WORD));
            assertEquals(0, bigramCount(conn, OTHER, OTHER), "pair crossed two generated sentences");
            assertEquals(2, countRows(conn,
                    "SELECT count(*) FROM source_files WHERE content_hash = ? AND source_type = 'generated'",
                    batch.contentHash()));
        });
    }

    @Test
    void generatedBatchDoesNotBlockTheSameTextAsAFile() throws Exception {
        inRolledBackTransaction(conn -> {
            String text = "Zzqtest zzqother. Zzqother zzqtest.";
            Importer.writeGenerated(conn, "zzqtest batch", CorpusStats.fromSentences(BATCH));
            Importer.write(conn, "zzqtest.txt", stats(text));

            // The file is now in, so the same text as a file is still a duplicate
            assertThrows(DuplicateImportException.class, () ->
                    Importer.write(conn, "zzqtest-copy.txt", stats(text)));
            assertCounts(conn, 4, 2, 2);
        });
    }

    @Test
    void failedGeneratedImportRollsBackEverything() throws Exception {
        // The source_files row is written first, then a word too long for words.word makes saving the
        // words fail
        CorpusStats stats = new CorpusStats();
        stats.addSentence(List.of(WORD, "zzq".repeat(40)));
        String batchName = "zzqtest failed batch";

        assertThrows(SQLException.class, () -> Database.inTransaction(conn -> {
            Importer.writeGenerated(conn, batchName, stats);
            return null;
        }));

        try (Connection conn = Database.getConnection()) {
            assertEquals(0, countRows(conn, "SELECT count(*) FROM source_files WHERE file_name = ?", batchName),
                    "source_files row was not rolled back");
        }
    }

    @Test
    void generatedBatchWithNoWordsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Importer().importGenerated(List.of("", "42")));
    }

    /** Returns how often first was followed by second in the bigrams table, or 0 if never. */
    private static int bigramCount(Connection conn, String first, String second) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT b.occurrences FROM bigrams b
                JOIN words w1 ON w1.word_id = b.word_id
                JOIN words w2 ON w2.word_id = b.next_word_id
                WHERE w1.word = ? AND w2.word = ?
                """)) {
            statement.setString(1, first);
            statement.setString(2, second);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Runs a count(*) query that takes one string parameter and returns the count. */
    private static int countRows(Connection conn, String sql, String value) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Asserts the total, start, and end counts stored for WORD in the words table. */
    private static void assertCounts(Connection conn, int total, int start, int end) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT total_count, start_count, end_count FROM words WHERE word = ?")) {
            statement.setString(1, WORD);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), WORD + " not found");
                assertEquals(total, rs.getInt(1));
                assertEquals(start, rs.getInt(2));
                assertEquals(end, rs.getInt(3));
            }
        }
    }
}
