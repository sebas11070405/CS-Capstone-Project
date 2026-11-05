/*
 * Holds the SQL that writes imported text to the database: the source_files
 * row that records each import, and the word, bigram, and trigram counts.
 * Every method runs on the connection passed to the constructor and never
 * commits, so the caller decides the transaction. Importer uses
 * Database.inTransaction so a failed import leaves nothing behind.
 *
 * Files are recorded with insertSourceFile, which refuses text that was
 * already imported as a file. Batches of generated sentences are recorded
 * with insertGeneratedBatch, which never refuses, because the same batch may
 * be fed back in more than once.
 *
 * Counts are added to what is already stored, so importing a second file
 * appends to the first. Write words before bigrams and trigrams, because
 * those queries look up word ids in the words table.
 *
 * Needs the tables from database/schema.sql.
 *
 * AI-assisted: Claude Sonnet 5.5 (bigram and trigram upserts), Claude Code (insertGeneratedBatch)
 *
 * Prompt:
 * The following is our Importer.java, CorpusStats.java, and schema.sql.
 * Currently the importer writes only the words table. Do bulk upserts for the
 * bigrams and trigrams tables as part of one transaction with the same unnest array
 * style as word upserts. Look up the word IDs from words table, join on them, and
 * update the existing row in case of conflict so that a subsequent file will append
 * to the earlier file. Sort rows by ID so that concurrent imports will lock the
 * same way. Write words first as joins require those rows. Do not alter the
 * duplicates checking logic. Document every query and method as per
 * docs/comment-style.md. Update the header comment which still mentions
 * bigrams and trigrams have not been written out.
 *
 * Prompt for insertGeneratedBatch: see src/main/java/com/sentencebuilder/importer/Importer.java
 */

package com.sentencebuilder.database;

import com.sentencebuilder.importer.CorpusStats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CorpusRepository {

    // DO NOTHING rather than an error keeps the transaction usable for looking up the earlier import
    private static final String INSERT_SOURCE = """
            INSERT INTO source_files (file_name, source_type, word_count, content_hash)
            VALUES (?, 'file', ?, ?)
            ON CONFLICT (content_hash) WHERE source_type = 'file' DO NOTHING
            """;

    // No ON CONFLICT: the unique hash index only covers 'file' rows, so a batch can always repeat
    private static final String INSERT_GENERATED = """
            INSERT INTO source_files (file_name, source_type, word_count, content_hash)
            VALUES (?, 'generated', ?, ?)
            """;

    // Finds the earlier import of the same text so the duplicate error can name it
    private static final String FIND_SOURCE = """
            SELECT file_name, imported_at FROM source_files
            WHERE content_hash = ? AND source_type = 'file'
            """;

    // unnest turns the four arrays into rows, so every word is inserted or updated in one round trip
    private static final String UPSERT_WORDS = """
            INSERT INTO words (word, total_count, start_count, end_count)
            SELECT * FROM unnest(?::text[], ?::int[], ?::int[], ?::int[])
            ON CONFLICT (word) DO UPDATE SET
                total_count = words.total_count + EXCLUDED.total_count,
                start_count = words.start_count + EXCLUDED.start_count,
                end_count   = words.end_count   + EXCLUDED.end_count
            """;

    // Joins on words to turn each word into its id; ORDER BY keeps lock order consistent between imports
    private static final String UPSERT_BIGRAMS = """
            INSERT INTO bigrams (word_id, next_word_id, occurrences)
            SELECT w1.word_id, w2.word_id, v.n
            FROM unnest(?::text[], ?::text[], ?::int[]) AS v(a, b, n)
            JOIN words w1 ON w1.word = v.a
            JOIN words w2 ON w2.word = v.b
            ORDER BY w1.word_id, w2.word_id
            ON CONFLICT (word_id, next_word_id) DO UPDATE SET
                occurrences = bigrams.occurrences + EXCLUDED.occurrences
            """;

    // Same as UPSERT_BIGRAMS with one more word in the key
    private static final String UPSERT_TRIGRAMS = """
            INSERT INTO trigrams (word1_id, word2_id, next_word_id, occurrences)
            SELECT w1.word_id, w2.word_id, w3.word_id, v.n
            FROM unnest(?::text[], ?::text[], ?::text[], ?::int[]) AS v(a, b, c, n)
            JOIN words w1 ON w1.word = v.a
            JOIN words w2 ON w2.word = v.b
            JOIN words w3 ON w3.word = v.c
            ORDER BY w1.word_id, w2.word_id, w3.word_id
            ON CONFLICT (word1_id, word2_id, next_word_id) DO UPDATE SET
                occurrences = trigrams.occurrences + EXCLUDED.occurrences
            """;

    private final Connection conn;   // Owned by the caller, who also commits or rolls back

    /** An earlier import found by its content hash: the file name it had and when it was imported. */
    public record SourceFile(String fileName, OffsetDateTime importedAt) {}

    /** Creates a repository that runs every query on the given connection. */
    public CorpusRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Records an imported file in source_files. Returns true if the row was
     * added, or false if a file with the same content hash is already there.
     */
    public boolean insertSourceFile(String fileName, int wordCount, String contentHash) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(INSERT_SOURCE)) {
            statement.setString(1, fileName);
            statement.setInt(2, wordCount);
            statement.setString(3, contentHash);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Records a batch of generated sentences in source_files with source_type
     * 'generated'. Unlike insertSourceFile it always adds a row, even if the same
     * batch was saved before.
     */
    public void insertGeneratedBatch(String batchName, int wordCount, String contentHash) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(INSERT_GENERATED)) {
            statement.setString(1, batchName);
            statement.setInt(2, wordCount);
            statement.setString(3, contentHash);
            statement.executeUpdate();
        }
    }

    /** Looks up the imported file with the given content hash. Returns empty if there is none. */
    public Optional<SourceFile> findSourceFile(String contentHash) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(FIND_SOURCE)) {
            statement.setString(1, contentHash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SourceFile(rs.getString("file_name"),
                        rs.getObject("imported_at", OffsetDateTime.class)));
            }
        }
    }

    /** Adds each word's total, start, and end counts to the words table, inserting words that are new. */
    public void addWordCounts(Map<String, CorpusStats.WordCounts> wordCounts) throws SQLException {
        // Sorted so concurrent imports lock rows in the same order and can't deadlock
        List<String> words = new ArrayList<>(wordCounts.keySet());
        Collections.sort(words);

        // Build parallel arrays for UPSERT_WORDS: entry ix of each array belongs to words[ix]
        int wordTotal = words.size();
        Integer[] totals = new Integer[wordTotal];
        Integer[] starts = new Integer[wordTotal];
        Integer[] ends = new Integer[wordTotal];
        for (int ix = 0; ix < wordTotal; ix++) {
            CorpusStats.WordCounts counts = wordCounts.get(words.get(ix));
            totals[ix] = counts.total();
            starts[ix] = counts.start();
            ends[ix] = counts.end();
        }

        try (PreparedStatement statement = conn.prepareStatement(UPSERT_WORDS)) {
            statement.setArray(1, conn.createArrayOf("text", words.toArray()));
            statement.setArray(2, conn.createArrayOf("int4", totals));
            statement.setArray(3, conn.createArrayOf("int4", starts));
            statement.setArray(4, conn.createArrayOf("int4", ends));
            statement.executeUpdate();
        }
    }

    /** Adds each word pair's count to the bigrams table. Every word must already be in words. */
    public void addBigramCounts(Map<CorpusStats.Bigram, Integer> bigramCounts) throws SQLException {
        // Parallel arrays for UPSERT_BIGRAMS, one entry per pair
        int total = bigramCounts.size();
        String[] first = new String[total];
        String[] second = new String[total];
        Integer[] counts = new Integer[total];
        int ix = 0;
        for (var entry : bigramCounts.entrySet()) {
            first[ix] = entry.getKey().first();
            second[ix] = entry.getKey().second();
            counts[ix++] = entry.getValue();
        }

        try (PreparedStatement statement = conn.prepareStatement(UPSERT_BIGRAMS)) {
            statement.setArray(1, conn.createArrayOf("text", first));
            statement.setArray(2, conn.createArrayOf("text", second));
            statement.setArray(3, conn.createArrayOf("int4", counts));
            statement.executeUpdate();
        }
    }

    /** Adds each word triple's count to the trigrams table. Every word must already be in words. */
    public void addTrigramCounts(Map<CorpusStats.Trigram, Integer> trigramCounts) throws SQLException {
        // Parallel arrays for UPSERT_TRIGRAMS, one entry per triple
        int total = trigramCounts.size();
        String[] first = new String[total];
        String[] second = new String[total];
        String[] third = new String[total];
        Integer[] counts = new Integer[total];
        int ix = 0;
        for (var entry : trigramCounts.entrySet()) {
            first[ix] = entry.getKey().first();
            second[ix] = entry.getKey().second();
            third[ix] = entry.getKey().third();
            counts[ix++] = entry.getValue();
        }

        try (PreparedStatement statement = conn.prepareStatement(UPSERT_TRIGRAMS)) {
            statement.setArray(1, conn.createArrayOf("text", first));
            statement.setArray(2, conn.createArrayOf("text", second));
            statement.setArray(3, conn.createArrayOf("text", third));
            statement.setArray(4, conn.createArrayOf("int4", counts));
            statement.executeUpdate();
        }
    }
}
