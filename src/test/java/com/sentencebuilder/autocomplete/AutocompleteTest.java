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
        String previousWord = "the";
        String chosenWord = "elephnat";

        // Verify that the word does not exist initially
        assertFalse(repo.wordExists("elephnat"));

        List<WordTransition> suggestions = autocomplete.getSuggestions("elephnat", 3);

        // Unknown words produce no suggestions
        assertTrue(suggestions.isEmpty());

        // Record choice
        autocomplete.recordChoice("the", "elephnat");

        List<WordTransition> transitions = repo.getTransitions(previousWord);

        WordTransition recorded = transitions.stream().filter(t -> t.getNextWord().equals(chosenWord)).findFirst().orElseThrow();

        assertEquals(0, recorded.getOccurrences());
        assertEquals(1, recorded.getUserSelected());
    }

    @Test
    public void testUnknownPreviousWord() {

        autocomplete.recordChoice("unknown", "boy");

        assertTrue(repo.wordExists("unknown"));

        WordTransition recorded =
                repo.getTransitions("unknown").stream()
                        .filter(t -> t.getNextWord().equals("boy"))
                        .findFirst()
                        .orElseThrow();

        assertEquals(1, recorded.getUserSelected());
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

   @Test
    public void testNormalization() {
        autocomplete.recordChoice(" THE ", " BOY ");

        List<WordTransition> transitions = repo.getTransitions("the");

        WordTransition boy = transitions.stream().filter(t -> t.getNextWord().equals("boy")).findFirst().orElseThrow();

        assertEquals(3, boy.getUserSelected());
   }
}
