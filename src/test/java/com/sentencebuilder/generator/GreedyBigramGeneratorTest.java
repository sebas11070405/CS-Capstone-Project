/*
 * Checks how GreedyBigramGenerator picks words and how a sentence is built, which
 * is shared with the other algorithms through AbstractGenerator. Greedy results
 * are exact, so these tests can compare whole sentences. The tests that build a
 * sentence need a valid .env, and TestCorpus rolls back everything they write.
 * Run them with ./gradlew test.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.TestCorpus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GreedyBigramGeneratorTest {

    private static final int NO_LIMIT = 50;   // Long enough that these sentences end on their own

    @Test
    void picksTheMostFrequentWord() throws Exception {
        TestCorpus.with("Zzqa zzqb. Zzqa zzqc zzqd. Zzqa zzqc zzqd.", (conn, repo) -> assertEquals("zzqa zzqc zzqd",
                new GreedyBigramGenerator(repo, NO_LIMIT).generateSentence("zzqa")));
    }

    @Test
    void endsWhenEndingIsMoreCommonThanAnyWord() throws Exception {
        // Two sentences end on zzqb, but only one continues to zzqc
        TestCorpus.with("Zzqa zzqb. Zzqa zzqb. Zzqb zzqc.", (conn, repo) -> assertEquals("zzqa zzqb",
                new GreedyBigramGenerator(repo, NO_LIMIT).generateSentence("zzqa")));
    }

    @Test
    void stopsAtMaxWords() throws Exception {
        // Each word is followed by the other more often than anything else, so this never ends itself
        TestCorpus.with("Zzqa zzqb zzqa zzqb zzqa zzqb zzqc.", (conn, repo) -> assertEquals("zzqa zzqb zzqa zzqb zzqa",
                new GreedyBigramGenerator(repo, 5).generateSentence("zzqa")));
    }

    @Test
    void firstStepCannotEndTheSentence() throws Exception {
        // More sentences end on zzqa than continue, but zzqa alone isn't a useful sentence
        TestCorpus.with("Zzqa. Zzqa. Zzqa zzqb.", (conn, repo) -> assertEquals("zzqa zzqb",
                new GreedyBigramGenerator(repo, NO_LIMIT).generateSentence("zzqa")));
    }

    @Test
    void normalizesTheStartingWord() throws Exception {
        TestCorpus.with("Zzqa zzqb.", (conn, repo) -> {
            GreedyBigramGenerator generator = new GreedyBigramGenerator(repo, NO_LIMIT);

            assertEquals("zzqa zzqb", generator.generateSentence("ZZQA"));
            assertEquals("zzqa zzqb", generator.generateSentence(" zzqa, "));
        });
    }

    @Test
    void rejectsAnythingButOneValidStartingWord() {
        // These fail before the first lookup, so no repository is needed
        GreedyBigramGenerator generator = new GreedyBigramGenerator(null, NO_LIMIT);

        assertThrows(IllegalArgumentException.class, () -> generator.generateSentence(null));
        assertThrows(IllegalArgumentException.class, () -> generator.generateSentence("42"));
        assertThrows(IllegalArgumentException.class, () -> generator.generateSentence("two words"));
    }
}
