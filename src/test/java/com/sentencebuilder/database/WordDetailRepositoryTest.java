/*
 * Tests WordDetailRepository against the shared database. Each test seeds a
 * few made-up words and word pairs inside a transaction that is always rolled
 * back, so nothing is left behind.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
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

    // Made up so they can't collide with real words already in the shared database
    private static final String WORD_A = "zzqdetaila";
    private static final String WORD_B = "zzqdetailb";
    private static final String WORD_C = "zzqdetailc";
    private static final String WORD_D = "zzqdetaild";

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

    /**
     * Adds the four test words and these pairs, written as first -> second (occurrences, user_selected):
     * a -> b (5, 0), a -> c (5, 2), a -> d (1, 0), c -> a (7, 0), d -> a (3, 0).
     */
    private static void seed(Connection conn) throws SQLException {
        insertWord(conn, WORD_A, 10, 2, 1);
        insertWord(conn, WORD_B, 5, 0, 0);
        insertWord(conn, WORD_C, 8, 0, 0);
        insertWord(conn, WORD_D, 4, 0, 0);

        insertPair(conn, WORD_A, WORD_B, 5, 0);
        insertPair(conn, WORD_A, WORD_C, 5, 2);
        insertPair(conn, WORD_A, WORD_D, 1, 0);
        insertPair(conn, WORD_C, WORD_A, 7, 0);
        insertPair(conn, WORD_D, WORD_A, 3, 0);
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

    private static void insertPair(Connection conn, String first, String second, int occurrences,
                                   int userSelected) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO bigrams (word_id, next_word_id, occurrences, user_selected)
                SELECT w1.word_id, w2.word_id, ?, ?
                FROM words w1, words w2
                WHERE w1.word = ? AND w2.word = ?
                """)) {
            statement.setInt(1, occurrences);
            statement.setInt(2, userSelected);
            statement.setString(3, first);
            statement.setString(4, second);
            statement.executeUpdate();
        }
    }

    @Test
    void findsAWordsCounts() throws Exception {
        inRolledBackTransaction(conn -> {
            seed(conn);

            Optional<WordInfo> info = new WordDetailRepository(conn).findWord(WORD_A);

            assertEquals(Optional.of(new WordInfo(WORD_A, 10, 2, 1)), info);
        });
    }

    @Test
    void unknownWordHasNoInfoAndNoNeighbors() throws Exception {
        inRolledBackTransaction(conn -> {
            WordDetailRepository repository = new WordDetailRepository(conn);

            assertTrue(repository.findWord("zzqnotstored").isEmpty());
            assertTrue(repository.findFollowers("zzqnotstored", 10).isEmpty());
            assertTrue(repository.findPredecessors("zzqnotstored", 10).isEmpty());
        });
    }

    @Test
    void followersAreOrderedByOccurrencesThenUserSelected() throws Exception {
        inRolledBackTransaction(conn -> {
            seed(conn);

            List<Neighbor> followers = new WordDetailRepository(conn).findFollowers(WORD_A, 10);

            // b and c both occur 5 times, so the one users picked comes first
            assertEquals(List.of(
                    new Neighbor(WORD_C, 5, 2),
                    new Neighbor(WORD_B, 5, 0),
                    new Neighbor(WORD_D, 1, 0)), followers);
        });
    }

    @Test
    void predecessorsAreOrderedByOccurrences() throws Exception {
        inRolledBackTransaction(conn -> {
            seed(conn);

            List<Neighbor> predecessors = new WordDetailRepository(conn).findPredecessors(WORD_A, 10);

            assertEquals(List.of(new Neighbor(WORD_C, 7, 0), new Neighbor(WORD_D, 3, 0)), predecessors);
        });
    }

    @Test
    void limitKeepsOnlyTheTopNeighbors() throws Exception {
        inRolledBackTransaction(conn -> {
            seed(conn);

            List<Neighbor> followers = new WordDetailRepository(conn).findFollowers(WORD_A, 2);

            assertEquals(List.of(new Neighbor(WORD_C, 5, 2), new Neighbor(WORD_B, 5, 0)), followers);
        });
    }

    @Test
    void limitBelowOneIsRejected() throws Exception {
        inRolledBackTransaction(conn -> {
            WordDetailRepository repository = new WordDetailRepository(conn);

            assertThrows(IllegalArgumentException.class, () -> repository.findFollowers(WORD_A, 0));
            assertThrows(IllegalArgumentException.class, () -> repository.findPredecessors(WORD_A, -1));
        });
    }
}
