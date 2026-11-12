/*
 * Tests that Autocomplete only offers next words once the user has finished a word with a space,
 * a comma, or a period. Uses FakeAutocompletRepo, so it needs no database or .env.
 */

package com.sentencebuilder.autocomplete;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.FakeAutocompletRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutocompleteTypedTextTest {

    private Autocomplete autocomplete;

    @BeforeEach
    void setUp() {
        autocomplete = new Autocomplete(new FakeAutocompletRepo());
    }

    @Test
    void aWordIsFinishedByASpaceCommaOrPeriod() {
        assertEquals(Optional.of("the"), Autocomplete.completedWord("the "));
        assertEquals(Optional.of("the"), Autocomplete.completedWord("the,"));
        assertEquals(Optional.of("the"), Autocomplete.completedWord("the."));
    }

    @Test
    void aWordStillBeingTypedIsNotFinished() {
        assertEquals(Optional.empty(), Autocomplete.completedWord("the"));
        assertEquals(Optional.empty(), Autocomplete.completedWord("the d"));
        assertTrue(autocomplete.getSuggestionsForText("the", 3).isEmpty());
        assertTrue(autocomplete.getSuggestionsForText("the d", 3).isEmpty());
    }

    @Test
    void usesTheLastWordOfALongerSentence() {
        assertEquals(Optional.of("dog"), Autocomplete.completedWord("I saw the big dog "));
        assertEquals(Optional.of("dog"), Autocomplete.completedWord("Well, the dog,"));
    }

    @Test
    void severalBreaksInARowStillFindTheWord() {
        assertEquals(Optional.of("the"), Autocomplete.completedWord("the , . "));
        assertEquals(Optional.of("the"), Autocomplete.completedWord("wait...the..."));
    }

    @Test
    void tabsAndNewLinesCountAsSpaces() {
        assertEquals(Optional.of("the"), Autocomplete.completedWord("the\t"));
        assertEquals(Optional.of("the"), Autocomplete.completedWord("the\n"));
    }

    @Test
    void wordsAreCleanedTheSameWayAsImportedText() {
        assertEquals(Optional.of("the"), Autocomplete.completedWord("The "));
        assertEquals(Optional.of("the"), Autocomplete.completedWord("\"the\", "));
        assertEquals(Optional.of("don't"), Autocomplete.completedWord("don't "));
    }

    @Test
    void nothingTypedOrNotAWordGivesNothing() {
        assertEquals(Optional.empty(), Autocomplete.completedWord(null));
        assertEquals(Optional.empty(), Autocomplete.completedWord(""));
        assertEquals(Optional.empty(), Autocomplete.completedWord("   "));
        assertEquals(Optional.empty(), Autocomplete.completedWord(", . "));
        assertEquals(Optional.empty(), Autocomplete.completedWord("the 42 "));
    }

    // Ranking score of each suggestion (occurrences plus user selections), so tests compare order without hard-coding words
    private static List<Long> scores(List<WordTransition> suggestions) {
        return suggestions.stream().map(transition -> (long) transition.getOccurrences() + transition.getUserSelected()).toList();
    }

    @Test
    void suggestionsAppearAfterEachKindOfBreakWithTheSameRanking() {
        List<Long> expected = scores(autocomplete.getSuggestions("the", 3));
        assertFalse(expected.isEmpty());
        for (String typed : List.of("the ", "the,", "the.", "Is it the ")) {
            assertEquals(expected, scores(autocomplete.getSuggestionsForText(typed, 3)));
        }
    }

    @Test
    void suggestionsRespectTheLimit() {
        assertEquals(1, autocomplete.getSuggestionsForText("the ", 1).size());
        assertTrue(autocomplete.getSuggestionsForText("the ", 0).isEmpty());
    }

    @Test
    void aFinishedWordWithNoFollowersGivesNoSuggestions() {
        assertTrue(autocomplete.getSuggestionsForText("elephnat ", 3).isEmpty());
        assertTrue(autocomplete.getSuggestionsForText("girl ", 3).isEmpty());
    }
}