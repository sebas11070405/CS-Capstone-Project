/*
 * Lists everything that has been added to the corpus, for the imported files report on the
 * Import tab (spec 1). Each source_files row is one imported text file or one batch of generated
 * sentences fed back in, with its word count and when it was added. Read only, so it never
 * changes any counts.
 *
 * Runs on the connection you pass in. Needs the source_files table from database/schema.sql.
 */

package com.sentencebuilder.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class SourceFileRepository {

    // file_id breaks ties, because rows added in one transaction share the same imported_at
    private static final String SELECT_SOURCES = """
            SELECT file_name, source_type, word_count, imported_at
            FROM source_files
            ORDER BY imported_at DESC, file_id DESC
            """;

    private final Connection conn;   // Caller opens and closes it

    /**
     * One imported text file or generated batch. generated is true for a batch of generated
     * sentences and false for a text file. wordCount includes repeated words.
     */
    public record SourceFile(String fileName, boolean generated, int wordCount, OffsetDateTime importedAt) {}

    /** Creates a repository that runs every query on the given connection. */
    public SourceFileRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Returns every imported file and generated batch, newest first. Rows added at the same moment
     * come back newest insert first, so the order never changes between calls. Returns an empty
     * list if nothing has been imported yet.
     */
    public List<SourceFile> findAll() throws SQLException {
        List<SourceFile> sources = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(SELECT_SOURCES);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                sources.add(new SourceFile(rs.getString(1), "generated".equals(rs.getString(2)),
                        rs.getInt(3), rs.getObject(4, OffsetDateTime.class)));
            }
        }
        return sources;
    }
}
