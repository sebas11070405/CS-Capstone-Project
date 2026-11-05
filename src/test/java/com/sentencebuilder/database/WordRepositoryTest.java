/*
 * Checks the order WordRepository returns transitions in, which the generators
 * and autocomplete rely on to find the most frequent words. They need a valid
 * .env, and TestCorpus rolls back everything they write. Run them with
 * ./gradlew test.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.model.WordTransition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordRepositoryTest {

    @Test
    void transitionsComeBackMostFrequentFirst() throws Exception {
        TestCorpus.with("Zzqa zzqb. Zzqa zzqc. Zzqa zzqc.", (conn, repo) -> assertEquals(
                List.of(new WordTransition("zzqc", 2), new WordTransition("zzqb", 1)),
                repo.getTransitions("zzqa")));
    }

    @Test
    void tiedTransitionsComeBackAlphabetically() throws Exception {
        TestCorpus.with("Zzqa.", (conn, repo) -> {
            // Added out of order so their ids aren't alphabetical, or an unsorted query could pass by luck
            repo.addWord("zzqy");
            repo.addWord("zzqb");
            repo.recordSelection("zzqa", "zzqy");
            repo.recordSelection("zzqa", "zzqb");

            assertEquals(
                    List.of(new WordTransition("zzqb", 0), new WordTransition("zzqy", 0)),
                    repo.getTransitions("zzqa"));
        });
    }
}
