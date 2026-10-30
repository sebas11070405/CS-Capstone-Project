package com.sentencebuilder.repository;

import com.sentencebuilder.model.WordTransition;
import java.util.List;

/**
 * Defines the data access operations required for retrieving
 * and managing word transitions in the Sentence Builder application.
 *
 * Implementations can retrieve word transitions from a PostgreSQL
 * database or use predefined data for testing.
 *
 * The interface supports retrieving bigram transitions,
 * managing vocabulary entries, and recording user selections
 * made through the autocomplete feature.
 *
 * @see WordTransition
 * @see PostgresTransitionRepo
 */

public interface TransitionRepository {
    /**
     * Retrieves possible next words based on the current word.
     *
     * Each transition contains a candidate next word and its
     * frequency of occurrence in the corpus. 
     *
     * @param word the current word used to retrieve transitions
     * @return a list of possible next-word transitions and their
     *         frequencies, or an empty list if none exist
     */
    List<WordTransition> getTransitions(String word);

    /**
     * Checks whether a word exists in the vocabulary.
     *
     * @param word the word to search for
     * @return true if the word exists, false otherwise
     */
    boolean wordExists(String word);

     /**
     * Adds a new word to the vocabulary if it does not already exist.
     *
     * This method supports the autocomplete component by allowing
     * previously unknown words to be stored in the vocabulary.
     *
     * @param word the word to add to the vocabulary
     */
    void addWord(String word);

    /**
     * Records a user's selection of an autocomplete suggestion.
     *
     * The method tracks the relationship between the previous
     * word and the selected next word. Implementations may update
     * a selection counter to record how frequently a 
     * suggestion is chosen.
     *
     * @param previousWord the word preceding the selected suggestion
     * @param nextWord the word selected by the user
     */
    void recordSelection(String previousWord, String nextWord);

}
