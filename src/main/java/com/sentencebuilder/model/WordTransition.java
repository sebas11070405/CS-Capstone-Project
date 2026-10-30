package com.sentencebuilder.model;

/**
 * Represents a possible next-word transition in the Sentence Builder application.
 *
 * Each WordTransition object stores a candidate next word, its frequency
 * of occurrence in the training corpus, and the number of times users have
 * selected it through the autocomplete feature.
 *
 * Transition data can be retrieved from the PostgreSQL database and
 * converted into WordTransition objects for use by the sentence generation
 * algorithms.
 *
 */
public class WordTransition {

    /** The candidate word that may follow the current word. */
    private final String nextWord;

    /** Number of times the word transition occurs in the training corpus */
    private final int frequency;

    /** Number of times users have selected this transition through autocomplete. */
    private final int userSelected;

    /**
     * Constructs a WordTransition with a next word and its frequency.
     *
     * The user selection count is initialized to zero. This constructor
     * is primarily used by sentence generators that only require
     * occurrence frequencies.
     *
     * @param nextWord the possible next word
     * @param frequency the number of times the transition occurs in the corpus
     */
    public WordTransition(String nextWord, int frequency){
        this(nextWord, frequency, 0);
    }

    /**
     * Constructs a WordTransition with a next word, occurrence frequency,
     * and autocomplete selection count.
     *
     * @param nextWord the possible next word
     * @param frequency the number of times the transition occurs in the corpus
     * @param userSelected the number of times users have selected this transition
     */
    public WordTransition(String nextWord, int frequency, int userSelected) {
        this.nextWord = nextWord;
        this.frequency = frequency;
        this.userSelected = userSelected;
    }
   /**
     * Returns the candidate next word.
     *
     * @return the next word associated with this transition
     */
    public String getNextWord() {

        return nextWord;
    }
   /**
     * Returns the occurrence frequency of the word transition.
     *
     * @return the number of times the transition occurs in the corpus
     */
    public int getFrequency() {

        return frequency;
    }
   /**
     * Returns the number of times users have selected this transition
     * through the autocomplete feature.
     *
     * @return the autocomplete selection count
     */
    public int getUserSelected(){
        return userSelected;
    }
}
