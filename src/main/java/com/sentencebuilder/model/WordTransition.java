package com.sentencebuilder.model;

// This class represents a transition from one word to another in a Markov chain.
// Small model for the data Markov needs to generate sentences. It holds the next word and its frequency of occurrence.
// In SQl, the records would be converted into WordTransition objects for use in the MarkovGenerator.
public class WordTransition {

    private final String nextWord;
    private final int frequency;
    private final int userSelected;

    public WordTransition(String nextWord, int frequency){
        this(nextWord, frequency, 0);
    }
    public WordTransition(String nextWord, int frequency, int userSelected) {
        this.nextWord = nextWord;
        this.frequency = frequency;
        this.userSelected = userSelected;
    }

    public String getNextWord() {

        return nextWord;
    }

    public int getFrequency() {

        return frequency;
    }

    public int getUserSelected(){
        return userSelected;
    }
}