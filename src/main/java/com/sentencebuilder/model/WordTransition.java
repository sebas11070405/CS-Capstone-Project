package com.sentencebuilder.model;

/**
 * Represents a possible next-word transition in the Sentence Builder application.
 *
 * Each WordTransition object stores a candidate next word, its occurrences
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
    private final int occurrences;
    private final int userSelected;

    /**
     * Constructs a WordTransition with a next word and its occurrences.
     *
     * The user selection count is initialized to zero. This constructor
     * is primarily used by sentence generators that only require
     * occurrence frequencies.
     *
     * @param nextWord the possible next word
     * @param occurrences the number of times the transition occurs in the corpus
     */
    public WordTransition(String nextWord, int occurrences){
        this(nextWord, occurrences, 0);
    }

    /**
     * Constructs a WordTransition with a next word, occurrence occurrences,
     * and autocomplete selection count.
     *
     * @param nextWord the possible next word
     * @param occurrences the number of times the transition occurs in the corpus
     * @param userSelected the number of times users have selected this transition
     */
    public WordTransition(String nextWord, int occurrences, int userSelected) {
        this.nextWord = nextWord;
        this.occurrences = occurrences;
        this.userSelected = userSelected;
    }


    public String getNextWord() {

        return nextWord;
    }

    public int getOccurrences() {

        return occurrences;
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
