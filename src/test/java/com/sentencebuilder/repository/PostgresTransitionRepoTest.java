package com.sentencebuilder.repository;

import com.sentencebuilder.model.WordTransition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PostgresTransitionRepoTest {

    @Test
    public void testGetTransitions() {

        PostgresTransitionRepo repo = new PostgresTransitionRepo();

        List<WordTransition> transitions = repo.getTransitions("the");

        assertNotNull(transitions);

        System.out.println("Number of transitions: " + transitions.size());

        for (WordTransition transition : transitions) {
            System.out.println(
                    transition.getNextWord() + ": " +
                            transition.getOccurrences()
            );
        }
    }

    @Test
    public void testWordExists(){

        PostgresTransitionRepo repo = new PostgresTransitionRepo();

        boolean exists = repo.wordExists("fawfgjiobo");

        assertFalse(exists, "The word should not exist in an empty database");

        System.out.println("Word exists: " + exists);
    }
}