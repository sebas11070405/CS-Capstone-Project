package com.sentencebuilder.generator;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;

import java.util.List;
import java.util.Random;

/**
 * Generates sentences using a Markov chain-based word generation strategy.
 *
 * This algorithm predicts the next word based on the current word
 * and the frequency of its possible transitions.
 *
 * Each possible next word has a probability of being selected
 * proportional to its frequency of occurrence in the training corpus.
 * Words with higher frequencies are more likely to be selected,
 * but less frequent words still have a chance of being chosen.
 *
 * Word transitions are retrieved through the TransitionRepository
 * interface, allowing the generator to work with either test data
 * or transitions stored in a PostgreSQL database.
 *
 * @see AbstractGenerator
 * @see TransitionRepository
 * @see WordTransition
 */

public class MarkovGenerator extends AbstractGenerator {

    /** Repository used to retrieve possible next-word transitions. */
    private final TransitionRepository repo;

    /** Random number generator used for weighted word selection */
    private final Random random;

    /** Initial word to begin sentence generation */
    private final String startWord;

    /**
     * Constructs a MarkovGenerator with the specified maximum length, transition repository, and starting word.
     *
     * @param maxWords maximum number of words allowed in the generated sentence
     * @param repo repository containing word transitions and their frequencies
     * @param startWord initial word used to begin sentence generation
     */
    public MarkovGenerator(int maxWords, TransitionRepository repo, String startWord) {

        super(maxWords);
        
        this.repo = repo;
        this.random = new Random();
        this.startWord = startWord;
    }

    /**
     * Generates a sentence using probability-weighted word transitions.
     *
     * The algorithm begins with the starting word and retrieves its possible next-word transitions from the repository.
     * A next word is randomly selected based on the frequency distribution of the available transitions.
     *
     * The selected word is appended to the sentence and becomes the current word for the next iteration.
     *
     * @return generated sentence containing up to maxWords words
     */
    @Override
    public String generateSentence() {

        StringBuilder sentenceBuilder = new StringBuilder(startWord);

        String currentWord = startWord;

        // Generate words until we reach the max sentence length (maxWords)
        for (int i = 1; i < maxWords; i++) {
            
            // Retrieve the list of possible transitions for the current word
            // WordTransition is a model class that holds the next word and its frequency.
            List<WordTransition> transitions = repo.getTransitions(currentWord);



            if (transitions.isEmpty()) {
                break; // No transitions available. Stop
            }
            // Else: Select the next word based on the frequencies
            String nextWord = selectNextWord(transitions);


            
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
    /**
     * Selects the next word using weighted random selection.
     *
     * Each transition contains a candidate next word and its
     * frequency of occurrence. The probability of selecting a
     * particular word is calculated as:
     *
     * <pre>
     * P(nextWord) = frequency(nextWord) / totalFrequency
     * </pre>
     *
     * The method first calculates the total frequency of all
     * available transitions. It then generates a random integer
     * within that range and uses cumulative frequencies to determine
     * which word is selected.
     *
     * Higher-frequency transitions occupy larger portions of
     * the selection range, making them more likely to be chosen.
     *
     * Claude Prompt: Write a selectNextWord() method that selects the next word from a list of
     * word transitions using weighted random selection based on each word's frequency.
     *
     * @param transitions list of possible next-word transitions and their corresponding frequencies
     * @return a randomly selected next word based on transition frequencies, or null if no valid transition exists
     */
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


            if (randomValue < cumulativeFrequency) {
                return transition.getNextWord();
            }
        }
        return null; // Should not reach here if transitions are valid
    }

}