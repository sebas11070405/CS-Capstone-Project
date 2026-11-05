package com.sentencebuilder.repository;
import com.sentencebuilder.model.WordTransition;
import java.util.*;

public class FakeTrigramData implements TrigramRepo{
    // Store transitions with HashMaps
    // Key: two consecutive words
    // Associated value: list of possible next words
    private final Map<String, List<WordTransition>> transitions = new HashMap<>();

    public FakeTrigramData(){

        transitions.put("the cat", Arrays.asList(
                new WordTransition("sat", 8),
                new WordTransition("ran", 3),
                new WordTransition("jumped", 1)
        ));

        transitions.put("cat sat", Arrays.asList(
                new WordTransition("on", 5),
                new WordTransition("under", 2)
        ));

        transitions.put("sat on", Arrays.asList(
                new WordTransition("the", 6)
        ));
    }

    @Override
    public List<WordTransition> getTrigramTransitions(
            String previousWord,
            String currentWord
    ){
        String key = previousWord + " " + currentWord;

        return transitions.getOrDefault(key, Collections.emptyList());
    }
}
