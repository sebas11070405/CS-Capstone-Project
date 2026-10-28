package com.sentencebuilder.importer;

import com.sentencebuilder.database.Database;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Requires a valid .env. Each test is rolled back, so nothing is left in Supabase.
class ImporterTest {

    // Made up so it can't collide with real words already in the shared database
    private static final String WORD = "zzqtest";

    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

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

            DuplicateImportException e = assertThrows(DuplicateImportException.class, () ->
                    Importer.write(conn, "renamed.txt", stats("ZZQTEST one.\r\nZzqtest two zzqtest.")));
            assertTrue(e.getMessage().contains("original.txt"), e.getMessage());
            assertCounts(conn, 3, 2, 1);
        });
    }

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
