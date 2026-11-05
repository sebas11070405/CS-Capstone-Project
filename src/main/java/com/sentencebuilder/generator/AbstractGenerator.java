/*
 * Base class for the sentence generation algorithms. It builds the sentence:
 * starting from a given word, it looks up which words can follow the current
 * word and appends the one the algorithm picks. A sentence ends when the
 * algorithm chooses to end it, when it reaches a word that nothing has
 * followed, or at maxWords. Each algorithm extends this class and only
 * implements selectNextWord.
 *
 * Use new GreedyBigramGenerator(repo, maxWords).generateSentence(startWord).
 * The WordRepository's connection must stay open while generating.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.WordRepository;
import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.tokenizer.Tokenizer;

import java.util.List;

public abstract class AbstractGenerator {

    private final WordRepository repo;   // Looks up which words can follow the current word
    private final int maxWords;          // Stops generation if the sentence hasn't ended by then

    /**
     * Creates a generator that reads from repo and builds sentences of up to
     * maxWords words. Throws IllegalArgumentException if maxWords is below 1,
     * because the starting word alone is already one word.
     */
    protected AbstractGenerator(WordRepository repo, int maxWords) {
        if (maxWords < 1) {
            throw new IllegalArgumentException("maxWords must be at least 1: " + maxWords);
        }
        this.repo = repo;
        this.maxWords = maxWords;
    }

    /**
     * Builds one sentence that begins with startWord and returns its text, with
     * the words separated by single spaces. The starting word is normalized the
     * same way imported text is, so "The" looks up "the". If nothing has ever
     * followed it, the sentence is just that word. Throws IllegalArgumentException
     * unless startWord is exactly one valid word.
     */
    public String generateSentence(String startWord) {
        String firstWord = startWord == null ? null : Tokenizer.normalizeWord(startWord);
        if (firstWord == null) {
            throw new IllegalArgumentException("Not a valid starting word: " + startWord);
        }

        StringBuilder sentence = new StringBuilder(firstWord);
        String currentWord = firstWord;

        // Starts at 1 because the starting word is already the first word
        for (int ixWord = 1; ixWord < maxWords; ixWord++) {
            List<WordTransition> transitions = repo.getTransitions(currentWord);

            // Nothing has ever followed this word, so the sentence can't continue
            if (transitions.isEmpty()) {
                break;
            }

            // A sentence needs more than its starting word, so the first step can't end it.
            // Otherwise Greedy would return just "holmes", which ends sentences more often
            // than any one word follows it.
            int endCount = ixWord == 1 ? 0 : repo.getEndCount(currentWord);

            String nextWord = selectNextWord(transitions, endCount);
            if (nextWord == null) {
                break;
            }

            sentence.append(" ").append(nextWord);
            currentWord = nextWord;
        }

        return sentence.toString();
    }

    /**
     * Picks the next word, or returns null to end the sentence. This is the only
     * part that differs between algorithms. The transitions are the words that
     * can follow the current word, most frequent first, and the list is never
     * empty. The endCount is how many sentences ended on the current word.
     */
    protected abstract String selectNextWord(List<WordTransition> transitions, int endCount);
}
