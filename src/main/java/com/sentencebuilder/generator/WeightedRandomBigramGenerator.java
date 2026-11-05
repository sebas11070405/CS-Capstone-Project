/*
 * Generates sentences by choosing each step at random, weighted by how often it
 * happened in the imported text. The choices are every word that can follow the
 * current word, plus ending the sentence. Common choices come up most often, but
 * the spec requires that rare words still show up sometimes, and they do.
 *
 * See AbstractGenerator for how to use it.
 *
 * AI-assisted: selectNextWord, tool not recorded
 *
 * Prompt:
 * Write a selectNextWord() method that selects the next word from a list of
 * word transitions using weighted random selection based on each word's frequency.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.WordRepository;
import com.sentencebuilder.model.WordTransition;

import java.util.List;
import java.util.Random;

public class WeightedRandomBigramGenerator extends AbstractGenerator {

    private final Random random = new Random();

    /** Creates a generator that reads from repo and builds sentences of up to maxWords words. */
    public WeightedRandomBigramGenerator(WordRepository repo, int maxWords) {
        super(repo, maxWords);
    }

    /**
     * Returns a random word, where each word's chance is its frequency divided
     * by the total of all the frequencies and endCount. Returns null to end the
     * sentence, with a chance of endCount divided by that same total, or if the
     * total is zero because nothing can be chosen.
     */
    @Override
    protected String selectNextWord(List<WordTransition> transitions, int endCount) {
        int total = endCount;
        for (WordTransition transition : transitions) {
            total += transition.frequency();
        }

        // nextInt throws on zero, and there is nothing to weight anyway
        if (total == 0) {
            return null;
        }

        int randomValue = random.nextInt(total);   // From 0 to total - 1

        // Each word covers a range as wide as its frequency, so common words are hit more often
        int cumulativeFrequency = 0;
        for (WordTransition transition : transitions) {
            cumulativeFrequency += transition.frequency();
            if (randomValue < cumulativeFrequency) {
                return transition.nextWord();
            }
        }

        // The value landed in the last endCount of the range, which stands for ending the sentence
        return null;
    }
}
