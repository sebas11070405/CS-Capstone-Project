/*
 * Checks how WeightedRandomBigramGenerator chooses its next step. These tests
 * call selectNextWord directly with made-up counts, so they don't need the
 * database. The picks are random, so they run many times and allow a margin wide
 * enough that a correct generator won't fail by chance. Run them with
 * ./gradlew test.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.model.WordTransition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedRandomBigramGeneratorTest {

    private static final int RUNS = 100_000;
    private static final double MARGIN = 0.01;   // More than six standard deviations at this many runs

    private final WeightedRandomBigramGenerator generator = new WeightedRandomBigramGenerator(null, 10);

    @Test
    void picksInProportionToTheCountsIncludingEndings() {
        Map<String, Integer> picks = countPicks(List.of(
                new WordTransition("door", 60),
                new WordTransition("man", 20)), 20);

        assertEquals(0.60, picks.get("door") / (double) RUNS, MARGIN);
        assertEquals(0.20, picks.get("man") / (double) RUNS, MARGIN);
        assertEquals(0.20, picks.get(null) / (double) RUNS, MARGIN);   // Null means the sentence ended
    }

    @Test
    void rareWordsStillShowUp() {
        Map<String, Integer> picks = countPicks(List.of(
                new WordTransition("door", 999),
                new WordTransition("cat", 1)), 0);

        assertTrue(picks.containsKey("cat"));
    }

    /** Picks RUNS times and returns how often each word came up, with null meaning the sentence ended. */
    private Map<String, Integer> countPicks(List<WordTransition> transitions, int endCount) {
        Map<String, Integer> picks = new HashMap<>();
        for (int ixRun = 0; ixRun < RUNS; ixRun++) {
            picks.merge(generator.selectNextWord(transitions, endCount), 1, Integer::sum);
        }
        return picks;
    }
}
