/*
 * Checks that imports add to existing word counts, that the same text is
 * rejected even under another file name, and that progress is reported in
 * order with completion only after the commit. Also checks that generated
 * batches are saved as 'generated', can repeat, keep their sentences apart,
 * don't block a file with the same text, and roll back completely on failure.
 *
 * They need a valid .env because they write to the shared Supabase database.
 * Each test runs inside a transaction that is rolled back, so nothing is left
 * behind. The tests that go through Database.inTransaction commit nothing,
 * because their imports fail before the commit. Run them with ./gradlew test.
 *
 * AI-assisted: Claude Code (progress and generated-batch tests)
 *
 * Prompt: see src/main/java/com/sentencebuilder/importer/Importer.java
 */

package com.sentencebuilder.importer;

import com.sentencebuilder.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterTest {

    // Made up so they can't collide with real words already in the shared database
    private static final String WORD = "zzqtest";
    private static final String OTHER = "zzqother";

    // Two generated sentences with no final punctuation. Counted together, they would add
    // OTHER -> OTHER, which a separate count never does.
    private static final List<String> BATCH = List.of("Zzqtest zzqother", "zzqother zzqtest");

    // 3 distinct words, 2 distinct pairs (zzqalpha zzqbeta appears twice), and 1 triple
    private static final String PROGRESS_TEXT = "Zzqprogress zzqalpha zzqbeta. Zzqalpha zzqbeta.";

    // Like "Hello." and "Hello world.": no triples, and the first has no pairs either
    private static final String ONE_WORD_TEXT = "Zzqhello.";
    private static final String TWO_WORD_TEXT = "Zzqhello zzqworld.";

    /** One call a test listener received. */
    private record Update(double fraction, String message) {}

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
    void writeReportsEachTableInOrderWithoutFinishing() throws Exception {
        // Weighted by rows: 3 words, 2 pairs, and 1 triple, plus 1 unit for the commit, out of 7
        assertEquals(List.of(
                new Update(0, "Saving progress.txt…"),
                new Update(0, "Saving progress.txt: words…"),
                new Update(3.0 / 7, "Saving progress.txt: word pairs…"),
                new Update(5.0 / 7, "Saving progress.txt: word triples…")), writeUpdates(PROGRESS_TEXT));
    }

    @Test
    void writeStaysBelowOneForOneWord() throws Exception {
        // 1 word and the commit, out of 2. Without the commit unit, pairs would start at 1.
        assertEquals(List.of(
                new Update(0, "Saving progress.txt…"),
                new Update(0, "Saving progress.txt: words…"),
                new Update(0.5, "Saving progress.txt: word pairs…"),
                new Update(0.5, "Saving progress.txt: word triples…")), writeUpdates(ONE_WORD_TEXT));
    }

    @Test
    void writeStaysBelowOneWithoutTriples() throws Exception {
        // 2 words, 1 pair, and the commit, out of 4. Without the commit unit, triples would start at 1.
        assertEquals(List.of(
                new Update(0, "Saving progress.txt…"),
                new Update(0, "Saving progress.txt: words…"),
                new Update(0.5, "Saving progress.txt: word pairs…"),
                new Update(0.75, "Saving progress.txt: word triples…")), writeUpdates(TWO_WORD_TEXT));
    }

    /** Writes the text as progress.txt in a rolled-back transaction and returns every progress update. */
    private static List<Update> writeUpdates(String text) throws Exception {
        List<Update> updates = new ArrayList<>();
        inRolledBackTransaction(conn ->
                Importer.write(conn, "progress.txt", stats(text),
                        (fraction, message) -> updates.add(new Update(fraction, message))));
        return updates;
    }

    @Test
    void duplicateStopsProgressBeforeSavingCounts() throws Exception {
        inRolledBackTransaction(conn -> {
            Importer.write(conn, "original.txt", stats(PROGRESS_TEXT));

            List<Update> updates = new ArrayList<>();
            assertThrows(DuplicateImportException.class, () ->
                    Importer.write(conn, "renamed.txt", stats(PROGRESS_TEXT),
                            (fraction, message) -> updates.add(new Update(fraction, message))));
            assertEquals(List.of(new Update(0, "Saving renamed.txt…")), updates);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {PROGRESS_TEXT, ONE_WORD_TEXT, TWO_WORD_TEXT})
    void importFileNeverReportsCompletionWhenCommitIsSkipped(String text, @TempDir Path dir) throws Exception {
        Path file = dir.resolve("progress.txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);

        // Failing on the last update before the commit makes inTransaction roll back instead of
        // committing, the same as a real failure at that point. Nothing reaches the shared database.
        List<Update> updates = new ArrayList<>();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                new Importer().importFile(file, (fraction, message) -> {
                    updates.add(new Update(fraction, message));
                    if (message.endsWith("committing…")) {
                        throw new IllegalStateException("stop before commit");
                    }
                }));
        assertEquals("stop before commit", thrown.getMessage());

        // It got as far as the commit, read the file first, and never claimed to be finished
        assertEquals(new Update(-1, "Reading progress.txt…"), updates.getFirst());
        assertEquals(new Update(-1, "Saving progress.txt: committing…"), updates.getLast());
        assertFalse(updates.stream().anyMatch(update -> update.fraction() >= 1), updates.toString());

        // The rollback removed the source_files row
        try (Connection conn = Database.getConnection();
             PreparedStatement statement = conn.prepareStatement(
                     "SELECT count(*) FROM source_files WHERE content_hash = ?")) {
            statement.setString(1, stats(text).contentHash());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "source_files row was committed");
            }
        }
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
