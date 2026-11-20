/*
 * Tests SourceFileRepository. Each test runs against a temporary copy of source_files that hides
 * the shared table for its connection only and is dropped on rollback, so shared data is never
 * read or changed and the results don't depend on what has been imported. Needs .env.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.database.SourceFileRepository.SourceFile;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFileRepositoryTest {

    // Same columns, defaults, and checks as the shared table. A temporary table comes first in the
    // search path, so "source_files" means this copy on this connection.
    private static final String CREATE_TEMP_SOURCES =
            "CREATE TEMP TABLE source_files (LIKE public.source_files INCLUDING ALL) ON COMMIT DROP";

    private static final OffsetDateTime JAN_1 = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime JAN_2 = JAN_1.plusDays(1);
    private static final OffsetDateTime JAN_3 = JAN_1.plusDays(2);

    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

    /**
     * Runs the work against an empty temporary source_files table and always rolls back, so the
     * table disappears even if the work fails. First checks that "source_files" really resolves to
     * the temporary copy, so a test can't touch shared data.
     */
    private static void inTemporarySources(DatabaseWork work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement statement = conn.createStatement()) {
                    statement.execute(CREATE_TEMP_SOURCES);
                }
                try (Statement statement = conn.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT relnamespace = pg_my_temp_schema() FROM pg_class "
                                     + "WHERE oid = to_regclass('source_files')")) {
                    assertTrue(rs.next() && rs.getBoolean(1), "source_files does not resolve to the temporary table");
                }
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    /**
     * Adds an imported file to the temporary table. Files need a content hash, so the name padded
     * to 64 characters stands in for one.
     */
    private static void addFile(Connection conn, String name, int words, OffsetDateTime importedAt)
            throws SQLException {
        insert(conn, name, "file", words, importedAt, String.format("%-64s", name).replace(' ', '0'));
    }

    /** Adds a generated batch to the temporary table. Batches have no content hash. */
    private static void addBatch(Connection conn, String name, int words, OffsetDateTime importedAt)
            throws SQLException {
        insert(conn, name, "generated", words, importedAt, null);
    }

    /** Inserts one source_files row with an explicit import time, so tests control the order. */
    private static void insert(Connection conn, String name, String type, int words,
                               OffsetDateTime importedAt, String hash) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO source_files (file_name, source_type, word_count, imported_at, content_hash) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, name);
            statement.setString(2, type);
            statement.setInt(3, words);
            statement.setObject(4, importedAt);
            statement.setString(5, hash);
            statement.executeUpdate();
        }
    }

    // Just the names, in order, so each test can compare short lists
    private static List<String> names(List<SourceFile> sources) {
        return sources.stream().map(SourceFile::fileName).toList();
    }

    @Test
    void nothingImportedGivesAnEmptyList() throws Exception {
        inTemporarySources(conn -> assertTrue(new SourceFileRepository(conn).findAll().isEmpty()));
    }

    @Test
    void listsNewestFirst() throws Exception {
        inTemporarySources(conn -> {
            addFile(conn, "first.txt", 10, JAN_1);
            addFile(conn, "third.txt", 30, JAN_3);
            addFile(conn, "second.txt", 20, JAN_2);
            assertEquals(List.of("third.txt", "second.txt", "first.txt"),
                    names(new SourceFileRepository(conn).findAll()));
        });
    }

    @Test
    void sameTimeListsTheLaterInsertFirst() throws Exception {
        inTemporarySources(conn -> {
            addFile(conn, "earlier.txt", 10, JAN_1);
            addFile(conn, "later.txt", 10, JAN_1);
            assertEquals(List.of("later.txt", "earlier.txt"), names(new SourceFileRepository(conn).findAll()));
        });
    }

    @Test
    void readsEveryColumnForFilesAndBatches() throws Exception {
        inTemporarySources(conn -> {
            addFile(conn, "holmes.txt", 1234, JAN_1);
            addBatch(conn, "Generated batch of 5 sentences", 42, JAN_2);

            List<SourceFile> sources = new SourceFileRepository(conn).findAll();
            assertEquals(2, sources.size());

            SourceFile batch = sources.get(0);
            assertEquals("Generated batch of 5 sentences", batch.fileName());
            assertTrue(batch.generated());
            assertEquals(42, batch.wordCount());
            assertEquals(JAN_2.toInstant(), batch.importedAt().toInstant());

            SourceFile file = sources.get(1);
            assertEquals("holmes.txt", file.fileName());
            assertFalse(file.generated());
            assertEquals(1234, file.wordCount());
            assertEquals(JAN_1.toInstant(), file.importedAt().toInstant());
        });
    }
}
