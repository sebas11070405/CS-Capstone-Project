package com.sentencebuilder.importer;

import com.sentencebuilder.database.Database;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Importer {

    // DO NOTHING rather than an error keeps the transaction usable for looking up the earlier import
    private static final String INSERT_SOURCE = """
            INSERT INTO source_files (file_name, source_type, word_count, content_hash)
            VALUES (?, 'file', ?, ?)
            ON CONFLICT (content_hash) WHERE source_type = 'file' DO NOTHING
            """;

    private static final String FIND_SOURCE = """
            SELECT file_name, imported_at FROM source_files
            WHERE content_hash = ? AND source_type = 'file'
            """;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    private static final String UPSERT_WORDS = """
            INSERT INTO words (word, total_count, start_count, end_count)
            SELECT * FROM unnest(?::text[], ?::int[], ?::int[], ?::int[])
            ON CONFLICT (word) DO UPDATE SET
                total_count = words.total_count + EXCLUDED.total_count,
                start_count = words.start_count + EXCLUDED.start_count,
                end_count   = words.end_count   + EXCLUDED.end_count
            """;

    public CorpusStats importFile(Path file) throws IOException, SQLException, DuplicateImportException {
        CorpusStats stats;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stats = CorpusStats.from(reader);
        }
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                write(conn, file.getFileName().toString(), stats);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
        return stats;
    }

    static void write(Connection conn, String fileName, CorpusStats stats)
            throws SQLException, DuplicateImportException {
        insertSource(conn, fileName, stats);
        writeWords(conn, stats);
    }

    private static void insertSource(Connection conn, String fileName, CorpusStats stats)
            throws SQLException, DuplicateImportException {
        try (PreparedStatement statement = conn.prepareStatement(INSERT_SOURCE)) {
            statement.setString(1, fileName);
            statement.setInt(2, stats.wordCount());
            statement.setString(3, stats.contentHash());
            if (statement.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement statement = conn.prepareStatement(FIND_SOURCE)) {
            statement.setString(1, stats.contentHash());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                String importedAt = rs.getObject("imported_at", OffsetDateTime.class)
                        .atZoneSameInstant(ZoneId.systemDefault())
                        .format(DATE_FORMAT);
                throw new DuplicateImportException(String.format("This text was already imported as %s on %s.",
                        rs.getString("file_name"), importedAt));
            }
        }
    }

    static void writeWords(Connection conn, CorpusStats stats) throws SQLException {
        // Sorted so concurrent imports lock rows in the same order and can't deadlock
        List<String> words = new ArrayList<>(stats.words().keySet());
        Collections.sort(words);

        int n = words.size();
        Integer[] totals = new Integer[n];
        Integer[] starts = new Integer[n];
        Integer[] ends = new Integer[n];
        for (int i = 0; i < n; i++) {
            CorpusStats.WordCounts counts = stats.words().get(words.get(i));
            totals[i] = counts.total();
            starts[i] = counts.start();
            ends[i] = counts.end();
        }

        try (PreparedStatement statement = conn.prepareStatement(UPSERT_WORDS)) {
            statement.setArray(1, conn.createArrayOf("text", words.toArray()));
            statement.setArray(2, conn.createArrayOf("int4", totals));
            statement.setArray(3, conn.createArrayOf("int4", starts));
            statement.setArray(4, conn.createArrayOf("int4", ends));
            statement.executeUpdate();
        }
    }
}
