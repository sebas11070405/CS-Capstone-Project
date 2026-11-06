/*
 * Tests WordDetailRepository on the shared database. Every test seeds fake
 * words and rolls back at the end, so nothing sticks around. Needs .env.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.database.WordDetailRepository.Neighbor;
import com.sentencebuilder.database.WordDetailRepository.WordInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordDetailRepositoryTest {

    // Fake words so they can't clash with real ones
    private static final String A = "zzqdetaila";
    private static final String B = "zzqdetailb";
    private static final String C = "zzqdetailc";
    private static final String D = "zzqdetaild";

    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

    // Always rolls back, even if the test fails
    private static void rolledBack(DatabaseWork work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    // Pairs as first -> second (occurrences, picks): a>b (5,0) a>c (5,2) a>d (1,0) c>a (7,0) d>a (3,0)
    private static void seed(Connection conn) throws SQLException {
        for (String word : List.of(B, C, D)) {
            insertWord(conn, word, 5, 0, 0);
        }
        insertWord(conn, A, 10, 2, 1);
        insertPair(conn, A, B, 5, 0);
        insertPair(conn, A, C, 5, 2);
        insertPair(conn, A, D, 1, 0);
        insertPair(conn, C, A, 7, 0);
        insertPair(conn, D, A, 3, 0);
    }

    private static void insertWord(Connection conn, String word, int total, int start, int end)
            throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO words (word, total_count, start_count, end_count) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, word);
            statement.setInt(2, total);
            statement.setInt(3, start);
            statement.setInt(4, end);
            statement.executeUpdate();
        }
    }

    private static void insertPair(Connection conn, String first, String second, int occurrences, int picks)
            throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO bigrams (word_id, next_word_id, occurrences, user_selected)
                SELECT w1.word_id, w2.word_id, ?, ? FROM words w1, words w2
                WHERE w1.word = ? AND w2.word = ?
                """)) {
            statement.setInt(1, occurrences);
            statement.setInt(2, picks);
            statement.setString(3, first);
            statement.setString(4, second);
            statement.executeUpdate();
        }
    }

    @Test
    void findsAWordsCounts() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            assertEquals(Optional.of(new WordInfo(A, 10, 2, 1)), new WordDetailRepository(conn).findWord(A));
        });
    }

    @Test
    void unknownWordGivesNothing() throws Exception {
        rolledBack(conn -> {
            WordDetailRepository repository = new WordDetailRepository(conn);
            assertTrue(repository.findWord("zzqnope").isEmpty());
            assertTrue(repository.findFollowers("zzqnope", 10).isEmpty());
            assertTrue(repository.findPredecessors("zzqnope", 10).isEmpty());
        });
    }

    @Test
    void followersSortByOccurrencesThenPicks() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            // b and c both hit 5, c wins because users picked it
            assertEquals(List.of(new Neighbor(C, 5, 2), new Neighbor(B, 5, 0), new Neighbor(D, 1, 0)),
                    new WordDetailRepository(conn).findFollowers(A, 10));
        });
    }

    @Test
    void predecessorsSortByOccurrences() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            assertEquals(List.of(new Neighbor(C, 7, 0), new Neighbor(D, 3, 0)),
                    new WordDetailRepository(conn).findPredecessors(A, 10));
        });
    }

    @Test
    void limitCapsTheResults() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            assertEquals(List.of(new Neighbor(C, 5, 2), new Neighbor(B, 5, 0)),
                    new WordDetailRepository(conn).findFollowers(A, 2));
        });
    }

    @Test
    void limitBelowOneThrows() throws Exception {
        rolledBack(conn -> {
            WordDetailRepository repository = new WordDetailRepository(conn);
            assertThrows(IllegalArgumentException.class, () -> repository.findFollowers(A, 0));
            assertThrows(IllegalArgumentException.class, () -> repository.findPredecessors(A, -1));
        });
    }
}
