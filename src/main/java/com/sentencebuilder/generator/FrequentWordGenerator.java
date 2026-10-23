package com.sentencebuilder.generator;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;

import java.util.List;

/**
 *
 * This class generates sentences using a frequent word generation strategy.
 * It extends the AbstractGenerator class and implements the Generator interface.
 */

public class FrequentWordGenerator extends AbstractGenerator {

    private final TransitionRepository repo;
    private final String startWord;
    // Constructor for FrequentWordGenerator, takes in the maximum number of words to generate
    public FrequentWordGenerator(int maxWords, TransitionRepository repo, String startWord) {

        super(maxWords);

        this.repo = repo;
        this.startWord = startWord;
    }

    // Implementation of the generate method from the Generator interface
    @Override
    public String generateSentence() {

        StringBuilder sentence = new StringBuilder(startWord);

        String currentWord = startWord;

        for (int i = 1; i < maxWords; i++){
            List<WordTransition> transitions = repo.getTransitions(currentWord);

            if(transitions.isEmpty() || transitions == null){
                break;
            }

            String nextWord = selectNextWord(transitions);


            if(nextWord == null){
                break;
            }

            sentence.append(" ").append(nextWord);
            currentWord = nextWord;
        }

        return sentence.toString();

    }

    private String selectNextWord(List<WordTransition> transitions){
        if(transitions == null || transitions.isEmpty()){
            return null;
        }

        WordTransition mostFrequent = transitions.get(0);

        for (WordTransition transition : transitions){
            if(transition.getFrequency() > mostFrequent.getFrequency()){
                mostFrequent = transition;
            }
        }

        return mostFrequent.getNextWord();
    }
}