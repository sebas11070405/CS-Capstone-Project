/*
 * Checks the whole task 4.5 flow: SentenceBatchService picks starting words and generates a batch
 * from the stored counts, and Importer.importGenerated feeds that batch back into the corpus. Both
 * run exactly as the Batch tab calls them, through their public constructors and the real
 * Postgres repositories.
 *
 * The corpus is predictable: one imported sentence, "Zzqalpha zzqbeta zzqgamma.", so zzqalpha is
 * the only starting word and every word has exactly one possible next word. Every algorithm then
 * generates "zzqalpha zzqbeta zzqgamma" every time, and the tests can state the exact word, pair,
 * and triple counts the import must produce.
 *
 * What the unit tests already cover is left to them: input validation and generator choice in
 * SentenceBatchServiceTest, and repeated batches inside one transaction, file duplicates, and an
 * empty batch in ImporterTest. These tests add what only the joined-up flow shows: generated output
 * counted correctly once it's saved, the same batch imported twice through importGenerated, the
 * Import report's view of a batch, and a failure partway through an import.
 *
 * Every connection opened during a test goes to temporary tables (see IsolatedDatabase), and each
 * test checks that generation and import really asked for connections there. Everything is rolled
 * back afterward, so shared data is never read or changed. Needs a valid .env and network access.
 * Run with ./gradlew test.
 *
 * All of it runs on one real connection, where each import's commit only releases a savepoint.
 * These tests check what an import writes and what its rollback undoes, but not that a real commit
 * persists or becomes visible to other, independent connections.
 *
 * AI-assisted: Claude Code
 *
 * Prompt:
 * Create a new branch from the latest main for task 4.5 integration tests. Test the complete flow
 * from SentenceBatchService output through the generated-sentence importer. Use predictable
 * generation data and verify word, bigram, and trigram counts, sentence boundaries, generated-batch
 * metadata, repeated imports, and rollback on failure. Reuse existing tests and helpers where
 * practical without duplicating their coverage.
 *
 * Keep all database work isolated from shared data. Use a test database or temporary tables, making
 * sure every connection involved uses the isolated setup. Keep production changes minimal.
 *
 * Also check the Batch tab's duplicate-click protection, retry after a failed import, and whether
 * imported batches appear in the Import report. If these require manual testing, document the steps
 * and clearly distinguish what you verified.
 *
 * Follow docs/comment-style.md and include this prompt in the appropriate header. Run the relevant
 * tests, disclose anything unverified, and summarize before pushing or opening a PR.
 *
 * Follow-up prompt:
 * Fix the isolation issue on batch-integration-tests: if a test fails or times out while a UI
 * background task is still running, that task must never fall back to the shared database. Keep
 * isolation active until outstanding work has stopped, and make late connection requests fail safely.
 * Add a regression test that delays a background connection request until after the test fails and
 * verifies it cannot reach shared data.
 *
 * Also correct the comments and documentation: the helper uses savepoints, so these tests don't
 * verify real commit persistence across independent connections. Keep the changes focused, preserve
 * attribution, and add this follow-up prompt. Run the relevant tests and summarize before pushing.
 *
 * Follow-up prompt:
 * Fix the remaining cleanup race in IsolatedDatabase.route(): a session-owned thread must always use
 * owner.open(), never fall through to shared.open(), even if cleanup happens during the connection
 * request. Add a deterministic regression test that overlaps cleanup with routing and verifies the
 * shared source is never called, using a fake source so the test cannot reach shared data. Keep the
 * change focused, preserve attribution, add this follow-up prompt, and rerun the isolation and batch
 * tests. Summarize before pushing.
 */

package com.sentencebuilder.integration;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.IsolatedDatabase;
import com.sentencebuilder.database.SourceFileRepository;
import com.sentencebuilder.database.SourceFileRepository.SourceFile;
import com.sentencebuilder.generator.SentenceBatchService;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;
import com.sentencebuilder.importer.CorpusStats;
import com.sentencebuilder.importer.Importer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BatchImportIntegrationTest {

    // Made up, so if isolation ever failed they would be easy to find and could never match real words
    public static final String START = "zzqalpha";
    public static final String MIDDLE = "zzqbeta";
    public static final String END = "zzqgamma";

    public static final String SEED_TEXT = "Zzqalpha zzqbeta zzqgamma.";   // The only text in the corpus
    // What every algorithm generates from it
    public static final String SENTENCE = START + " " + MIDDLE + " " + END;

    // Longer than SENTENCE, so generation stops because zzqgamma has no next word, not at the limit
    private static final int MAX_WORDS = 10;

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void generatedBatchIsCountedSentenceBySentence(Algorithm algorithm, @TempDir Path dir) throws Exception {
        IsolatedDatabase.run(session -> {
            seed(dir);

            // Generation reads the temporary tables through Database, like it does in the app
            int beforeGenerating = session.connectionsOpened();
            List<String> batch = new SentenceBatchService().generateBatch(3, algorithm, MAX_WORDS);
            assertEquals(Collections.nCopies(3, SENTENCE), batch);
            assertTrue(session.connectionsOpened() > beforeGenerating, "generation bypassed the isolated setup");

            int beforeImporting = session.connectionsOpened();
            CorpusStats stats = new Importer().importGenerated(batch);
            assertEquals(9, stats.wordCount());
            assertTrue(session.connectionsOpened() > beforeImporting, "import bypassed the isolated setup");

            // The seed sentence once, plus the three generated ones
            Connection conn = session.connection();
            assertWord(conn, START, 4, 4, 0);
            assertWord(conn, MIDDLE, 4, 0, 0);
            assertWord(conn, END, 4, 0, 4);
            assertEquals(4, bigramCount(conn, START, MIDDLE));
            assertEquals(4, bigramCount(conn, MIDDLE, END));
            assertEquals(4, trigramCount(conn, START, MIDDLE, END));

            // The batch reaches the importer as "... zzqgamma", "zzqalpha ...", but no pair or triple
            // may join the end of one sentence to the start of the next
            assertEquals(0, bigramCount(conn, END, START), "pair crossed two generated sentences");
            assertEquals(2, countRows(conn, "SELECT count(*) FROM bigrams"));
            assertEquals(1, countRows(conn, "SELECT count(*) FROM trigrams"));
        });
    }

    @Test
    void importedBatchIsListedAsGenerated(@TempDir Path dir) throws Exception {
        IsolatedDatabase.run(session -> {
            seed(dir);
            List<String> batch = new SentenceBatchService().generateBatch(2, Algorithm.MOST_LIKELY, MAX_WORDS);
            CorpusStats stats = new Importer().importGenerated(batch);

            // The Import report reads source_files through this repository, newest first
            List<SourceFile> report = new SourceFileRepository(session.connection()).findAll();
            assertEquals(2, report.size(), report.toString());
            SourceFile added = report.getFirst();
            assertEquals("Generated batch of 2 sentences", added.fileName());
            assertTrue(added.generated(), "batch was saved as a file");
            assertEquals(6, added.wordCount());
            assertFalse(report.get(1).generated(), "seed file was saved as a batch");

            // The hash is stored for generated batches too, so they can be matched up later
            assertEquals(1, countRows(session.connection(),
                    "SELECT count(*) FROM source_files WHERE source_type = 'generated' AND content_hash = ?",
                    stats.contentHash()));
        });
    }

    @Test
    void sameBatchCanBeImportedAgainAndAddsUp(@TempDir Path dir) throws Exception {
        IsolatedDatabase.run(session -> {
            seed(dir);
            List<String> batch = new SentenceBatchService().generateBatch(2, Algorithm.MOST_LIKELY, MAX_WORDS);

            // Two separate imports, each in its own (savepoint) transaction, like two Add to Corpus presses
            new Importer().importGenerated(batch);
            new Importer().importGenerated(batch);

            Connection conn = session.connection();
            assertWord(conn, START, 5, 5, 0);
            assertEquals(5, bigramCount(conn, START, MIDDLE));
            assertEquals(5, trigramCount(conn, START, MIDDLE, END));
            assertEquals(0, bigramCount(conn, END, START), "pair crossed two generated sentences");
            assertEquals(2, countRows(conn, "SELECT count(*) FROM source_files WHERE source_type = 'generated'"));

            // A later batch starts from the updated counts and still makes the same sentence
            assertEquals(List.of(SENTENCE),
                    new SentenceBatchService().generateBatch(1, Algorithm.TRIGRAM_BACKOFF, MAX_WORDS));
        });
    }

    @Test
    void importThatFailsPartwayLeavesNothingAndCanBeRetried(@TempDir Path dir) throws Exception {
        IsolatedDatabase.run(session -> {
            seed(dir);
            List<String> batch = new SentenceBatchService().generateBatch(2, Algorithm.MOST_LIKELY, MAX_WORDS);
            Connection conn = session.connection();
            List<Long> before = totals(conn);

            // Triples are saved last, so by then the batch row, words, and pairs have all been written
            session.failNextStatementContaining("INSERT INTO trigrams");
            SQLException thrown = assertThrows(SQLException.class, () -> new Importer().importGenerated(batch));
            assertTrue(thrown.getMessage().contains("INSERT INTO trigrams"), thrown.getMessage());
            assertEquals(before, totals(conn), "a failed import left some of its counts behind");

            // Trying again, as the Batch tab allows after a failure, counts the batch exactly once
            new Importer().importGenerated(batch);
            assertWord(conn, START, 3, 3, 0);
            assertEquals(3, trigramCount(conn, START, MIDDLE, END));
            assertEquals(1, countRows(conn, "SELECT count(*) FROM source_files WHERE source_type = 'generated'"));
        });
    }

    @Test
    void sharedTablesNeverSeeTheTestWords(@TempDir Path dir) throws Exception {
        IsolatedDatabase.run(session -> {
            seed(dir);
            new Importer().importGenerated(
                    new SentenceBatchService().generateBatch(2, Algorithm.MOST_LIKELY, MAX_WORDS));
        });

        // Back on the shared database now. Reading it is the only way to show nothing got through.
        try (Connection conn = Database.getConnection()) {
            assertEquals(0, countRows(conn, "SELECT count(*) FROM words WHERE word = ?", START));
        }
    }

    /**
     * Imports SEED_TEXT as a text file, through the same importFile the Import tab uses, so the
     * corpus has one sentence to generate from.
     */
    public static void seed(Path dir) throws Exception {
        Path file = dir.resolve("seed.txt");
        Files.writeString(file, SEED_TEXT, StandardCharsets.UTF_8);
        new Importer().importFile(file);
    }

    /**
     * Returns the number of sources, then the sums of word, pair, and triple counts, so a test can
     * check that nothing at all changed.
     */
    private static List<Long> totals(Connection conn) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT (SELECT count(*) FROM source_files),
                       (SELECT coalesce(sum(total_count), 0) FROM words),
                       (SELECT coalesce(sum(occurrences), 0) FROM bigrams),
                       (SELECT coalesce(sum(occurrences), 0) FROM trigrams)
                """);
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
        }
    }

    /** Asserts the total, start, and end counts stored for the word. */
    public static void assertWord(Connection conn, String word, int total, int start, int end) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT total_count, start_count, end_count FROM words WHERE word = ?")) {
            statement.setString(1, word);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), word + " not found");
                assertEquals(List.of(total, start, end), List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), word);
            }
        }
    }

    /** Returns how often first was followed by second, or 0 if never. */
    private static int bigramCount(Connection conn, String first, String second) throws SQLException {
        return countRows(conn, """
                SELECT coalesce(sum(b.occurrences), 0) FROM bigrams b
                JOIN words w1 ON w1.word_id = b.word_id
                JOIN words w2 ON w2.word_id = b.next_word_id
                WHERE w1.word = ? AND w2.word = ?
                """, first, second);
    }

    /** Returns how often first and second were followed by third, or 0 if never. */
    public static int trigramCount(Connection conn, String first, String second, String third) throws SQLException {
        return countRows(conn, """
                SELECT coalesce(sum(t.occurrences), 0) FROM trigrams t
                JOIN words w1 ON w1.word_id = t.word1_id
                JOIN words w2 ON w2.word_id = t.word2_id
                JOIN words w3 ON w3.word_id = t.next_word_id
                WHERE w1.word = ? AND w2.word = ? AND w3.word = ?
                """, first, second, third);
    }

    /** Runs a query that returns one number, filling its parameters in order, and returns the number. */
    public static int countRows(Connection conn, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            for (int ix = 0; ix < values.length; ix++) {
                statement.setString(ix + 1, values[ix]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
