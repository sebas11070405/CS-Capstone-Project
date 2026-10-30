/*
 * Checks the word, start, and end counts, the word pair and triple counts,
 * and the content hash for small sample texts. They need no database. Run
 * them with ./gradlew test.
 */

package com.sentencebuilder.importer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void hashIgnoresFormatting() throws IOException {
        String hash = stats("The cat sat.\nThe dog ran.").contentHash();
        assertEquals(64, hash.length());
        assertEquals(hash, stats("The cat sat.\r\nThe dog ran.").contentHash());
        assertEquals(hash, stats("THE  CAT\nSAT!   The dog ran").contentHash());
    }

    @Test
    void hashChangesWithWordsOrSentenceBreaks() throws IOException {
        String hash = stats("The cat sat. The dog ran.").contentHash();
        assertNotEquals(hash, stats("The cat sat. The dog sat.").contentHash());
        assertNotEquals(hash, stats("The cat sat the dog ran.").contentHash());
    }

    @Test
    void emptyInputHasNoWords() throws IOException {
        CorpusStats stats = stats("");
        assertEquals(0, stats.words().size());
        assertEquals(0, stats.wordCount());
        assertTrue(stats.bigrams().isEmpty());
        assertTrue(stats.trigrams().isEmpty());
    }

    @Test
    void countsRepeatedPairsAndTriples() throws IOException {
        CorpusStats stats = stats("The cat sat. The cat sat. The cat ran.");
        assertEquals(Map.of(
                List.of("the", "cat"), 3,
                List.of("cat", "sat"), 2,
                List.of("cat", "ran"), 1), stats.bigrams());
        assertEquals(Map.of(
                List.of("the", "cat", "sat"), 2,
                List.of("the", "cat", "ran"), 1), stats.trigrams());
    }

    @Test
    void countsOverlappingRepeatsInOneSentence() throws IOException {
        // Four words give three overlapping pairs and two overlapping triples
        CorpusStats stats = stats("Very very very very.");
        assertEquals(Map.of(List.of("very", "very"), 3), stats.bigrams());
        assertEquals(Map.of(List.of("very", "very", "very"), 2), stats.trigrams());
    }

    @Test
    void shortSentencesHaveFewerPairsAndTriples() throws IOException {
        CorpusStats oneWord = stats("Yes.");
        assertTrue(oneWord.bigrams().isEmpty());
        assertTrue(oneWord.trigrams().isEmpty());

        CorpusStats twoWords = stats("Go home.");
        assertEquals(Map.of(List.of("go", "home"), 1), twoWords.bigrams());
        assertTrue(twoWords.trigrams().isEmpty());
    }

    @Test
    void pairsNeverCrossSentenceBoundaries() throws IOException {
        // Ended by a period, a question mark, and a blank line with no punctuation
        CorpusStats stats = stats("The cat sat. Dogs ran? Birds flew\n\nFish swam");
        assertNull(stats.bigrams().get(List.of("sat", "dogs")));
        assertNull(stats.bigrams().get(List.of("ran", "birds")));
        assertNull(stats.bigrams().get(List.of("flew", "fish")));
        assertNull(stats.trigrams().get(List.of("cat", "sat", "dogs")));
        assertNull(stats.trigrams().get(List.of("sat", "dogs", "ran")));
        assertEquals(5, stats.bigrams().size());
        assertEquals(1, stats.trigrams().size());
    }

    @Test
    void generatedSentencesAreSeparateEvenWithoutPunctuation() {
        CorpusStats stats = CorpusStats.fromSentences(List.of("the cat sat", "dogs ran"));
        assertNull(stats.bigrams().get(List.of("sat", "dogs")));
        assertEquals(3, stats.bigrams().size());
        assertCounts(stats, "dogs", 1, 1, 0);
        assertCounts(stats, "sat", 1, 0, 1);
    }
}
