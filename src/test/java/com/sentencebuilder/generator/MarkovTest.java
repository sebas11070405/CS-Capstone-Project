package com.sentencebuilder.generator;

import com.sentencebuilder.repository.FakeData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MarkovTest {

    @Test
    public void testGetTransitions() {
        FakeData fakeData = new FakeData();

        MarkovGenerator generator = new MarkovGenerator(3, fakeData, "the");


            String sentence = generator.generateSentence();

            assertNotNull(sentence);
            assertFalse(sentence.isEmpty());

            System.out.println("Sentence: " + sentence);


    }

    @Test
    public void testNoTransitions() {
        FakeData fakeData = new FakeData();

        MarkovGenerator generator = new MarkovGenerator(5, fakeData, "Unknown");

        String sentence = generator.generateSentence();

        assertEquals("Unknown", sentence);

        System.out.println("Sentence: " + sentence);
    }
}