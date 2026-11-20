/*
 * Tests SentenceHistory. Each test runs against temporary copies of words and generated_sentences
 * that hide the shared tables for its connection only and are dropped on rollback, so shared data
 * is never read or changed. Needs .env.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.GeneratedSentenceRepository;
import com.sentencebuilder.database.GeneratedSentenceRepository.GeneratedSentence;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentenceHistoryTest {

    // Same columns, defaults, and checks as the shared tables. Temporary tables come first in the
    // search path, so "words" and "generated_sentences" mean these copies on this connection.
    private static final List<String> CREATE_TEMP_TABLES = List.of(
            "CREATE TEMP TABLE words (LIKE public.words INCLUDING ALL) ON COMMIT DROP",
            "CREATE TEMP TABLE generated_sentences (LIKE public.generated_sentences INCLUDING ALL) ON COMMIT DROP");

    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

    /**
     * Runs the work against empty temporary words and generated_sentences tables and always rolls
     * back, so they disappear even if the work fails. First checks that both names really resolve
     * to the temporary copies, so a test can't touch shared data.
     */
    private static void inTemporaryTables(DatabaseWork work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement statement = conn.createStatement()) {
                    for (String create : CREATE_TEMP_TABLES) {
                        statement.execute(create);
                    }
                }
                try (Statement statement = conn.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT bool_and(relnamespace = pg_my_temp_schema()) FROM pg_class "
                                     + "WHERE oid IN (to_regclass('words'), to_regclass('generated_sentences'))")) {
                    assertTrue(rs.next() && rs.getBoolean(1), "a table does not resolve to its temporary copy");
                }
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    /** Adds a word to the temporary words table, so sentences can be recorded as starting with it. */
    private static void addWord(Connection conn, String word) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO words (word, total_count, start_count) VALUES (?, 1, 1)")) {
            statement.setString(1, word);
            statement.executeUpdate();
        }
    }

    @Test
    void recordsEachSentenceWithItsStartWordAndAlgorithm() throws Exception {
        inTemporaryTables(conn -> {
            addWord(conn, "the");
            addWord(conn, "cat");

            SentenceHistory.write(conn, Algorithm.TRIGRAM_BACKOFF, List.of("the", "cat"),
                    List.of("the cat sat", "cat sat"));

            // Rows from one transaction come back newest insert first
            List<GeneratedSentence> recent = new GeneratedSentenceRepository(conn).findRecent(10);
            assertEquals(2, recent.size());
            assertEquals("cat sat", recent.get(0).text());
            assertEquals("cat", recent.get(0).startWord());
            assertEquals("TRIGRAM_BACKOFF", recent.get(0).algorithm());
            assertEquals("the cat sat", recent.get(1).text());
            assertEquals("the", recent.get(1).startWord());
            assertEquals("TRIGRAM_BACKOFF", recent.get(1).algorithm());
        });
    }

    @Test
    void repeatedSentencesAreKeptForTheDuplicatesReport() throws Exception {
        inTemporaryTables(conn -> {
            addWord(conn, "the");
            SentenceHistory.write(conn, Algorithm.MOST_LIKELY, List.of("the", "the"),
                    List.of("the cat sat", "the cat sat"));

            List<GeneratedSentenceRepository.DuplicateGroup> duplicates =
                    new GeneratedSentenceRepository(conn).findDuplicates(10);
            assertEquals(1, duplicates.size());
            assertEquals("the cat sat", duplicates.get(0).text());
            assertEquals(2, duplicates.get(0).timesGenerated());
        });
    }

    @Test
    void mismatchedListsRecordNothing() throws Exception {
        inTemporaryTables(conn -> {
            addWord(conn, "the");
            assertThrows(IllegalArgumentException.class, () -> SentenceHistory.write(conn,
                    Algorithm.MOST_LIKELY, List.of("the"), List.of("the cat", "the dog")));
            assertTrue(new GeneratedSentenceRepository(conn).findRecent(10).isEmpty());
        });
    }

    @Test
    void unknownStartWordIsRejected() throws Exception {
        inTemporaryTables(conn -> assertThrows(IllegalArgumentException.class, () -> SentenceHistory.write(conn,
                Algorithm.WEIGHTED_RANDOM, List.of("zzqmissing"), List.of("zzqmissing walks"))));
    }
}
