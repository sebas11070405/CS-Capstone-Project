package com.sentencebuilder.autocomplete;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;

import java.util.Comparator;
import java.util.List;

public class Autocomplete {

    private final TransitionRepository repo;


    // Accept any implementation of TransitionRepository
    // Temporary: passes FakeData
    public Autocomplete(TransitionRepository repo){
        this.repo = repo;
    }


    // ask repository for list of possible next words (getSuggestions())
    public List<WordTransition> getSuggestions(String previousWord, int limit){
        if (limit <= 0){
            return List.of();
        }

        List<WordTransition> transitions = repo.getTransitions(previousWord);

        // order candidates from highest to lowest frequency (sorted())
        // restricts the number of suggestions returned (limit(limit))
        // collect results into a list
        return transitions.stream().sorted(Comparator.comparingInt(WordTransition::getFrequency).reversed()).limit(limit).toList();
    }

    public void recordChoice(String previousWord, String chosenWord){
        if(previousWord == null || previousWord.isBlank() || chosenWord == null || chosenWord.isBlank()){
            throw new IllegalArgumentException(
                    "Previous word and chosen word cannot be blank."
            );
        }

        previousWord = previousWord.toLowerCase();
        chosenWord = chosenWord.toLowerCase();

        if(!repo.wordExists(previousWord)){
            repo.addWord(previousWord);
        }

        if(!repo.wordExists(chosenWord)){
            repo.addWord(chosenWord);
        }

        repo.recordSelection(previousWord, chosenWord);
    }
}
