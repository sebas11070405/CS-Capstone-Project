/*
 * Checks that SentenceBatchService validates its inputs, makes one sentence
 * per requested count with the chosen algorithm, allows repeated starting
 * words and sentences, and reports a corpus with no starting words clearly.
 *
 * The batch tests use an in-memory word chain ("the" -> "cat" -> "sat") and
 * starting words chosen by the test, so they need no database. The
 * pickStartWords tests need a valid .env and network access, but run against
 * a temporary words table that hides the shared one for their connection only
 * and is rolled back, so shared data is never read, changed, or locked. Run
 * them with ./gradlew test.
 *
 * AI-assisted: Claude Code
 *
 * Prompt: see src/main/java/com/sentencebuilder/generator/SentenceBatchService.java
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.StartWordRepository;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;
import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.repository.TrigramRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentenceBatchServiceTest {

    // Each word has one possible next word, so every algorithm builds the same sentence
    private static final Map<String, List<WordTransition>> CHAIN = Map.of(
            "the", List.of(new WordTransition("cat", 4)),
            "cat", List.of(new WordTransition("sat", 2)));

    // No trigrams, so TRIGRAM_BACKOFF always falls back to CHAIN
    private static final TrigramRepo NO_TRIGRAMS = (previousWord, currentWord) -> List.of();

    // Same columns as words in schema.sql, written out so the shared table isn't touched at all.
    // A temporary table comes first in the search path, so "words" means this copy on this connection.
    private static final String CREATE_TEMP_WORDS = """
            CREATE TEMP TABLE words
            (
                word_id     INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                word        VARCHAR(100) NOT NULL UNIQUE,
                total_count INT          NOT NULL DEFAULT 0,
                start_count INT          NOT NULL DEFAULT 0,
                end_count   INT          NOT NULL DEFAULT 0
            ) ON COMMIT DROP
            """;

    /** Read-only TransitionRepository over CHAIN. The write methods are never called by generators. */
    private static final class ChainRepo implements TransitionRepository {
        @Override
        public List<WordTransition> getTransitions(String word) {
            return CHAIN.getOrDefault(word, List.of());
        }

        @Override
        public boolean wordExists(String word) {
            return CHAIN.containsKey(word);
        }

        @Override
        public void addWord(String word) {
            throw new UnsupportedOperationException("batch generation must not write");
        }

        @Override
        public void recordSelection(String previousWord, String nextWord) {
            throw new UnsupportedOperationException("batch generation must not write");
        }
    }

    /** Database work a test passes in as a lambda. Unlike Runnable, it may throw. */
    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

    /** Returns a service that starts its sentences with the given words, in order. */
    private static SentenceBatchService serviceStartingWith(String... startWords) {
        return new SentenceBatchService(count -> List.of(startWords), new ChainRepo(), NO_TRIGRAMS);
    }

    @Test
    void makesOneSentencePerStartWordAndAllowsRepeats() throws Exception {
        List<String> batch = serviceStartingWith("the", "the", "cat").generateBatch(3, Algorithm.MOST_LIKELY, 5);
        assertEquals(List.of("the cat sat", "the cat sat", "cat sat"), batch);
    }

    @Test
    void asksForOneStartWordPerSentence() throws Exception {
        List<Integer> requested = new ArrayList<>();
        SentenceBatchService service = new SentenceBatchService(count -> {
            requested.add(count);
            return List.of("the", "cat", "the", "cat");
        }, new ChainRepo(), NO_TRIGRAMS);

        assertEquals(4, service.generateBatch(4, Algorithm.WEIGHTED_RANDOM, 5).size());
        assertEquals(List.of(4), requested);
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void everyAlgorithmFollowsTheChainUpToMaxWords(Algorithm algorithm) throws Exception {
        assertEquals(List.of("the cat sat"), serviceStartingWith("the").generateBatch(1, algorithm, 5));
        assertEquals(List.of("the cat"), serviceStartingWith("the").generateBatch(1, algorithm, 2));
        assertEquals(List.of("the"), serviceStartingWith("the").generateBatch(1, algorithm, 1));
    }

    @Test
    void createsTheGeneratorForEachAlgorithm() {
        assertInstanceOf(FrequentWordGenerator.class, SentenceBatchService.createGenerator(
                Algorithm.MOST_LIKELY, 5, "the", new ChainRepo(), NO_TRIGRAMS));
        assertInstanceOf(MarkovGenerator.class, SentenceBatchService.createGenerator(
                Algorithm.WEIGHTED_RANDOM, 5, "the", new ChainRepo(), NO_TRIGRAMS));
        assertInstanceOf(TrigramBackoffGenerator.class, SentenceBatchService.createGenerator(
                Algorithm.TRIGRAM_BACKOFF, 5, "the", new ChainRepo(), NO_TRIGRAMS));
    }

    @Test
    void rejectsBadInputsBeforePickingStartWords() {
        // Fails the test if validation lets a bad request through to the start-word lookup
        SentenceBatchService service = new SentenceBatchService(count -> {
            throw new AssertionError("start words were picked for an invalid request");
        }, new ChainRepo(), NO_TRIGRAMS);

        assertThrows(IllegalArgumentException.class, () -> service.generateBatch(0, Algorithm.MOST_LIKELY, 5));
        assertThrows(IllegalArgumentException.class, () -> service.generateBatch(-3, Algorithm.MOST_LIKELY, 5));
        assertThrows(IllegalArgumentException.class, () -> service.generateBatch(2, null, 5));
        assertThrows(IllegalArgumentException.class, () -> service.generateBatch(2, Algorithm.MOST_LIKELY, 0));
    }

    @Test
    void noStartWordsStopsTheBatch() {
        SentenceBatchService service = new SentenceBatchService(count -> {
            throw new NoStartWordException("none");
        }, new ChainRepo(), NO_TRIGRAMS);

        assertThrows(NoStartWordException.class, () -> service.generateBatch(3, Algorithm.MOST_LIKELY, 5));
    }

    @Test
    void pickingWithNoEligibleWordsExplainsWhy() throws Exception {
        inTemporaryWords(conn -> {
            addWord(conn, "middle", 0);   // Present, but never started a sentence
            NoStartWordException thrown = assertThrows(NoStartWordException.class, () ->
                    SentenceBatchService.pickStartWords(new StartWordRepository(conn), 2));
            assertTrue(thrown.getMessage().contains("Import a text file"), thrown.getMessage());
        });
    }

    @Test
    void pickingAllowsTheSameStartWordAgain() throws Exception {
        inTemporaryWords(conn -> {
            addWord(conn, "only", 2);
            assertEquals(List.of("only", "only", "only"),
                    SentenceBatchService.pickStartWords(new StartWordRepository(conn), 3));
        });
    }

    @Test
    void pickingUsesOnlyEligibleStartWords() throws Exception {
        inTemporaryWords(conn -> {
            addWord(conn, "first", 1);
            addWord(conn, "second", 4);
            addWord(conn, "never", 0);

            List<String> picks = SentenceBatchService.pickStartWords(new StartWordRepository(conn), 20);
            assertEquals(20, picks.size());
            assertTrue(Set.of("first", "second").containsAll(picks), picks.toString());
        });
    }

    /**
     * Runs the work against an empty temporary words table, and always rolls back
     * so the table disappears even if the work fails. First checks that "words"
     * really resolves to the temporary copy, so a test can't hit shared data.
     */
    private static void inTemporaryWords(DatabaseWork work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement statement = conn.createStatement()) {
                    statement.execute(CREATE_TEMP_WORDS);
                }
                try (Statement statement = conn.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT relnamespace = pg_my_temp_schema() FROM pg_class "
                                     + "WHERE oid = to_regclass('words')")) {
                    assertTrue(rs.next() && rs.getBoolean(1), "words does not resolve to the temporary table");
                }
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    /** Adds a word with the given start count to the temporary words table. */
    private static void addWord(Connection conn, String word, int startCount) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO words (word, total_count, start_count) VALUES (?, ?, ?)")) {
            statement.setString(1, word);
            statement.setInt(2, Math.max(startCount, 1));
            statement.setInt(3, startCount);
            statement.executeUpdate();
        }
    }
}
