package com.sentencebuilder.autocomplete;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;

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
                (WordTransition t) -> (long) t.getFrequency() + t.getUserSelected()).reversed()).limit(limit).toList();
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
