/*
 * Looks up one word: its counts, what comes after it, and what comes before it.
 * The Words screen uses this for the detail view. Read only, runs on the
 * connection you pass in. Words need to be normalized first (Tokenizer.normalizeWord).
 */

package com.sentencebuilder.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class WordDetailRepository {

    private static final String FIND_WORD =
            "SELECT word, total_count, start_count, end_count FROM words WHERE word = ?";

    // Most common first, then the ones users picked, then alphabetical so ties stay stable
    private static final String FIND_FOLLOWERS = """
            SELECT w2.word, b.occurrences, b.user_selected
            FROM bigrams b
            JOIN words w1 ON b.word_id = w1.word_id
            JOIN words w2 ON b.next_word_id = w2.word_id
            WHERE w1.word = ?
            ORDER BY b.occurrences DESC, b.user_selected DESC, w2.word
            LIMIT ?
            """;

    // Same thing flipped around
    private static final String FIND_PREDECESSORS = """
            SELECT w1.word, b.occurrences, b.user_selected
            FROM bigrams b
            JOIN words w1 ON b.word_id = w1.word_id
            JOIN words w2 ON b.next_word_id = w2.word_id
            WHERE w2.word = ?
            ORDER BY b.occurrences DESC, b.user_selected DESC, w1.word
            LIMIT ?
            """;

    private final Connection conn;   // Caller opens and closes it

    /** A word and how often it shows up overall, at sentence starts, and at sentence ends. */
    public record WordInfo(String word, int totalCount, int startCount, int endCount) {}

    /** A word next to another one, with how often the pair showed up and how often users picked it. */
    public record Neighbor(String word, int occurrences, int userSelected) {}

    public WordDetailRepository(Connection conn) {
        this.conn = conn;
    }

    /** Gets a word's counts, or empty if we've never seen it. */
    public Optional<WordInfo> findWord(String word) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(FIND_WORD)) {
            statement.setString(1, word);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next()
                        ? Optional.of(new WordInfo(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
                        : Optional.empty();
            }
        }
    }

    /** Top limit words that come after this one, most common first. Empty if none. */
    public List<Neighbor> findFollowers(String word, int limit) throws SQLException {
        return findNeighbors(FIND_FOLLOWERS, word, limit);
    }

    /** Top limit words that come right before this one, most common first. Empty if none. */
    public List<Neighbor> findPredecessors(String word, int limit) throws SQLException {
        return findNeighbors(FIND_PREDECESSORS, word, limit);
    }

    // Both neighbor queries take (word, limit) and return (word, occurrences, user_selected)
    private List<Neighbor> findNeighbors(String sql, String word, int limit) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        List<Neighbor> neighbors = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, word);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    neighbors.add(new Neighbor(rs.getString(1), rs.getInt(2), rs.getInt(3)));
                }
            }
        }
        return neighbors;
    }
}
