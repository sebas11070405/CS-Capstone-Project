package com.sentencebuilder.importer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CorpusStatsTest {

    private static CorpusStats stats(String text) throws IOException {
        return CorpusStats.from(new StringReader(text));
    }

    private static void assertCounts(CorpusStats stats, String word, int total, int start, int end) {
        CorpusStats.WordCounts counts = stats.words().get(word);
        assertEquals(total, counts.total(), word + " total");
        assertEquals(start, counts.start(), word + " start");
        assertEquals(end, counts.end(), word + " end");
    }

    @Test
    void countsTotalsStartsAndEnds() throws IOException {
        CorpusStats stats = stats("The cat sat. The dog ran. A cat ran.");
        assertCounts(stats, "the", 2, 2, 0);
        assertCounts(stats, "cat", 2, 0, 0);
        assertCounts(stats, "ran", 2, 0, 2);
        assertCounts(stats, "sat", 1, 0, 1);
        assertCounts(stats, "a", 1, 1, 0);
        assertEquals(6, stats.words().size());
        assertEquals(9, stats.wordCount());
    }

    @Test
    void oneWordSentenceIsBothStartAndEnd() throws IOException {
        assertCounts(stats("Yes."), "yes", 1, 1, 1);
    }

    @Test
    void countsUseNormalizedWords() throws IOException {
        CorpusStats stats = stats("Holmes said. HOLMES left.");
        assertCounts(stats, "holmes", 2, 2, 0);
        assertFalse(stats.words().containsKey("Holmes"));
    }

    @Test
    void emptyInputHasNoWords() throws IOException {
        CorpusStats stats = stats("");
        assertEquals(0, stats.words().size());
        assertEquals(0, stats.wordCount());
    }
}
