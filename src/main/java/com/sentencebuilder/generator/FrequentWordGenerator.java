package com.sentencebuilder.generator;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;

import java.util.*;

/**
 * Generates sentences using the Most Frequent Word algorithm
 *
 * This algorithm predicts the next word by selecting the word with the highest transition frequency
 * from the current word.
 *
 * Word transitions are retrieved through the TransitionRepository interface, allowing the generator to
 * work with either hardcoded test data or transitions stored in database.
 *
 * Sentence generation continues until the maximum word count is reached or no additional transitions are available.
 *
 * @see AbstractGenerator
 * @see TransitionRepository
 * @see WordTransition
 */

public class FrequentWordGenerator extends AbstractGenerator {

    /** Repository used to retrieve word transitions. */
    private final TransitionRepository repo;
    /** Initial word to begin sentence generation. */
    private final String startWord;

    /**
     * Constructs FrequentWordGenerator with the specified maximum length, transition repository, and starting word.
     *
     * @param maxWords maximum number of words allowed in the generated sentence
     * @param repo repository containing word transitions and their frequencies
     * @param startWord initial word used to begin sentence generation
     */
    public FrequentWordGenerator(int maxWords, TransitionRepository repo, String startWord) {

        super(maxWords);

        this.repo = repo;
        this.startWord = startWord;
    }

    /**
     * Generates a sentence by selecting the most frequent next word based on the current word.
     *
     * Algorithm begins with the specified starting word and retrieves its possible transitions from the
     * repository. The transitions with the highest frequency is selected and appended to the sentence.
     *
     * A hashset is used to keep track of which word pairs have already appeared in a generated sentence,
     * which prevents the sentence generated from cycling the same words.
     *
     * @return generated sentence containing up to maxWords words
     */
    @Override
    public String generateSentence() {
        Set<String> usedPairs = new HashSet<>();

        StringBuilder sentence = new StringBuilder(startWord);

        String currentWord = startWord;

        for (int i = 1; i < maxWords; i++){
            List<WordTransition> transitions = repo.getTransitions(currentWord);

            /** Stop generation if no transitions are available */
            if(transitions == null || transitions.isEmpty()){
                break;
            }

            String nextWord = selectNextWord(currentWord, transitions, usedPairs);

            /** Stop generation if no valid next word is available */
            if(nextWord == null){
                break;
            }

            /** Record the selected word pair **/
            usedPairs.add(currentWord + "->" + nextWord);

            /** Append the selected word and update the current word */
            sentence.append(" ").append(nextWord);
            currentWord = nextWord;
        }

        return sentence.toString();

    }

    /**
     * Selects the most frequently occurring next word from a list of possible word transitions.
     *
     * Each transition contains a candidate next word and its frequency of occurrence in the corpus.
     * The method iterates through all transitions and selects the candidate with the highest frequency.
     *
     * If multiple transitions share the highest frequency, the first encountered transition is selected.
     * Ignores transitions that have already appeared in the sentence.
     *
     * @param transitions list of possible next-word transitions and their corresponding frequencies
     * @return next word with the highest frequency or null
     */
    private String selectNextWord(String currentWord, List<WordTransition> transitions, Set<String> usedPairs){
        if(transitions == null || transitions.isEmpty()){
            return null;
        }

        WordTransition mostFrequent = null;

        /** Find transition with the highest frequency */
        for (WordTransition transition : transitions){

            String nextWord = transition.getNextWord();
            String pair = currentWord + "->" + nextWord;

            /** Skips previously used word pairs **/
            if(usedPairs.contains(pair)){
                continue;
            }

            if(mostFrequent == null || transition.getOccurrences() > mostFrequent.getOccurrences()){
                mostFrequent = transition;
            }
        }
        /** Return null if every transition has already been used **/
        if (mostFrequent == null){
            return null;
        }

        return mostFrequent.getNextWord();
    }
}
