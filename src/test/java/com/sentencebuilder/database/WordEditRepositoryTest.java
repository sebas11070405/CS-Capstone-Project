/*
 * Tests WordEditRepository on the shared database. Every test seeds a fake word
 * and rolls back at the end, so nothing sticks around. Needs .env.
 */

package com.sentencebuilder.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordEditRepositoryTest {

    // Fake word so it can't clash with a real one
    private static final String WORD = "zzqedit";

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

    private static void seed(Connection conn) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO words (word, total_count, start_count, end_count) VALUES (?, 10, 2, 1)")) {
            statement.setString(1, WORD);
            statement.executeUpdate();
        }
    }

    // Returns [total, start, end] as stored
    private static List<Integer> counts(Connection conn) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT total_count, start_count, end_count FROM words WHERE word = ?")) {
            statement.setString(1, WORD);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), WORD + " not found");
                return List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
        }
    }

    @Test
    void updatesTheCounts() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            assertTrue(new WordEditRepository(conn).updateCounts(WORD, 20, 5, 4));
            assertEquals(List.of(20, 5, 4), counts(conn));
        });
    }

    @Test
    void startAndEndCanEqualTotal() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            assertTrue(new WordEditRepository(conn).updateCounts(WORD, 3, 3, 3));
            assertEquals(List.of(3, 3, 3), counts(conn));
        });
    }

    @Test
    void unknownWordReturnsFalse() throws Exception {
        rolledBack(conn -> assertFalse(new WordEditRepository(conn).updateCounts("zzqnope", 5, 1, 1)));
    }

    @Test
    void badCountsAreRejectedAndNothingChanges() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordEditRepository repository = new WordEditRepository(conn);

            assertThrows(IllegalArgumentException.class, () -> repository.updateCounts(WORD, -1, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> repository.updateCounts(WORD, 5, 6, 0));
            assertThrows(IllegalArgumentException.class, () -> repository.updateCounts(WORD, 5, 0, 6));
            assertEquals(List.of(10, 2, 1), counts(conn));
        });
    }
}
