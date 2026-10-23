package com.sentencebuilder.generator;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;

import java.util.List;
import java.util.Random;

/**
 *
 * This class generates sentences using a Markov chain generation strategy.
 * It extends the AbstractGenerator class and inherits the common functionality for generating sentences.
 */

public class MarkovGenerator extends AbstractGenerator {

    // Necessary fields for Markov: The repository for retrieving word transitions, 
    // a random number generator for selecting the next word, and the starting word for the sentence generation.
    private final TransitionRepository repo;
    private final Random random;
    private final String startWord;

    // Constructor for MarkovGenerator, takes in the max sentence length, the transition repository, 
    // the starting word, and a random number generator.
    public MarkovGenerator(int maxWords, TransitionRepository repo, String startWord) {
        // Pass maxWords to AbstractGenerator.
        super(maxWords);
        
        this.repo = repo;
        this.random = new Random();
        this.startWord = startWord;
    }

    // Implementation of the generate method from the Generator interface
    @Override
    public String generateSentence() {

        StringBuilder sentenceBuilder = new StringBuilder(startWord);
        
        // Take the starting word
        String currentWord = startWord;

        // Generate words until we reach the max sentence length (maxWords)
        for (int i = 1; i < maxWords; i++) {
            
            // Retrieve the list of possible transitions for the current word
            // WordTransition is a model class that holds the next word and its frequency.
            List<WordTransition> transitions = repo.getTransitions(currentWord);

            System.out.println("Current word: " + currentWord);

            if (transitions.isEmpty()) {
                break; // No transitions available. Stop
            }
            // Else: Select the next word based on the frequencies
            String nextWord = selectNextWord(transitions);

            System.out.println("Selected next word: " + nextWord);
            
            if (nextWord == null) {
                break; // No valid next word found
            }
            // Update/append the sentence with the selected next word
            sentenceBuilder.append(" ").append(nextWord);

            // Move to the next word for the next iteration
            currentWord = nextWord;
        }

        return sentenceBuilder.toString();
        
}

// AI Prompt: Write a selectNextWord() method that selects the next word from a list of word transitions using weighted
// random selection based on each word's frequency.

// Method to decide which word comes next based on the list of transitions.
private String selectNextWord(List<WordTransition> transitions) {

        // calculate total frequency to determine the probability distribution.
        int totalFrequency = 0;

        for (WordTransition transition : transitions) {
            totalFrequency += transition.getFrequency();
        }

        if (totalFrequency == 0) {
            return null; // No valid transitions
        }

        // Generate a random value to select the next word
        // Probability of selecting a word is proportional to its frequency
        // Implement the ranges according to the frequencies.
        int randomValue = random.nextInt(totalFrequency);
        int cumulativeFrequency = 0;

        for (WordTransition transition : transitions) {
            cumulativeFrequency += transition.getFrequency();

            System.out.println("Random value: " + randomValue);
            System.out.println("Cumulative Frequency: " + cumulativeFrequency);

            if (randomValue < cumulativeFrequency) {
                return transition.getNextWord();
            }
        }
        return null; // Should not reach here if transitions are valid
    }

}