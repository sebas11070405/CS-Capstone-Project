package com.sentencebuilder.repository;

import com.sentencebuilder.model.WordTransition;

import java.util.List;

public class PostgresTransitionRepo implements TransitionRepository{

    @Override
    public List<WordTransition> getTransitions(String word) {
        return List.of();
    }

    @Override
    public boolean wordExists(String word) {
        return false;
    }

    @Override
    public void addWord(String word) {

    }

    @Override
    public void recordSelection(String previousWord, String nextWord) {

    }
}
