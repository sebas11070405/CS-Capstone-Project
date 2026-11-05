/*
 * Checks autocomplete's suggestions and how it records the user's choices,
 * against a small made-up corpus. They need a valid .env, and TestCorpus rolls
 * back everything they write. Run them with ./gradlew test.
 */

package com.sentencebuilder.autocomplete;

import com.sentencebuilder.database.TestCorpus;
import com.sentencebuilder.model.WordTransition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutocompleteTest {

    // After zzqa: zzqd follows three times, zzqc twice, and zzqb once
    private static final String CORPUS =
            "Zzqa zzqd. Zzqa zzqd. Zzqa zzqd. Zzqa zzqc. Zzqa zzqc. Zzqa zzqb.";

    @Test
    void suggestsTheMostFrequentWordsUpToTheLimit() throws Exception {
        TestCorpus.with(CORPUS, (conn, repo) -> assertEquals(
                List.of(new WordTransition("zzqd", 3), new WordTransition("zzqc", 2)),
                new Autocomplete(repo).getSuggestions("zzqa", 2)));
    }

    @Test
    void normalizesWhatTheUserTypes() throws Exception {
        TestCorpus.with(CORPUS, (conn, repo) -> {
            Autocomplete autocomplete = new Autocomplete(repo);

            assertEquals(List.of(new WordTransition("zzqd", 3)), autocomplete.getSuggestions("ZZQA,", 1));

            autocomplete.recordChoice("ZZQA", "Zzqnew,");
            assertEquals(1, TestCorpus.userSelected(conn, "zzqa", "zzqnew"));
        });
    }

    @Test
    void choosingNewWordsStoresThemAndTheirPair() throws Exception {
        TestCorpus.with(CORPUS, (conn, repo) -> {
            new Autocomplete(repo).recordChoice("zzqnewone", "zzqnewtwo");

            assertTrue(TestCorpus.wordIsStored(conn, "zzqnewone"));
            assertTrue(TestCorpus.wordIsStored(conn, "zzqnewtwo"));
            assertEquals(1, TestCorpus.userSelected(conn, "zzqnewone", "zzqnewtwo"));
        });
    }

    @Test
    void choosingAPairAgainCountsItAgain() throws Exception {
        TestCorpus.with(CORPUS, (conn, repo) -> {
            Autocomplete autocomplete = new Autocomplete(repo);
            autocomplete.recordChoice("zzqa", "zzqd");
            autocomplete.recordChoice("zzqa", "zzqd");

            assertEquals(2, TestCorpus.userSelected(conn, "zzqa", "zzqd"));
        });
    }

    @Test
    void invalidChoicesThrowAndStoreNothing() throws Exception {
        TestCorpus.with(CORPUS, (conn, repo) -> {
            Autocomplete autocomplete = new Autocomplete(repo);

            assertThrows(IllegalArgumentException.class, () -> autocomplete.recordChoice("zzqnew", "42"));
            assertThrows(IllegalArgumentException.class, () -> autocomplete.recordChoice("two words", "zzqnew"));
            assertFalse(TestCorpus.wordIsStored(conn, "zzqnew"));
        });
    }
}
