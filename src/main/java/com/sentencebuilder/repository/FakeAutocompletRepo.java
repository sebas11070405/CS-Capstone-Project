package com.sentencebuilder.repository;
import com.sentencebuilder.model.WordTransition;
import java.util.*;

public class FakeAutocompletRepo implements TransitionRepository {

    private final Map<String, List<WordTransition>> transitions = new HashMap<>();

    private final Set<String> words = new HashSet<>();

    public FakeAutocompletRepo() {
        words.addAll(List.of("the", "boy", "dog", "girl", "elephant"));

        transitions.put("the", new ArrayList<>(List.of(
                new WordTransition("boy", 10, 2),
                new WordTransition("dog", 8, 7),
                new WordTransition("girl", 5, 1)
        )));
    }

    @Override
    public List<WordTransition> getTransitions(String word) {
        return new ArrayList<>(transitions.getOrDefault(word, List.of()));
    }

    @Override
    public boolean wordExists(String word) {
        return words.contains(word);
    }

    @Override
    public void addWord(String word) {
        words.add(word);
    }

    @Override
    public void recordSelection(String previousWord, String nextWord) {
        List<WordTransition> existing = transitions.get(previousWord);

        if (existing == null) {
            return;
        }

        for (int i = 0; i < existing.size(); i++){
            WordTransition transitions = existing.get(i);

            if(transitions.getNextWord().equals(nextWord)){
                existing.set(i, new WordTransition(nextWord, transitions.getFrequency(), transitions.getUserSelected() + 1));

                return;
            }
        }
    }
}
