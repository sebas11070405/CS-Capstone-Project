/*
 * Answers WordRepository's questions from the Supabase database. Each call
 * opens a connection, runs one query, and closes it. Needs DB_URL, DB_USER,
 * and DB_PASSWORD in .env (see Database).
 */

package com.sentencebuilder.repository;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.tokenizer.Tokenizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcWordRepository implements WordRepository {

    // Uses ix_bigrams_follow, so the rows come back already in order
    private static final String BIGRAM_FOLLOWERS = """
            SELECT next.word, bigram.occurrences
            FROM bigrams bigram
            JOIN words current ON current.word_id = bigram.word_id
            JOIN words next    ON next.word_id    = bigram.next_word_id
            WHERE current.word = ?
            ORDER BY bigram.occurrences DESC, next.word
            """;

    private static final String TRIGRAM_FOLLOWERS = """
            SELECT next.word, trigram.occurrences
            FROM trigrams trigram
            JOIN words first  ON first.word_id  = trigram.word1_id
            JOIN words second ON second.word_id = trigram.word2_id
            JOIN words next   ON next.word_id   = trigram.next_word_id
            WHERE first.word = ? AND second.word = ?
            ORDER BY trigram.occurrences DESC, next.word
            """;

    private static final String SENTENCE_STARTERS = """
            SELECT word, start_count
            FROM words
            WHERE start_count > 0
            ORDER BY start_count DESC, word
            """;

    // Looks both words up by name, so the caller never needs ids
    private static final String RECORD_SELECTION = """
            UPDATE bigrams
            SET user_selected = user_selected + 1
            WHERE word_id = (SELECT word_id FROM words WHERE word = ?)
              AND next_word_id = (SELECT word_id FROM words WHERE word = ?)
            """;

    @Override
    public List<WordCount> followersOf(String word) {
        String current = Tokenizer.normalizeWord(word);
        if (current == null) {
            return List.of();
        }
        return queryCounts(BIGRAM_FOLLOWERS, current);
    }

    @Override
    public List<WordCount> followersOf(String first, String second) {
        String firstWord = Tokenizer.normalizeWord(first);
        String secondWord = Tokenizer.normalizeWord(second);
        if (firstWord == null || secondWord == null) {
            return List.of();
        }
        return queryCounts(TRIGRAM_FOLLOWERS, firstWord, secondWord);
    }

    @Override
    public List<WordCount> sentenceStarters() {
        return queryCounts(SENTENCE_STARTERS);
    }

    @Override
    public boolean recordSelection(String word, String next) {
        String current = Tokenizer.normalizeWord(word);
        String nextWord = Tokenizer.normalizeWord(next);
        if (current == null || nextWord == null) {
            return false;
        }
        try (Connection conn = Database.getConnection();
             PreparedStatement statement = conn.prepareStatement(RECORD_SELECTION)) {
            statement.setString(1, current);
            statement.setString(2, nextWord);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RepositoryException("Could not record the selection of " + nextWord, e);
        }
    }

    /** Runs a query whose rows are (word, count) and whose parameters are all strings. */
    private static List<WordCount> queryCounts(String sql, String... parameters) {
        try (Connection conn = Database.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
            for (int ix = 0; ix < parameters.length; ix++) {
                statement.setString(ix + 1, parameters[ix]);
            }
            List<WordCount> results = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    results.add(new WordCount(rows.getString(1), rows.getInt(2)));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new RepositoryException("Database query failed", e);
        }
    }
}