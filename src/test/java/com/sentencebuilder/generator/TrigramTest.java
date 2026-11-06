package com.sentencebuilder.generator;

import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.repository.TrigramRepo;

import com.sentencebuilder.repository.PostgresTransitionRepo;
import org.junit.jupiter.api.Test;
import com.sentencebuilder.repository.PostgresTrigramRepo;

import static org.junit.jupiter.api.Assertions.*;

public class TrigramTest {

    @Test
    public void testTrigramGenerator(){

        TransitionRepository bigramRepo = new PostgresTransitionRepo();
        TrigramRepo trigramRepo = new PostgresTrigramRepo();

        TrigramBackoffGenerator generator = new TrigramBackoffGenerator(10, bigramRepo, trigramRepo, "sherlock", "holmes");

        String sentence = generator.generateSentence();

        assertNotNull(sentence);
        assertFalse(sentence.isEmpty());

        System.out.println("Sentence: " + sentence);
    }

    @Test
    public void testFallback(){
        TransitionRepository bigramRepo = new PostgresTransitionRepo();
        TrigramRepo trigramRepo = new PostgresTrigramRepo();

        TrigramBackoffGenerator generator = new TrigramBackoffGenerator(5, bigramRepo, trigramRepo, "abcde", "cat");

        // verify that no trigram transitions exist
        assertTrue(trigramRepo.getTrigramTransitions("abcde", "cat").isEmpty(),
                "Expected no trigram transitions");

        // verify that bigram transitions exist
        assertFalse(bigramRepo.getTransitions("cat").isEmpty(), "Expected bigram transitions for cat");

        String sentence = generator.generateSentence();

        assertNotNull(sentence);
        assertTrue(sentence.startsWith("abcde cat"));


        System.out.println("Fallback Sentence: " + sentence);
    }
}
