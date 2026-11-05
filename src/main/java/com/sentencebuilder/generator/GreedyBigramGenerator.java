/*
 * Generates sentences by always taking the most common option: the word that
 * most often follows the current word, or ending the sentence if that is more
 * common than any single next word. The same starting word gives the same
 * sentence, and the output can repeat a phrase until it reaches the length limit.
 *
 * See AbstractGenerator for how to use it.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.WordRepository;
import com.sentencebuilder.model.WordTransition;

import java.util.List;

public class GreedyBigramGenerator extends AbstractGenerator {

    /** Creates a generator that reads from repo and builds sentences of up to maxWords words. */
    public GreedyBigramGenerator(WordRepository repo, int maxWords) {
        super(repo, maxWords);
    }

    /**
     * Returns the most frequent next word. Returns null to end the sentence if
     * more sentences ended here than continued with that word, or if the word
     * only came from autocomplete and never appeared in the imported text.
     */
    @Override
    protected String selectNextWord(List<WordTransition> transitions, int endCount) {
        // The repository sorts the most frequent word first
        WordTransition mostFrequent = transitions.getFirst();

        if (mostFrequent.frequency() == 0 || endCount > mostFrequent.frequency()) {
            return null;
        }
        return mostFrequent.nextWord();
    }
}
