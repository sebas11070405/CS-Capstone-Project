/*
 * Reads everything the database knows about one word: its counts, the words
 * that have followed it, and the words that have come before it. The Words
 * screen uses this to show a word's details when the user selects it.
 *
 * Every method runs on the connection passed to the constructor and only reads,
 * so the caller owns the connection and decides when to close it. Words must
 * already be normalized the way imported text is (see Tokenizer.normalizeWord),
 * because the words table stores them lowercase.
 *
 * Needs the tables from database/schema.sql.
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

    private static final String FIND_WORD = """
            SELECT word, total_count, start_count, end_count
            FROM words
            WHERE word = ?
            """;

    // Most common first; user_selected breaks ties so words the user picked rank above never-picked ones
    private static final String FIND_FOLLOWERS = """
            SELECT w2.word, b.occurrences, b.user_selected
            FROM bigrams b
            JOIN words w1 ON b.word_id = w1.word_id
            JOIN words w2 ON b.next_word_id = w2.word_id
            WHERE w1.word = ?
            ORDER BY b.occurrences DESC, b.user_selected DESC, w2.word
            LIMIT ?
            """;

    // Same as FIND_FOLLOWERS in the other direction, using the index on next_word_id
    private static final String FIND_PREDECESSORS = """
            SELECT w1.word, b.occurrences, b.user_selected
            FROM bigrams b
            JOIN words w1 ON b.word_id = w1.word_id
            JOIN words w2 ON b.next_word_id = w2.word_id
            WHERE w2.word = ?
            ORDER BY b.occurrences DESC, b.user_selected DESC, w1.word
            LIMIT ?
            """;

    private final Connection conn;   // Owned by the caller, who also closes it

    /** A word with how often it appears in total, at the start of a sentence, and at the end. */
    public record WordInfo(String word, int totalCount, int startCount, int endCount) {}

    /**
     * A word next to another word in a pair: how often the pair occurs in imported text and how
     * often a user chose it through auto-complete.
     */
    public record Neighbor(String word, int occurrences, int userSelected) {}

    /** Creates a repository that runs every query on the given connection. */
    public WordDetailRepository(Connection conn) {
        this.conn = conn;
    }

    /** Looks up a word's counts. Returns empty if the word is not in the database. */
    public Optional<WordInfo> findWord(String word) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(FIND_WORD)) {
            statement.setString(1, word);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new WordInfo(rs.getString("word"), rs.getInt("total_count"),
                        rs.getInt("start_count"), rs.getInt("end_count")));
            }
        }
    }

    /**
     * Returns up to limit words that have followed the given word, most frequent first. The list
     * is empty if the word is unknown or nothing has followed it.
     */
    public List<Neighbor> findFollowers(String word, int limit) throws SQLException {
        return findNeighbors(FIND_FOLLOWERS, word, limit);
    }

    /**
     * Returns up to limit words that have come right before the given word, most frequent first.
     * The list is empty if the word is unknown or nothing has come before it.
     */
    public List<Neighbor> findPredecessors(String word, int limit) throws SQLException {
        return findNeighbors(FIND_PREDECESSORS, word, limit);
    }

    /**
     * Runs one of the neighbor queries, which both take a word and a limit and return the
     * neighbor's word, occurrences, and user_selected in that order.
     */
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
