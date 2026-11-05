/*
 * Holds the SQL that finds which words can follow a word and how often a word
 * ends a sentence, for the sentence generators and autocomplete. It also adds
 * new words and counts autocomplete choices. Every method runs on the connection passed to the
 * constructor and never commits, so the caller opens and closes the connection
 * and can share it across many lookups. Database failures are rethrown as
 * RuntimeException so generators don't have to declare SQLException.
 *
 * Needs the tables from database/schema.sql.
 *
 * AI-assisted: getTransitions and addWord queries, tool not recorded
 *
 * Prompt:
 * Write an SQL query to retrieve possible next words and their occurrence frequencies
 * from the bigrams table by joining the words table and filtering by current word.
 *
 * Follow-up prompt:
 * Write an SQL query to insert a new word into the words table and handle duplicate
 * words to prevent inserting existing records.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.model.WordTransition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class WordRepository {

    // Every word that has followed the given word, with how often it did. Most frequent
    // comes first, and ties go alphabetically so the order is the same on every run.
    private static final String FIND_TRANSITIONS = """
            SELECT w2.word, b.occurrences
            FROM bigrams b
            JOIN words w1 ON b.word_id = w1.word_id
            JOIN words w2 ON b.next_word_id = w2.word_id
            WHERE w1.word = ?
            ORDER BY b.occurrences DESC, w2.word
            """;

    private static final String FIND_END_COUNT = "SELECT end_count FROM words WHERE word = ?";

    // DO NOTHING so adding a word that is already stored is not an error
    private static final String INSERT_WORD = """
            INSERT INTO words (word)
            VALUES (?)
            ON CONFLICT (word) DO NOTHING
            """;

    // Creates the pair if the user is the first to put these words together. Its occurrences
    // stay 0 because it never appeared in imported text.
    private static final String UPSERT_SELECTED = """
            INSERT INTO bigrams (word_id, next_word_id, user_selected)
            SELECT w1.word_id, w2.word_id, 1
            FROM words w1, words w2
            WHERE w1.word = ? AND w2.word = ?
            ON CONFLICT (word_id, next_word_id) DO UPDATE
            SET user_selected = bigrams.user_selected + 1
            """;

    private final Connection conn;   // Opened and closed by the caller

    /** Creates a repository that runs every query on the given connection. */
    public WordRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Finds every word that has followed the given word, with how often it did,
     * most frequent first. The generators and autocomplete use this to choose the
     * next word. Returns an empty list if nothing has ever followed the word.
     */
    public List<WordTransition> getTransitions(String word) {
        List<WordTransition> transitions = new ArrayList<>();

        try (PreparedStatement statement = conn.prepareStatement(FIND_TRANSITIONS)) {
            statement.setString(1, word);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    transitions.add(new WordTransition(rs.getString("word"), rs.getInt("occurrences")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving word transitions", e);
        }

        return transitions;
    }

    /**
     * Returns how many sentences ended on the given word, so the generators can
     * decide when to stop. Returns 0 if the word isn't stored.
     */
    public int getEndCount(String word) {
        try (PreparedStatement statement = conn.prepareStatement(FIND_END_COUNT)) {
            statement.setString(1, word);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("end_count") : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving end count", e);
        }
    }

    /** Adds a word to the words table with zero counts, unless it is already there. */
    public void addWord(String word) {
        try (PreparedStatement statement = conn.prepareStatement(INSERT_WORD)) {
            statement.setString(1, word);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error adding word", e);
        }
    }

    /**
     * Adds one to the count of how often autocomplete users chose nextWord after
     * previousWord, creating the pair if it is new. Both words must already be in
     * the words table.
     *
     * @throws IllegalArgumentException if either word is not in the words table
     */
    public void recordSelection(String previousWord, String nextWord) {
        try (PreparedStatement statement = conn.prepareStatement(UPSERT_SELECTED)) {
            statement.setString(1, previousWord);
            statement.setString(2, nextWord);

            // Zero rows means the lookup found no ids, so there was nothing to insert or update
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Word is not stored: " + previousWord + " or " + nextWord);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error recording autocomplete selection", e);
        }
    }
}
