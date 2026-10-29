/*
 * Reads a UTF-8 text file, counts its words with CorpusStats, and adds the
 * counts to the words table. The source_files row and the word counts are
 * written in one transaction, so a failed import leaves the database
 * unchanged. If the same text was imported before (matched by content hash),
 * it throws DuplicateImportException and writes nothing. Bigrams and trigrams
 * aren't written yet.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 */

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

    // Finds the earlier import of the same text for the duplicate error message
    private static final String FIND_SOURCE = """
            SELECT file_name, imported_at FROM source_files
            WHERE content_hash = ? AND source_type = 'file'
            """;

    // For example, "Oct 28, 2026 at 7:15 PM"
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    // unnest turns the four arrays into rows, so every word is inserted or updated in one round trip
    private static final String UPSERT_WORDS = """
            INSERT INTO words (word, total_count, start_count, end_count)
            SELECT * FROM unnest(?::text[], ?::int[], ?::int[], ?::int[])
            ON CONFLICT (word) DO UPDATE SET
                total_count = words.total_count + EXCLUDED.total_count,
                start_count = words.start_count + EXCLUDED.start_count,
                end_count   = words.end_count   + EXCLUDED.end_count
            """;

    /**
     * Imports a text file and returns its counts. Nothing is written if the text
     * was already imported or if any step fails.
     */
    public CorpusStats importFile(Path file) throws IOException, SQLException, DuplicateImportException {
        // Count the whole file before connecting, so the connection isn't held open while it's read
        CorpusStats stats;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stats = CorpusStats.from(reader);
        }

        // One transaction for everything, rolled back on any error so no partial import is left behind
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

    /**
     * Writes the source_files row and the word counts on the given connection
     * without committing. Package-private so ImporterTest can run it inside a
     * transaction it rolls back.
     */
    static void write(Connection conn, String fileName, CorpusStats stats)
            throws SQLException, DuplicateImportException {
        insertSource(conn, fileName, stats);
        writeWords(conn, stats);
    }

    /**
     * Records the file in source_files. If the same text was already imported,
     * looks up that earlier import and throws DuplicateImportException naming it.
     */
    private static void insertSource(Connection conn, String fileName, CorpusStats stats)
            throws SQLException, DuplicateImportException {
        try (PreparedStatement statement = conn.prepareStatement(INSERT_SOURCE)) {
            statement.setString(1, fileName);
            statement.setInt(2, stats.wordCount());
            statement.setString(3, stats.contentHash());
            // One row inserted means this text is new
            if (statement.executeUpdate() == 1) {
                return;
            }
        }

        // Nothing was inserted, so the hash is already there. Find that import to name it in the error.
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

    /** Adds every word's counts to the words table, inserting words that are new. */
    static void writeWords(Connection conn, CorpusStats stats) throws SQLException {
        // Sorted so concurrent imports lock rows in the same order and can't deadlock
        List<String> words = new ArrayList<>(stats.words().keySet());
        Collections.sort(words);

        // Build parallel arrays for UPSERT_WORDS: entry ix of each array belongs to words[ix]
        int wordTotal = words.size();
        Integer[] totals = new Integer[wordTotal];
        Integer[] starts = new Integer[wordTotal];
        Integer[] ends = new Integer[wordTotal];
        for (int ix = 0; ix < wordTotal; ix++) {
            CorpusStats.WordCounts counts = stats.words().get(words.get(ix));
            totals[ix] = counts.total();
            starts[ix] = counts.start();
            ends[ix] = counts.end();
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
