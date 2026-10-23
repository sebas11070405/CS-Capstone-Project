package com.sentencebuilder.repository;

import com.sentencebuilder.model.WordTransition;
import java.util.List;

// It is an interface that doesn't need to know how the data is stored.
// Data access abstraction layer

public interface TransitionRepository {


    // Retrieve possible next words
    List<WordTransition> getTransitions(String word);

    // Check whether a word exists in the vocabulary
    boolean wordExists(String word);

    // Add a word to the vocabulary if it does not exist
    void addWord(String word);

    // Record user's choice of next word
    void recordSelection(String previousWord, String nextWord);

}