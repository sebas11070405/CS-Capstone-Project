package com.sentencebuilder.generator;

import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.repository.TrigramRepo;

import java.util.*;

/**
 * Generates sentences using a trigram backoff strategy.
 *
 * This algorithm predicts the next word based on the two most
 * recently generated words. It first attempts to retrieve matching
 * trigram transitions from the TrigramRepo repository.
 *
 * If no matching trigram transitions are available, the generator
 * falls back to bigram prediction using the most recent word.
 *
 * Both trigram and bigram predictions use weighted random selection,
 * where words with higher occurrence frequencies have a greater
 * probability of being selected.
 *
 * @see AbstractGenerator
 * @see TransitionRepository
 * @see TrigramRepo
 * @see WordTransition
 */
public class TrigramBackoffGenerator extends AbstractGenerator {

    /** Repository used to retrieve bigram transitions for fallback prediction. */
    private final TransitionRepository bigramRepo;

    /** Repository used to retrieve trigram transitions based on two preceding words. */
    private final TrigramRepo trigramRepo;

    /** First starting word used to initialize sentence generation. */
    private final String word1;

    /** Second starting word used to initialize sentence generation. */
    private final String word2;

    /** Random number generator used for weighted word selection. */
    private final Random random = new Random();

    /**
     * Constructs a TrigramBackoffGenerator with the specified maximum
     * sentence length, repositories, and starting words.
     *
     * The trigram repository is used for predictions based on two
     * preceding words, while the bigram repository provides fallback
     * predictions when no matching trigram transitions exist.
     *
     * @param maxWords maximum number of words allowed in the generated sentence
     * @param bigramRepo repository containing bigram transitions and frequencies
     * @param trigramRepo repository containing trigram transitions and frequencies
     * @param word1 first starting word for sentence generation
     * @param word2 second starting word for sentence generation
     */
    public TrigramBackoffGenerator(int maxWords, TransitionRepository bigramRepo, TrigramRepo trigramRepo, String word1, String word2){
        super(maxWords);

        this.bigramRepo = bigramRepo;
        this.trigramRepo = trigramRepo;
        this.word1 = word1;
        this.word2 = word2;
    }

    /**
     * Generates a sentence using trigram prediction with bigram fallback.
     *
     * The algorithm begins by adding the provided starting words
     * to the sentence. If both starting words are null or blank,
     * an empty string is returned.
     *
     * For each prediction, the generator first attempts to retrieve
     * trigram transitions using the two most recent words. If no matching
     * trigram transitions exist, it retrieves bigram transitions using
     * only the most recent word.
     *
     * The next word is selected using weighted random selection
     * based on the occurrence frequencies of the available transitions.
     * The selected word is appended to the sentence, and the process
     * repeats using the updated word context.
     *
     * Generation stops when the maximum word count is reached,
     * no transitions are available, or no valid next word can be selected.
     *
     * @return generated sentence containing the starting words and
     *         predicted words, or an empty string if no starting
     *         words are provided
     */
    @Override
    public String generateSentence() {

        List<String> words = new ArrayList<>();

        // Add starting words if they are not null or blank.
        if (word1 != null && !word1.isBlank()){
            words.add(word1);
        }

        if (word2 != null && !word2.isBlank()){
            words.add(word2);
        }

        // Return an empty sentence if no valid starting words exist
        if(words.isEmpty()){
            return "";
        }

        // Generate until maximum length is reached
        while (words.size() < maxWords){
            List<WordTransition> transitions = new ArrayList<>();

            int size = words.size();

            // Retrieve the most recently generated word
            String currentWord = words.get(size-1);

            // If two words are available, use trigram prediction
            if (size >= 2){
                String previousWord = words.get(size-2);

                transitions = trigramRepo.getTrigramTransitions(previousWord, currentWord);

            }

            // Else, fallback to bigram prediction
            if (transitions == null || transitions.isEmpty()){
                transitions = bigramRepo.getTransitions(currentWord);
            }

            // Stop if no transitions exist
            if (transitions == null || transitions.isEmpty()){
                break;
            }

            // Use weighted probability to select next word
            String nextWord = selectNextWord(transitions);

            // Stop generation if no valid next word can be selected
            if (nextWord == null){
                break;
            }

            words.add(nextWord);
        }
        return String.join(" ", words);
    }

    /**
     * Selects the next word using weighted random selection.
     *
     * Each transition contains a possible next word and its
     * occurrence frequency. The probability of selecting a word
     * is proportional to its frequency relative to the total
     * frequency of all valid transitions.
     *
     * <pre>
     * P(nextWord) = frequency(nextWord) / totalFrequency
     * </pre>
     *
     * The method calculates the total frequency of all transitions
     * with positive occurrence counts. It then generates a random
     * integer within that range and compares it against cumulative
     * frequencies to select the next word.
     *
     * @param transitions list of possible next-word transitions
     *                    and their corresponding frequencies
     * @return a randomly selected next word based on transition
     *         frequencies, or null if no valid transition exists
     */
    private String selectNextWord(List<WordTransition> transitions){

        // Calculate the total frequency of valid transitions
        int totalFrequency = 0;

        for(WordTransition transition : transitions){
            if(transition.getOccurrences() > 0){
                totalFrequency += transition.getOccurrences();
            }
        }

        // Stop if no transitions have positive frequencies
        if (totalFrequency <= 0){
            return null;
        }

        int randomValue = random.nextInt(totalFrequency);

        int cumulativeFrequency = 0;

        // Select transition whose cumulative range contains the random value
        for (WordTransition transition : transitions){

            // Ignore any negative or 0 frequencies
            if(transition.getOccurrences() <= 0){
                continue;
            }

            cumulativeFrequency += transition.getOccurrences();

            if(randomValue < cumulativeFrequency){
                return transition.getNextWord();
            }
        }
        return null;
    }
}
