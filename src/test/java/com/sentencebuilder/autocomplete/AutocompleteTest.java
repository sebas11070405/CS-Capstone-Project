package com.sentencebuilder.autocomplete;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.FakeAutocompleteRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
public class AutocompleteTest {

    private FakeAutocompleteRepo repo;
    private Autocomplete autocomplete;

    @BeforeEach
    public void setUp(){
        repo = new FakeAutocompleteRepo();
        autocomplete = new Autocomplete(repo);
    }
    @Test
    public void testSuggestions(){
        List<WordTransition> suggestions = autocomplete.getSuggestions("the", 2);

        System.out.println("Previous Word: the");
        System.out.println("Rank | Word | Occurrences | User Selected | Score");

        int rank = 1;
        for (WordTransition transition : suggestions) {
            int score = transition.getOccurrences()
                    + transition.getUserSelected();

            System.out.printf("%d | %s | %d | %d | %d%n",
                    rank++,
                    transition.getNextWord(),
                    transition.getOccurrences(),
                    transition.getUserSelected(),
                    score
            );
        }

        assertEquals(2, suggestions.size());

        assertEquals("dog", suggestions.get(0).getNextWord());
        assertEquals("boy", suggestions.get(1).getNextWord());

    }

    @Test
    public void testLimit(){
        List<WordTransition> suggestions = autocomplete.getSuggestions("the", 2);

        assertEquals(2, suggestions.size());
    }

    @Test
    public void testUnknownWord(){
        List<WordTransition> suggestions = autocomplete.getSuggestions("elephnat", 3);

        assertTrue(suggestions.isEmpty());

        autocomplete.recordChoice("the", "elephnat");

        System.out.println("\nUnknown Word Test");
        System.out.println("Input: elephnat");
        System.out.println("Suggestions found: " + suggestions.size());
        System.out.println("Word exists in database: " + repo.wordExists("elephnat"));

        assertFalse(repo.wordExists("elephnat"));
    }

    @Test
    public void testRecordChoice(){

        System.out.println("\nRecording User Selection");
        List<WordTransition> before = repo.getTransitions("the");

        for (WordTransition t : before) {
            if (t.getNextWord().equals("boy")) {
                System.out.println("Before selection: " +
                        t.getUserSelected());
            }
        }
        autocomplete.recordChoice("the", "boy");

        List<WordTransition> transitions = repo.getTransitions("the");

        WordTransition boy = transitions.stream().filter(t -> t.getNextWord().equals("boy")).findFirst().orElseThrow();

        System.out.println("After selection: " + boy.getUserSelected());

        assertEquals(3, boy.getUserSelected());
    }

    @Test
    public void testInvalidLimit(){
        assertTrue(autocomplete.getSuggestions("the", 0).isEmpty() );
   }
}
