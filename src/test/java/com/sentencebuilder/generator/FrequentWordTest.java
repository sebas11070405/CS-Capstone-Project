package com.sentencebuilder.generator;

import com.sentencebuilder.repository.FakeData;
import com.sentencebuilder.repository.PostgresTransitionRepo;
import com.sentencebuilder.repository.TransitionRepository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the FrequentWordGenerator class.
 *
 * These tests verify that the Most Frequent Word generator can
 * generate sentences using transitions retrieved from the PostgreSQL
 * database and handle cases where no transitions are available.
 *
 * @see FrequentWordGenerator
 * @see PostgresTransitionRepo
 */
public class FrequentWordTest {

    /**
     * Tests sentence generation using word transitions from PostgreSQL.
     * Verifies that the generated sentence is not null or empty.
     */
    @Test
    public void testGetTransitions() {
        TransitionRepository repo = new PostgresTransitionRepo();

        FrequentWordGenerator generator = new FrequentWordGenerator(10, repo, "the");


        String sentence = generator.generateSentence();

        assertNotNull(sentence);
        assertFalse(sentence.isEmpty());

        System.out.println("Sentence: " + sentence);

    }

    /**
     * Tests sentence generation when no word transitions are available.
     *
     * Uses a starting word that is not expected to exist in the
     * database. When no transitions are found, the generator should
     * stop and return only the starting word.
     *
     * Verifies that the generator handles missing transitions
     * without generating additional words.
     */
    @Test
    public void testNoTransitions(){
        TransitionRepository repo = new PostgresTransitionRepo();

        FrequentWordGenerator generator = new FrequentWordGenerator(5, repo, "abcde");

        String sentence = generator.generateSentence();

        assertEquals("abcde", sentence);

        System.out.println("Sentence: " + sentence);
    }

}
