package com.sentencebuilder.generator;

import com.sentencebuilder.repository.FakeData;
import com.sentencebuilder.repository.PostgresTransitionRepo;
import com.sentencebuilder.repository.TransitionRepository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


public class FrequentWordTest {

    @Test
    public void testGetTransitions() {
        TransitionRepository repo = new PostgresTransitionRepo();

        FrequentWordGenerator generator = new FrequentWordGenerator(3, repo, "the");


        String sentence = generator.generateSentence();

        assertNotNull(sentence);
        assertFalse(sentence.isEmpty());

        System.out.println("Sentence: " + sentence);

    }

    @Test
    public void testNoTransitions(){
        TransitionRepository repo = new PostgresTransitionRepo();

        FrequentWordGenerator generator = new FrequentWordGenerator(5, repo, "abcde");

        String sentence = generator.generateSentence();

        assertEquals("abcde", sentence);

        System.out.println("Sentence: " + sentence);
    }

}
