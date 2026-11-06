/*
 * Lets the Words screen fix a word's counts by hand. Runs on the connection you
 * pass in and never commits, so the caller decides when it sticks. Words need to
 * be normalized first (Tokenizer.normalizeWord).
 */

package com.sentencebuilder.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class WordEditRepository {

    private static final String UPDATE_COUNTS =
            "UPDATE words SET total_count = ?, start_count = ?, end_count = ? WHERE word = ?";

    private final Connection conn;   // Caller opens, commits, and closes it

    public WordEditRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Sets a word's total, start, and end counts. Returns false if the word isn't stored.
     * Throws IllegalArgumentException for counts the schema would reject: negative, or
     * start/end above total. Checked here so the UI gets a readable message instead of a
     * raw constraint error.
     */
    public boolean updateCounts(String word, int total, int start, int end) throws SQLException {
        if (total < 0 || start < 0 || end < 0) {
            throw new IllegalArgumentException("Counts can't be negative");
        }
        if (start > total || end > total) {
            throw new IllegalArgumentException("Start and end counts can't be higher than the total");
        }
        try (PreparedStatement statement = conn.prepareStatement(UPDATE_COUNTS)) {
            statement.setInt(1, total);
            statement.setInt(2, start);
            statement.setInt(3, end);
            statement.setString(4, word);
            return statement.executeUpdate() == 1;
        }
    }
}
