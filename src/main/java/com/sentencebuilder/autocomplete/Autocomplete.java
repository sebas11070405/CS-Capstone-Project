/*
 * Suggests the next word for the sentence a user is typing, and records which suggestion they pick.
 * Following the assignment, it never guesses a word from its first letters. Suggestions only appear
 * once the user has finished a word by typing a space, a comma, or a period, which is what
 * getSuggestionsForText checks. completedWord pulls out the finished word so the UI can also tell
 * which word to record as the user's choice.
 *
 * Works with any TransitionRepository, so tests can use FakeAutocompletRepo and the app can use
 * PostgresTransitionRepo (which needs DB_URL, DB_USER, and DB_PASSWORD in .env).
 */

package com.sentencebuilder.autocomplete;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.tokenizer.Tokenizer;

import java.util.*;

public class Autocomplete {

    /**
     * Constructs an Autocomplete instance using the provided repository.
     *
     * @param repo repository used for retrieving word transitions and recording user selections
     */
    private final TransitionRepository repo;

    /** Repository used to retrieve transitions and record selections. */
    public Autocomplete(TransitionRepository repo){

        this.repo = repo;
    }

    /**
     * Finds the word the user has just finished typing. A word is finished when the text ends with
     * a space, a comma, or a period, so "the dog " gives "dog" and "the dog," gives "dog", but
     * "the dog" gives nothing because the user may still be typing. Several breaks in a row are
     * fine ("dog, "), and the word is cleaned up the same way imported text is, so "Dog" becomes
     * "dog". Takes everything typed so far and returns the finished word, or empty if the last word
     * isn't finished or isn't a valid word (such as a number).
     */
    public static Optional<String> completedWord(String typedText) {
        if (typedText == null || typedText.isEmpty() || !isWordBreak(typedText.charAt(typedText.length() - 1))) {
            return Optional.empty();
        }

        // Skip the breaks at the end, so "dog, " ends right after the g
        int wordEnd = typedText.length();
        while (wordEnd > 0 && isWordBreak(typedText.charAt(wordEnd - 1))) {
            wordEnd--;
        }

        // Walk back to the break before the word to find where it starts
        int wordStart = wordEnd;
        while (wordStart > 0 && !isWordBreak(typedText.charAt(wordStart - 1))) {
            wordStart--;
        }

        // Tokenizer cleans the word the same way the importer does, and returns null if it isn't one
        return Optional.ofNullable(Tokenizer.normalizeWord(typedText.substring(wordStart, wordEnd)));
    }

    /**
     * Gives the next-word suggestions for the text the user has typed so far. Takes everything typed
     * and the most suggestions to return. Returns an empty list while the user is still in the
     * middle of a word, or when the finished word has no stored followers, so the UI can call this
     * on every keystroke and only show choices after a space, comma, or period.
     */
    public List<WordTransition> getSuggestionsForText(String typedText, int limit) {
        Optional<String> finishedWord = completedWord(typedText);
        if (finishedWord.isEmpty()) {
            return List.of();
        }
        return getSuggestions(finishedWord.get(), limit);
    }

    /** Says whether a character ends a word: any whitespace (space, tab, new line), a comma, or a period. */
    private static boolean isWordBreak(char character) {
        return Character.isWhitespace(character) || character == ',' || character == '.';
    }

    /**
     * Retrieves the highest-ranked next-word suggestions.
     *
     * Suggestions are ranked by the sum of their occurrence
     * frequency and user selection count. The number of suggestions
     * returned is limited by the specified value.
     *
     * @param previousWord the word preceding the suggested next word
     * @param limit maximum number of suggestions to return
     * @return a list of ranked word transitions, or an empty list if no suggestions are available
     */
    public List<WordTransition> getSuggestions(String previousWord, int limit){
        if (limit <= 0 || previousWord == null || previousWord.isBlank()){
            return List.of();
        }

        previousWord = previousWord.trim().toLowerCase();

        // Unknown words have no stored transitions
        if(!repo.wordExists(previousWord)){
            return List.of();
        }

        List<WordTransition> transitions = repo.getTransitions(previousWord);

        if (transitions == null || transitions.isEmpty()) {
            return List.of();
        }

        /**
         * Claude prompt: Implement a Java operation that ranks next-word suggestions by the sum of their occurrence frequency
         *  and user_selected count. sort them in descending order, and return only the top requested number of suggestions given limit value.
         */

        return transitions.stream().sorted(Comparator.comparingLong(
                (WordTransition t) -> (long) t.getOccurrences() + t.getUserSelected()).reversed()).limit(limit).toList();
    }

    /**
     * Records a user-selected word transition when it already exists.
     *
     * The method checks that both words exist in the database.
     * If either word is unknown, no database modification is made.
     *
     * The repository is responsible for updating the user_selected
     * count of an existing bigram. Missing bigram relationships
     * are not created.
     *
     * @param previousWord the word preceding the selected word
     * @param chosenWord the word entered or selected by the user
     * @throws IllegalArgumentException if either word is null or blank
     */
    public void recordChoice(String previousWord, String chosenWord){
        if(previousWord == null || previousWord.isBlank() || chosenWord == null || chosenWord.isBlank()){
            throw new IllegalArgumentException(
                    "Previous word and chosen word cannot be blank."
            );
        }

        previousWord = previousWord.trim().toLowerCase();
        chosenWord = chosenWord.trim().toLowerCase();

        // Don't automatically insert unknown words into the database
        if(!repo.wordExists(previousWord) || !repo.wordExists(chosenWord)){
            return;
        }

        // Repository handles inserting new bigrams or incrementing user_selected counts
        repo.recordSelection(previousWord, chosenWord);
    }
}