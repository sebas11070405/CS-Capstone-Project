package com.sentencebuilder.repository;

import com.sentencebuilder.model.WordTransition;

import java.util.*;

public class FakeData implements TransitionRepository {

    private final Map<String, Map<String, WordTransition>> data = new HashMap<>();
    private final Set<String> vocabulary = new HashSet<>();

    private void addTransition(String previousWord, String nextWord, int freq){
        data.computeIfAbsent(previousWord, key -> new HashMap<>()).put(nextWord, new WordTransition(nextWord, freq));

        vocabulary.add(previousWord);
        vocabulary.add(nextWord);

    }

    public FakeData(){
        addTransition("the", "cat", 7);
        addTransition("the", "dog", 2);
        addTransition("the", "bird", 1);

        addTransition("cat", "sat", 5);
        addTransition("cat", "ran", 3);
        addTransition("cat", "jumped", 9);

        addTransition("dog", "barked", 6);
        addTransition("dog", "chased", 3);
        addTransition("dog", "played", 1);

        addTransition("bird", "flew", 6);
        addTransition("bird", "sang", 2);
        addTransition("bird", "nested", 4);
    }
    @Override
    public List<WordTransition> getTransitions(String word) {
        Map<String, WordTransition> transitions = data.get(word);

        if(transitions == null){
            return List.of();
        }

        return new ArrayList<>(transitions.values());
    }

    @Override
    public boolean wordExists(String word) {
        return vocabulary.contains(word.toLowerCase());
    }

    @Override
    public void addWord(String word) {
        vocabulary.add(word.toLowerCase());
    }

    @Override
    public void recordSelection(String previousWord, String nextWord) {
        Map<String, WordTransition> transitions = data.computeIfAbsent(previousWord, key -> new HashMap<>());

        WordTransition existing = transitions.get(nextWord);

        if(existing == null){
            transitions.put(nextWord, new WordTransition(nextWord, 0, 1));
        }
        else{
            transitions.put(nextWord, new WordTransition(nextWord, existing.getFrequency(), existing.getUserSelected() + 1));
        }
    }
}