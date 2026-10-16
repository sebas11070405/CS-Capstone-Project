package generator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import repository.FakeData;

public class MarkovTest {

    @Test
    public void testGetTransitions() {
        FakeData fakeData = new FakeData();

        MarkovGenerator generator = new MarkovGenerator(3, fakeData, "The");


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
    }
}