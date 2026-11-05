package com.sentencebuilder.repository;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.model.WordTransition;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL implementation of the TrigramRepo interface.
 *
 * This class retrieves trigram transitions from the PostgreSQL
 * database for use by the TrigramBackoffGenerator.
 *
 * A trigram consists of three consecutive words. Given two
 * preceding words, the repository retrieves possible next words
 * and their occurrence frequencies from the training corpus.
 *
 * The retrieved database records are converted into WordTransition
 * objects, which the generator uses for weighted random selection.
 *
 * If no matching trigram transitions exist, an empty list is returned.
 *
 * @see TrigramRepo
 * @see WordTransition
 * @see Database
 */
public class PostgresTrigramRepo implements TrigramRepo{

    /**
     * Retrieves possible next-word transitions based on two preceding words.
     *
     * The method queries the trigrams table using the previous word
     * and current word as search parameters. The words table is joined
     * to retrieve the actual word strings associated with the stored
     * word IDs.
     *
     * Each matching record contains a possible next word and its
     * frequency of occurrence in the corpus. These values are converted
     * into WordTransition objects and returned to the generator.
     *
     * AI Prompt: Implement an SQL query to retrieve possible next words and their occurrence frequencies from the trigrams table.
     *
     * @param previousWord the first word in the two-word context
     * @param currentWord the second word in the two-word context
     * @return a list of possible next-word transitions and their
     *         occurrence frequencies, or an empty list if no
     *         matching trigrams exist
     * @throws RuntimeException if a database operation fails
     */
    @Override
    public List<WordTransition> getTrigramTransitions(String previousWord, String currentWord) {

        List<WordTransition> transitions = new ArrayList<>();

        String sql = """
                SELECT w3.word AS next_word, t.frequency
                FROM trigrams t
                JOIN words w1 ON t.word1_id = w1.word_id
                JOIN words w2 ON t.word2_id = w2.word_id
                JOIN words w3 ON t.next_word_id = w3.word_id
                WHERE w1.word = ?
                AND w2.word = ?
                """;

        // Establish database connection
        try (Connection conn = Database.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)){

            // Assign two preceding words to the query parameters
            stmt.setString(1, previousWord);
            stmt.setString(2, currentWord);

            // Execute the query and retrieve matching trigram records
            try (ResultSet rs = stmt.executeQuery()){

                while(rs.next()){
                    String nextWord = rs.getString("next_word");
                    int frequency = rs.getInt("frequency");

                    // Convert records into WordTransition objects
                    transitions.add(new WordTransition(nextWord, frequency));
                };
            }
        } catch(SQLException e){
            throw new RuntimeException("Error retrieving trigram transition", e);
        }
        return transitions;
    }
}
