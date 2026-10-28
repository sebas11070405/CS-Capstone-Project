package com.sentencebuilder.importer;

import com.sentencebuilder.database.Database;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterTest {

    // Requires a valid .env. Everything is rolled back, so nothing is left in Supabase.
    @Test
    void writingTwiceAddsToExistingCounts() throws Exception {
        CorpusStats stats = CorpusStats.from(new StringReader("Zzqtest one. Zzqtest two zzqtest."));
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Importer.writeWords(conn, stats);
                assertCounts(conn, "zzqtest", 3, 2, 1);

                Importer.writeWords(conn, stats);
                assertCounts(conn, "zzqtest", 6, 4, 2);
            } finally {
                conn.rollback();
            }
        }
    }

    private static void assertCounts(Connection conn, String word, int total, int start, int end)
            throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT total_count, start_count, end_count FROM words WHERE word = ?")) {
            statement.setString(1, word);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), word + " not found");
                assertEquals(total, rs.getInt(1));
                assertEquals(start, rs.getInt(2));
                assertEquals(end, rs.getInt(3));
            }
        }
    }
}
