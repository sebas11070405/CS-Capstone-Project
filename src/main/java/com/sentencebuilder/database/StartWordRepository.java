/*
 * Picks a random word that has started at least one sentence (start_count > 0
 * in the words table). Every such word has the same chance, however often it
 * started a sentence, and the same word can come up again on the next pick.
 * It only reads, so it never changes any counts.
 *
 * Create one with the connection to query on, for example
 * new StartWordRepository(Database.getConnection()), and call randomStartWord.
 * The caller owns and closes the connection. Nothing here depends on the
 * generators or the UI.
 *
 * Needs the words table from database/schema.sql.
 *
 * AI-assisted: Claude Code
 *
 * Prompt:
 * Create a new branch from the latest main for random starting-word selection. Add a small database
 * method that randomly picks a word with `start_count > 0`, giving each eligible word an equal chance.
 * Return an empty Optional if none qualify, and allow repeated picks. Keep it independent of the
 * generators and UI. Follow docs/comment-style.md and include this prompt in the header. Add tests
 * using isolated temporary tables so shared data stays untouched. Summarize the changes and test results
 * before pushing or opening a PR.
 */

package com.sentencebuilder.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class StartWordRepository {

    // ORDER BY random() gives every eligible row the same chance, unlike weighting by start_count.
    // The table name is unqualified so a temporary words table in the session takes its place in tests.
    private static final String RANDOM_START_WORD = """
            SELECT word FROM words
            WHERE start_count > 0
            ORDER BY random()
            LIMIT 1
            """;

    private final Connection conn;   // Owned by the caller, who also closes it

    /** Creates a repository that runs its query on the given connection. */
    public StartWordRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Returns a random word that has started at least one sentence, each with an
     * equal chance. Each call picks again, so the same word can be returned twice
     * in a row. Returns empty if no word has a start_count above zero.
     */
    public Optional<String> randomStartWord() throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(RANDOM_START_WORD);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString("word")) : Optional.empty();
        }
    }
}
