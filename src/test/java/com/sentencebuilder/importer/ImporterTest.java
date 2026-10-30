/*
 * Checks that imports add to existing word, pair, and triple counts, that the
 * same file text is rejected even under another file name, that generated
 * batches can repeat, that a failed import rolls everything back, and that
 * backfillPairs adds only pairs, refuses to run twice, and refuses a list that
 * leaves out an imported file.
 *
 * They need a valid .env because they write to the shared Supabase database.
 * Each test runs inside a transaction that is rolled back, so nothing is left
 * behind. The rollback tests commit nothing because their imports fail. The
 * backfill tests need empty pair tables, so they run against temporary copies
 * of the tables (see inTemporaryTables) and never touch or lock shared data.
 * Run them with ./gradlew test.
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
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterTest {

    // Made up so they can't collide with real words already in the shared database
    private static final String WORD = "zzqtest";
    private static final String ALPHA = "zzqalpha";
    private static final String BETA = "zzqbeta";
    private static final String GAMMA = "zzqgamma";

    // Gives [alpha, beta] twice, [beta, gamma] once, and [alpha, beta, gamma] once
    private static final String PAIR_TEXT = "Zzqalpha zzqbeta zzqgamma. Zzqalpha zzqbeta.";

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

    private static void writeFile(Connection conn, String fileName, String text) throws Exception {
        Importer.write(conn, fileName, "file", stats(text), Importer.ProgressListener.NONE);
    }

    @Test
    void differentTextsAddToExistingCounts() throws Exception {
        inRolledBackTransaction(conn -> {
            writeFile(conn, "first.txt", "Zzqtest one. Zzqtest two zzqtest.");
            assertCounts(conn, 3, 2, 1);

            writeFile(conn, "second.txt", "Zzqtest three.");
            assertCounts(conn, 4, 3, 1);
        });
    }

    @Test
    void pairsAndTriplesAddToExistingCounts() throws Exception {
        inRolledBackTransaction(conn -> {
            writeFile(conn, "first.txt", PAIR_TEXT);
            assertEquals(2, bigramCount(conn, ALPHA, BETA));
            assertEquals(1, bigramCount(conn, BETA, GAMMA));
            assertEquals(1, trigramCount(conn, ALPHA, BETA, GAMMA));
            // The sentence boundary between "zzqgamma." and "Zzqalpha" isn't a pair
            assertEquals(0, bigramCount(conn, GAMMA, ALPHA));

            writeFile(conn, "second.txt", "Zzqalpha zzqbeta zzqgamma zzqalpha.");
            assertEquals(3, bigramCount(conn, ALPHA, BETA));
            assertEquals(2, bigramCount(conn, BETA, GAMMA));
            assertEquals(1, bigramCount(conn, GAMMA, ALPHA));
            assertEquals(2, trigramCount(conn, ALPHA, BETA, GAMMA));
        });
    }

    @Test
    void importsLeaveUserSelectedUnchanged() throws Exception {
        inRolledBackTransaction(conn -> {
            writeFile(conn, "first.txt", PAIR_TEXT);
            // Pretend auto-complete picked this pair five times
            try (PreparedStatement statement = conn.prepareStatement("""
                    UPDATE bigrams SET user_selected = 5
                    WHERE word_id = (SELECT word_id FROM words WHERE word = ?)
                      AND next_word_id = (SELECT word_id FROM words WHERE word = ?)
                    """)) {
                statement.setString(1, ALPHA);
                statement.setString(2, BETA);
                assertEquals(1, statement.executeUpdate());
            }

            writeFile(conn, "second.txt", "Zzqalpha zzqbeta.");
            assertEquals(3, bigramCount(conn, ALPHA, BETA));
            assertEquals(5, userSelected(conn, ALPHA, BETA));
        });
    }

    @Test
    void sameTextIsRejectedEvenUnderAnotherName() throws Exception {
        inRolledBackTransaction(conn -> {
            writeFile(conn, "original.txt", "Zzqtest one. Zzqtest two zzqtest.");

            DuplicateImportException thrown = assertThrows(DuplicateImportException.class, () ->
                    writeFile(conn, "renamed.txt", "ZZQTEST one.\r\nZzqtest two zzqtest."));
            assertTrue(thrown.getMessage().contains("original.txt"), thrown.getMessage());
            assertCounts(conn, 3, 2, 1);
        });
    }

    @Test
    void duplicateFileAddsNoPairs() throws Exception {
        inRolledBackTransaction(conn -> {
            writeFile(conn, "original.txt", PAIR_TEXT);
            assertThrows(DuplicateImportException.class, () -> writeFile(conn, "copy.txt", PAIR_TEXT));
            assertEquals(2, bigramCount(conn, ALPHA, BETA));
            assertEquals(1, trigramCount(conn, ALPHA, BETA, GAMMA));
        });
    }

    @Test
    void generatedBatchesCanRepeat() throws Exception {
        inRolledBackTransaction(conn -> {
            CorpusStats batch = CorpusStats.fromSentences(List.of("zzqalpha zzqbeta zzqgamma", "zzqalpha zzqbeta"));
            Importer.write(conn, "batch.txt", "generated", batch, Importer.ProgressListener.NONE);
            Importer.write(conn, "batch.txt", "generated", batch, Importer.ProgressListener.NONE);

            assertEquals(4, bigramCount(conn, ALPHA, BETA));
            assertEquals(2, trigramCount(conn, ALPHA, BETA, GAMMA));
            assertEquals(0, bigramCount(conn, GAMMA, ALPHA));
            try (PreparedStatement statement = conn.prepareStatement(
                    "SELECT count(*) FROM source_files WHERE content_hash = ? AND source_type = 'generated'")) {
                statement.setString(1, batch.contentHash());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    assertEquals(2, rs.getInt(1));
                }
            }
        });
    }

    @Test
    void generatedBatchDoesNotBlockTheSameTextAsAFile() throws Exception {
        inRolledBackTransaction(conn -> {
            Importer.write(conn, "batch.txt", "generated", CorpusStats.fromSentences(List.of(PAIR_TEXT)),
                    Importer.ProgressListener.NONE);
            writeFile(conn, "file.txt", PAIR_TEXT);
            assertEquals(4, bigramCount(conn, ALPHA, BETA));
        });
    }

    @Test
    void failedImportRollsBackEverything() throws Exception {
        // The source_files row is written first, then a word too long for words.word makes saving the
        // words fail
        CorpusStats stats = new CorpusStats();
        stats.addSentence(List.of(ALPHA, BETA, "zzz".repeat(40)));

        try (Connection conn = Database.getConnection()) {
            assertThrows(SQLException.class, () ->
                    Importer.save(conn, "broken.txt", "file", stats, Importer.ProgressListener.NONE));

            try (PreparedStatement statement = conn.prepareStatement(
                    "SELECT count(*) FROM source_files WHERE content_hash = ?")) {
                statement.setString(1, stats.contentHash());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1), "source_files row was not rolled back");
                }
            }
            assertEquals(0, bigramCount(conn, ALPHA, BETA));
            conn.rollback();
        }
    }

    @Test
    void failureAfterWordsAreSavedRollsBackEverything() throws Exception {
        // Fails in Java after the source_files row and the words are saved. Unlike a SQL error, this
        // doesn't abort the transaction, so only an explicit rollback keeps them out.
        CorpusStats stats = stats(PAIR_TEXT);
        Importer.ProgressListener failOnPairs = (fraction, message) -> {
            if (message.contains("word pairs")) {
                throw new IllegalStateException("Simulated failure");
            }
        };

        try (Connection conn = Database.getConnection()) {
            assertThrows(IllegalStateException.class, () ->
                    Importer.save(conn, "broken.txt", "file", stats, failOnPairs));
            conn.setAutoCommit(true);
            assertEquals(0, queryInt(conn, "SELECT count(*) FROM source_files WHERE content_hash = ?",
                    stats.contentHash()), "source_files row was committed");
            assertEquals(0, wordTotal(conn, ALPHA), "word counts were committed");
            assertEquals(0, bigramCount(conn, ALPHA, BETA), "word pairs were committed");
        }
    }

    /**
     * Runs the work in a rolled-back transaction where source_files, words, bigrams,
     * and trigrams are empty temporary copies of the real tables. Postgres looks up
     * a temporary table before a real one of the same name, so the Importer's SQL
     * uses the copies and never reads, changes, or locks shared data. The copies
     * belong to this connection only and are dropped by the rollback.
     */
    private static void inTemporaryTables(DatabaseWork work) throws Exception {
        inRolledBackTransaction(conn -> {
            // LIKE ... INCLUDING ALL copies columns, defaults, identity, checks, and indexes, which
            // includes the partial unique index the duplicate check uses. Foreign keys aren't copied.
            List<String> tables = List.of("source_files", "words", "bigrams", "trigrams");
            try (Statement statement = conn.createStatement()) {
                for (String table : tables) {
                    statement.execute("CREATE TEMPORARY TABLE " + table
                            + " (LIKE public." + table + " INCLUDING ALL)");
                }
            }

            // Stop before doing anything if a name still resolves to the shared table
            for (String table : tables) {
                assertEquals(1, queryInt(conn, """
                        SELECT count(*) FROM pg_class
                        WHERE oid = to_regclass(?) AND relnamespace = pg_my_temp_schema()
                        """, table), table + " is not the temporary copy");
            }
            work.run(conn);
        });
    }

    /** Imports the text the way the old importer did: its source row and words, but no pairs. */
    private static void writeOldImport(Connection conn, String fileName, String text) throws Exception {
        writeFile(conn, fileName, text);
        try (Statement statement = conn.createStatement()) {
            statement.execute("DELETE FROM trigrams");
            statement.execute("DELETE FROM bigrams");
        }
    }

    @Test
    void backfillAddsOnlyPairsAndRefusesToRunTwice() throws Exception {
        inTemporaryTables(conn -> {
            writeOldImport(conn, "old.txt", PAIR_TEXT);
            int alphaTotal = wordTotal(conn, ALPHA);

            Importer.backfillPairs(conn, List.of("old.txt"), List.of(stats(PAIR_TEXT)),
                    Importer.ProgressListener.NONE);
            assertEquals(2, bigramCount(conn, ALPHA, BETA));
            assertEquals(1, trigramCount(conn, ALPHA, BETA, GAMMA));
            assertEquals(alphaTotal, wordTotal(conn, ALPHA), "backfill changed word counts");

            assertThrows(IllegalStateException.class, () -> Importer.backfillPairs(conn, List.of("old.txt"),
                    List.of(stats(PAIR_TEXT)), Importer.ProgressListener.NONE));
            assertEquals(2, bigramCount(conn, ALPHA, BETA));
        });
    }

    @Test
    void backfillRefusesTextThatWasNeverImported() throws Exception {
        inTemporaryTables(conn ->
                assertThrows(IllegalStateException.class, () -> Importer.backfillPairs(conn, List.of("new.txt"),
                        List.of(stats(PAIR_TEXT)), Importer.ProgressListener.NONE)));
    }

    @Test
    void backfillRefusesAListMissingAnImportedFile() throws Exception {
        inTemporaryTables(conn -> {
            writeOldImport(conn, "first.txt", PAIR_TEXT);
            writeOldImport(conn, "second.txt", "Zzqgamma zzqalpha.");

            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                    Importer.backfillPairs(conn, List.of("first.txt"), List.of(stats(PAIR_TEXT)),
                            Importer.ProgressListener.NONE));
            assertTrue(thrown.getMessage().contains("second.txt"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains("first.txt"), thrown.getMessage());
            assertEquals(0, queryInt(conn, "SELECT count(*) FROM bigrams"), "pairs were written");
            assertEquals(0, queryInt(conn, "SELECT count(*) FROM trigrams"), "triples were written");

            // With both files listed, the backfill goes through
            Importer.backfillPairs(conn, List.of("first.txt", "second.txt"),
                    List.of(stats(PAIR_TEXT), stats("Zzqgamma zzqalpha.")), Importer.ProgressListener.NONE);
            assertEquals(2, bigramCount(conn, ALPHA, BETA));
            assertEquals(1, bigramCount(conn, GAMMA, ALPHA));
        });
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

    /** Returns the stored total_count of a word, or 0 if it isn't stored. */
    private static int wordTotal(Connection conn, String word) throws SQLException {
        return queryInt(conn, "SELECT total_count FROM words WHERE word = ?", word);
    }

    /** Returns the stored occurrences of [word, next], or 0 if the pair isn't stored. */
    private static int bigramCount(Connection conn, String word, String next) throws SQLException {
        return queryInt(conn, """
                SELECT b.occurrences FROM bigrams b
                JOIN words w1 ON w1.word_id = b.word_id
                JOIN words w2 ON w2.word_id = b.next_word_id
                WHERE w1.word = ? AND w2.word = ?
                """, word, next);
    }

    /** Returns the stored user_selected of [word, next], or 0 if the pair isn't stored. */
    private static int userSelected(Connection conn, String word, String next) throws SQLException {
        return queryInt(conn, """
                SELECT b.user_selected FROM bigrams b
                JOIN words w1 ON w1.word_id = b.word_id
                JOIN words w2 ON w2.word_id = b.next_word_id
                WHERE w1.word = ? AND w2.word = ?
                """, word, next);
    }

    /** Returns the stored occurrences of [word1, word2, next], or 0 if the triple isn't stored. */
    private static int trigramCount(Connection conn, String word1, String word2, String next)
            throws SQLException {
        return queryInt(conn, """
                SELECT t.occurrences FROM trigrams t
                JOIN words w1 ON w1.word_id = t.word1_id
                JOIN words w2 ON w2.word_id = t.word2_id
                JOIN words w3 ON w3.word_id = t.next_word_id
                WHERE w1.word = ? AND w2.word = ? AND w3.word = ?
                """, word1, word2, next);
    }

    /** Runs a query that returns at most one int, with the given string parameters. Returns 0 for no row. */
    private static int queryInt(Connection conn, String sql, String... params) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            for (int ix = 0; ix < params.length; ix++) {
                statement.setString(ix + 1, params[ix]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
