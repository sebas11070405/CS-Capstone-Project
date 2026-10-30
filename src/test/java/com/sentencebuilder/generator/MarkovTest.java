package com.sentencebuilder.generator;

import com.sentencebuilder.repository.PostgresTransitionRepo;
import com.sentencebuilder.repository.TransitionRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the MarkovGenerator class.
 *
 * These tests verify that the Markov generator can generate
 * sentences using word transitions retrieved from the PostgreSQL
 * database and handle cases where no transitions are available.
 *
 * @see MarkovGenerator
 * @see PostgresTransitionRepo
 */
public class MarkovTest {

    /**
     * Tests sentence generation using transitions from PostgreSQL.
     * Verifies that the generated sentence is not null or empty.
     */
    @Test
    public void testGetTransitions() {
        TransitionRepository repo = new PostgresTransitionRepo();

        MarkovGenerator generator = new MarkovGenerator(3, repo, "the");


            String sentence = generator.generateSentence();

            assertNotNull(sentence);
            assertFalse(sentence.isEmpty());

            System.out.println("Sentence: " + sentence);


    }

    /**
     * Tests sentence generation when no word transitions exist.
     *
     * Uses a starting word that is not expected to exist
     * in the database. When no transitions are available,
     * the generator should stop and return only the starting word.
     *
     * Verifies that the generator handles missing transitions
     * without throwing an exception.
     */
    @Test
    public void testNoTransitions() {
        TransitionRepository repo = new PostgresTransitionRepo();

        MarkovGenerator generator = new MarkovGenerator(5, repo, "abcde");

        String sentence = generator.generateSentence();

        assertEquals("abcde", sentence);

        System.out.println("Sentence: " + sentence);
    }
}
