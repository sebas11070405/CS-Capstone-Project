package com.sentencebuilder.repository;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.model.WordTransition;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL implementation of the TransitionRepository interface.
 *
 * This class provides database operations for retrieving and managing
 * word transitions used by the sentence generation and autocomplete
 * components of the Sentence Builder application.
 *
 * Word transitions are retrieved from the bigrams table, which stores
 * pairs of consecutive words and their frequencies of occurrence.
 * The repository converts these database records into WordTransition
 * objects that sentence generation algorithms can use.
 *
 * The repository also supports checking whether a word exists,
 * inserting new words, and recording user selections made through
 * the autocomplete feature.
 *
 * Database connections are established using the Database class,
 * and SQL queries are executed using prepared statements.
 *
 * @see TransitionRepository
 * @see WordTransition
 * @see Database
 */
public class PostgresTransitionRepo implements TransitionRepository{

    /**
     * Retrieves all possible next-word transitions for a given word.
     *
     * The method queries the bigrams table to find words that follow
     * the specified word in the corpus. Each matching record contains
     * a possible next word and its frequency of occurrence.
     *
     * The results are converted into WordTransition objects and
     * returned as a list. These transitions are used by the Markov
     * and Most Frequent Word generators to predict the next word.
     *
     * AI Prompt: Write an SQL query to retrieve possible next words and their occurrence frequencies
     * from the bigrams table by joining the words table and filtering by current word.
     *
     * @param word the current word used to search for possible next words
     * @return a list of WordTransition objects containing possible next words and their frequencies; an empty list if
     *         no matching transitions exist
     * @throws RuntimeException if a database operation fails
     */
    @Override
    public List<WordTransition> getTransitions(String word) {

        List<WordTransition> transitions = new ArrayList<>();

        String sql = """
                SELECT w2.word, b.occurrences
                FROM bigrams b
                JOIN words w1 ON b.word_id = w1.word_id
                JOIN words w2 ON b.next_word_id = w2.word_id
                WHERE w1.word = ?
                """;

        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            /** Set current word as the query parameter */
            stmt.setString(1, word);

            try (ResultSet rs = stmt.executeQuery()) {
                /** Convert each database record into a WordTransition object */
                while (rs.next()) {
                    String nextWord = rs.getString("word");
                    int frequency = rs.getInt("occurrences");

                    transitions.add(new WordTransition(nextWord, frequency));
                }
            }

        }catch (SQLException e){
            throw new RuntimeException("Error retrieving word transitions", e);
        }

        return transitions;
    }

    /**
     * Checks whether a specified word exists in the database.
     *
     * The method searches the words table for an exact match.
     * This operation can be used by the autocomplete component
     * to determine whether a word has already been stored.
     *
     * @param word the word to search for in the database
     * @return true if the word exists; false otherwise
     * @throws RuntimeException if a database operation fails
     */
    @Override
    public boolean wordExists(String word) {
        String sql = "SELECT 1 FROM words WHERE word = ?";

        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, word);

            /** A matching row indicates that the word exists */
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error checking word existence", e);
        }
    }

    /**
     * Inserts a new word into the words table.
     *
     * The method attempts to insert the specified word into the
     * database. If the word already exists, the ON CONFLICT clause
     * prevents a duplicate entry from being created.
     *
     * This operation supports the autocomplete feature by allowing
     * previously unknown words to be added to the database.
     *
     * AI Prompt: Write an SQL query to insert a new word into the words table and handle duplicate
     * words to prevent inserting existing records.
     *
     * @param word the word to insert into the database
     * @throws RuntimeException if a database operation fails
     */
    @Override
    public void addWord(String word) {
        String sql = """
                INSERT INTO words (word)
                VALUES (?)
                ON CONFLICT (word) DO NOTHING
                """;

        try (Connection conn = Database.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)){
            stmt.setString(1, word);

            /** Insert word unless it already exists. */
            stmt.executeUpdate();
        } catch (SQLException e){
            throw new RuntimeException("Error adding word", e);
        }
    }

    /**
     * Records a user's selection of an autocomplete suggestion.
     *
     * The method updates the user_selected count for a bigram
     * consisting of the previous word and the selected next word.
     * Each successful selection increments the count by one.
     *
     * The user_selected value can be used by the autocomplete
     * component to track how frequently users choose a particular
     * word suggestion.
     *
     * If the specified bigram does not exist in the database,
     * the method throws an IllegalArgumentException.
     *
     * @param previousWord the word preceding the selected suggestion
     * @param nextWord the word selected by the user
     * @throws IllegalArgumentException if the specified bigram does not exist
     * @throws RuntimeException if a database operation fails
     */
    @Override
    public void recordSelection(String previousWord, String nextWord) {
        String sql = """
                UPDATE bigrams
                SET user_selected = user_selected + 1
                WHERE word_id = (
                    SELECT word_id FROM words WHERE word = ?
                )
                AND next_word_id = (
                    SELECT word_id FROM words WHERE word = ?
                )
                """;


        try (Connection conn = Database.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)){

            stmt.setString(1, previousWord);
            stmt.setString(2, nextWord);

            /** Increment selection count for matching bigram */
            int rowsUpdated = stmt.executeUpdate();

            /** Report error if no matching bigram was found */
            if (rowsUpdated == 0){
                throw new IllegalArgumentException("Bigram does not exist: " + previousWord + " -> " + nextWord);

            }
        } catch (SQLException e){
            throw new RuntimeException("Error recording autocomplete section", e);
        }
    }
}
