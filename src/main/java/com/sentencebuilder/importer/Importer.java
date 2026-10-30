/*
 * Reads a UTF-8 text file, counts it with CorpusStats, and adds the counts to
 * the words, bigrams, and trigrams tables. The source_files row and every
 * count are written in one transaction, so a failed import leaves the
 * database unchanged. Existing counts are added to, and bigrams.user_selected
 * is never touched. If the same text was imported before (matched by content
 * hash), importFile throws DuplicateImportException and writes nothing.
 *
 * importGenerated saves a batch of generated sentences the same way with
 * source_type 'generated'. Batches are never treated as duplicates, so the
 * same sentences can be fed back in more than once.
 *
 * backfillPairs is for files imported before pairs and triples were counted.
 * Their words are already in the database, so it adds only their bigrams and
 * trigrams. See its comment for when it is safe to run.
 *
 * Both imports take a ProgressListener so the UI can show how far along they
 * are. Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 *
 * AI-assisted: Claude Code
 *
 * Prompt:
 * Create a new branch from the latest main called importer-completion. Finish the existing importer
 * using the current PostgreSQL schema and tokenizer. Keep the implementation straightforward and follow
 * docs/comment-style.md.
 *
 * - Count consecutive word pairs and triples within each sentence, never across sentence boundaries.
 * - Save those counts to bigrams and trigrams, adding to existing occurrences and leaving user_selected
 *   unchanged.
 * - Keep file metadata and all counts in one transaction so failures roll everything back.
 * - Preserve duplicate-file detection. Explain how to handle files imported before pair/triple counting
 *   was added without counting their words twice. Don't reset the shared database.
 * - Let generated sentences reuse the counting and saving logic with source_type = 'generated',
 *   allowing repeated batches.
 * - Add progress updates to the existing import UI while keeping it responsive.
 *
 * Reuse the existing classes and database connection. Add tests for repeated pairs/triples, short
 * sentences, sentence boundaries, additive imports, duplicates, generated batches, and rollback on
 * failure. Run the tests and clearly state anything you couldn't verify.
 *
 * Follow-up prompt:
 * Please fix two issues in the current importer work:
 *
 * - Backfill accepts only some old files, then blocks backfilling the rest because pairs already
 * exist. Before writing anything, verify that every previously imported file is included. If any
 * are missing, report them and leave the database unchanged. Add a regression test with two old
 * files where only one is supplied.
 * - The backfill tests delete all bigrams and trigrams in the shared database before rolling back.
 * Change them to use isolated temporary tables or a separate test database so they don't delete or
 * lock shared data.
 *
 * Keep the fixes simple, preserve the existing importer behavior, and rerun the tests.
 */

package com.sentencebuilder.importer;

import com.sentencebuilder.database.Database;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Importer {

    // Rows sent per statement. Small enough for quick round trips, large enough to stay fast.
    private static final int BATCH_SIZE = 5000;

    // DO NOTHING rather than an error keeps the transaction usable for looking up the earlier
    // import. Generated batches never conflict because the unique index only covers 'file' rows.
    private static final String INSERT_SOURCE = """
            INSERT INTO source_files (file_name, source_type, word_count, content_hash)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (content_hash) WHERE source_type = 'file' DO NOTHING
            """;

    // Finds the earlier import of the same text for the duplicate error message
    private static final String FIND_SOURCE = """
            SELECT file_name, imported_at FROM source_files
            WHERE content_hash = ? AND source_type = 'file'
            """;

    // For example, "Oct 28, 2026 at 7:15 PM"
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    // unnest turns the four arrays into rows, so a whole batch of words is inserted or updated in one
    // round trip. RETURNING gives each word's id, which the bigram and trigram rows refer to.
    private static final String UPSERT_WORDS = """
            INSERT INTO words (word, total_count, start_count, end_count)
            SELECT * FROM unnest(?::text[], ?::int[], ?::int[], ?::int[])
            ON CONFLICT (word) DO UPDATE SET
                total_count = words.total_count + EXCLUDED.total_count,
                start_count = words.start_count + EXCLUDED.start_count,
                end_count   = words.end_count   + EXCLUDED.end_count
            RETURNING word, word_id
            """;

    // user_selected isn't listed, so new rows get its default of 0 and existing rows keep theirs
    private static final String UPSERT_BIGRAMS = """
            INSERT INTO bigrams (word_id, next_word_id, occurrences)
            SELECT * FROM unnest(?::int[], ?::int[], ?::int[])
            ON CONFLICT (word_id, next_word_id) DO UPDATE SET
                occurrences = bigrams.occurrences + EXCLUDED.occurrences
            """;

    private static final String UPSERT_TRIGRAMS = """
            INSERT INTO trigrams (word1_id, word2_id, next_word_id, occurrences)
            SELECT * FROM unnest(?::int[], ?::int[], ?::int[], ?::int[])
            ON CONFLICT (word1_id, word2_id, next_word_id) DO UPDATE SET
                occurrences = trigrams.occurrences + EXCLUDED.occurrences
            """;

    // Looks up ids without changing any counts, for backfillPairs
    private static final String FIND_WORD_IDS = "SELECT word, word_id FROM words WHERE word = ANY(?::text[])";

    /**
     * Receives progress while an import runs. fraction goes from 0 to 1, or is -1
     * while the amount of work isn't known yet. It's called on the importing
     * thread, so UI code must hand it to the JavaFX thread (a Task does this).
     */
    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (fraction, message) -> {};   // For callers that don't show progress

        void update(double fraction, String message);
    }

    /**
     * Imports a text file and returns its counts. Nothing is written if the text
     * was already imported or if any step fails.
     */
    public CorpusStats importFile(Path file, ProgressListener listener)
            throws IOException, SQLException, DuplicateImportException {
        // Count the whole file before connecting, so the connection isn't held open while it's read.
        // Reading is fast next to saving, so it just shows as busy rather than as a percentage.
        listener.update(-1, "Reading " + file.getFileName() + "…");
        CorpusStats stats;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stats = CorpusStats.from(reader);
        }

        try (Connection conn = Database.getConnection()) {
            save(conn, file.getFileName().toString(), "file", stats, listener);
        }
        return stats;
    }

    /**
     * Adds a batch of generated sentences to the counts, recorded in source_files
     * as source_type 'generated'. The same batch may be added again. Returns the
     * batch's counts. Throws IllegalArgumentException if the sentences have no words.
     */
    public CorpusStats importGenerated(List<String> sentences, ProgressListener listener)
            throws SQLException, DuplicateImportException {
        CorpusStats stats = CorpusStats.fromSentences(sentences);
        if (stats.wordCount() == 0) {
            throw new IllegalArgumentException("The generated sentences have no words to import");
        }
        String name = String.format("Generated batch of %d sentences", sentences.size());

        try (Connection conn = Database.getConnection()) {
            save(conn, name, "generated", stats, listener);
        }
        return stats;
    }

    /**
     * Adds only the bigrams and trigrams of files that were imported before pairs
     * were counted. Their words were already counted then, so they aren't counted
     * again. Run it once, with every such file, before anyone imports a file with
     * this version (see BackfillPairs).
     *
     * It refuses to run if bigrams or trigrams already has rows, since then some
     * pairs might be counted twice. It also refuses a file whose text isn't already
     * in source_files, and a list that leaves out any imported file, naming the
     * ones left out. Everything is one transaction, so a refusal changes nothing.
     */
    public void backfillPairs(List<Path> files, ProgressListener listener) throws IOException, SQLException {
        // Count every file first, so no connection is held open while they're read
        List<String> names = new ArrayList<>();
        List<CorpusStats> statsList = new ArrayList<>();
        for (Path file : files) {
            listener.update(-1, "Reading " + file.getFileName() + "…");
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                statsList.add(CorpusStats.from(reader));
            }
            names.add(file.getFileName().toString());
        }

        // One transaction, so a refusal or failure partway through leaves nothing behind
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                backfillPairs(conn, names, statsList, listener);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Writes everything in one transaction on the given connection and commits it.
     * On any failure it rolls back, so neither the source_files row nor any count
     * is left behind, then rethrows. Package-private so ImporterTest can check the
     * rollback. The connection is left open for the caller to close.
     */
    static void save(Connection conn, String fileName, String sourceType, CorpusStats stats,
                     ProgressListener listener) throws SQLException, DuplicateImportException {
        conn.setAutoCommit(false);
        try {
            write(conn, fileName, sourceType, stats, listener);
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
    }

    /**
     * Writes the source_files row and the word, bigram, and trigram counts on the
     * given connection without committing. Package-private so ImporterTest can run
     * it inside a transaction it rolls back.
     */
    static void write(Connection conn, String fileName, String sourceType, CorpusStats stats,
                      ProgressListener listener) throws SQLException, DuplicateImportException {
        listener.update(0, "Saving " + fileName + "…");
        insertSource(conn, fileName, sourceType, stats);

        // Progress counts rows across all three tables, since each row costs about the same to save
        Progress progress = new Progress(listener, fileName,
                stats.words().size() + stats.bigrams().size() + stats.trigrams().size());
        Map<String, Integer> wordIds = writeWords(conn, stats, progress);
        writeSequences(conn, UPSERT_BIGRAMS, stats.bigrams(), wordIds, progress, "word pairs");
        writeSequences(conn, UPSERT_TRIGRAMS, stats.trigrams(), wordIds, progress, "word triples");
    }

    /**
     * Records the import in source_files. If a file's text was already imported,
     * looks up that earlier import and throws DuplicateImportException naming it.
     */
    private static void insertSource(Connection conn, String fileName, String sourceType, CorpusStats stats)
            throws SQLException, DuplicateImportException {
        try (PreparedStatement statement = conn.prepareStatement(INSERT_SOURCE)) {
            statement.setString(1, fileName);
            statement.setString(2, sourceType);
            statement.setInt(3, stats.wordCount());
            statement.setString(4, stats.contentHash());
            // One row inserted means this text is new, or it's a generated batch
            if (statement.executeUpdate() == 1) {
                return;
            }
        }

        // Nothing was inserted, so the hash is already there. Find that import to name it in the error.
        try (PreparedStatement statement = conn.prepareStatement(FIND_SOURCE)) {
            statement.setString(1, stats.contentHash());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                String importedAt = rs.getObject("imported_at", OffsetDateTime.class)
                        .atZoneSameInstant(ZoneId.systemDefault())
                        .format(DATE_FORMAT);
                throw new DuplicateImportException(String.format("This text was already imported as %s on %s.",
                        rs.getString("file_name"), importedAt));
            }
        }
    }

    /**
     * Adds every word's counts to the words table, inserting words that are new.
     * Returns each word's word_id, keyed by the word.
     */
    private static Map<String, Integer> writeWords(Connection conn, CorpusStats stats, Progress progress)
            throws SQLException {
        // Sorted so concurrent imports lock rows in the same order and can't deadlock
        List<String> words = new ArrayList<>(stats.words().keySet());
        Collections.sort(words);

        Map<String, Integer> wordIds = new HashMap<>();
        try (PreparedStatement statement = conn.prepareStatement(UPSERT_WORDS)) {
            for (int batchStart = 0; batchStart < words.size(); batchStart += BATCH_SIZE) {
                List<String> batch = words.subList(batchStart, Math.min(batchStart + BATCH_SIZE, words.size()));

                // Build parallel arrays for UPSERT_WORDS: entry ix of each array belongs to batch[ix]
                Integer[] totals = new Integer[batch.size()];
                Integer[] starts = new Integer[batch.size()];
                Integer[] ends = new Integer[batch.size()];
                for (int ix = 0; ix < batch.size(); ix++) {
                    CorpusStats.WordCounts counts = stats.words().get(batch.get(ix));
                    totals[ix] = counts.total();
                    starts[ix] = counts.start();
                    ends[ix] = counts.end();
                }

                statement.setArray(1, conn.createArrayOf("text", batch.toArray()));
                statement.setArray(2, conn.createArrayOf("int4", totals));
                statement.setArray(3, conn.createArrayOf("int4", starts));
                statement.setArray(4, conn.createArrayOf("int4", ends));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        wordIds.put(rs.getString("word"), rs.getInt("word_id"));
                    }
                }
                progress.add(batch.size(), "words");
            }
        }
        return wordIds;
    }

    /**
     * Adds bigram or trigram counts with the given upsert, which takes one int
     * array per word position followed by one for the occurrences. counts is
     * keyed by the words in order, and wordIds must hold an id for every one. label
     * names the rows in progress messages.
     */
    private static void writeSequences(Connection conn, String upsertSql, Map<List<String>, Integer> counts,
                                       Map<String, Integer> wordIds, Progress progress, String label)
            throws SQLException {
        // Turn each entry into a row of word ids followed by its count
        List<int[]> rows = new ArrayList<>(counts.size());
        for (Map.Entry<List<String>, Integer> entry : counts.entrySet()) {
            List<String> sequence = entry.getKey();
            int[] row = new int[sequence.size() + 1];
            for (int ix = 0; ix < sequence.size(); ix++) {
                row[ix] = wordIds.get(sequence.get(ix));
            }
            row[sequence.size()] = entry.getValue();
            rows.add(row);
        }
        // Sorted by ids so concurrent imports lock rows in the same order and can't deadlock
        rows.sort(Arrays::compare);

        try (PreparedStatement statement = conn.prepareStatement(upsertSql)) {
            for (int batchStart = 0; batchStart < rows.size(); batchStart += BATCH_SIZE) {
                List<int[]> batch = rows.subList(batchStart, Math.min(batchStart + BATCH_SIZE, rows.size()));

                // Turn the rows into one array per column: columns[col][ix] is column col of batch[ix]
                int columnCount = batch.getFirst().length;
                Integer[][] columns = new Integer[columnCount][batch.size()];
                for (int ix = 0; ix < batch.size(); ix++) {
                    for (int col = 0; col < columnCount; col++) {
                        columns[col][ix] = batch.get(ix)[col];
                    }
                }

                for (int col = 0; col < columnCount; col++) {
                    statement.setArray(col + 1, conn.createArrayOf("int4", columns[col]));
                }
                statement.executeUpdate();
                progress.add(batch.size(), label);
            }
        }
    }

    /**
     * Does the work of backfillPairs on the given connection without committing.
     * Package-private so ImporterTest can run it inside a transaction it rolls back.
     */
    static void backfillPairs(Connection conn, List<String> names, List<CorpusStats> statsList,
                              ProgressListener listener) throws SQLException {
        // Keep imports and other backfills out until this one commits, so the empty check below stays
        // true. Plain reads still work.
        try (Statement statement = conn.createStatement()) {
            statement.execute("LOCK TABLE source_files, bigrams, trigrams IN SHARE ROW EXCLUSIVE MODE");
        }

        // Rows already there mean pairs were counted for some file, which could then be counted twice
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT EXISTS (SELECT 1 FROM bigrams) OR EXISTS (SELECT 1 FROM trigrams)")) {
            rs.next();
            if (rs.getBoolean(1)) {
                throw new IllegalStateException("Word pairs have already been counted, so backfilling could "
                        + "count some twice. Nothing was changed.");
            }
        }

        // Each file must match an earlier import exactly once, so its words were counted and its
        // pairs get added only once
        Set<String> seenHashes = new HashSet<>();
        for (int ix = 0; ix < statsList.size(); ix++) {
            String hash = statsList.get(ix).contentHash();
            if (!seenHashes.add(hash)) {
                throw new IllegalStateException(names.get(ix) + " has the same text as another file in the list. "
                        + "Nothing was changed.");
            }
            try (PreparedStatement statement = conn.prepareStatement(FIND_SOURCE)) {
                statement.setString(1, hash);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(names.get(ix) + " doesn't match any imported file, so its "
                                + "words were never counted. Import it normally instead. Nothing was changed.");
                    }
                }
            }
        }

        // Every imported file must be in this run. A later run couldn't add the rest, because pairs
        // would exist by then and it would refuse so nothing is counted twice.
        List<String> missing = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT file_name, content_hash FROM source_files WHERE source_type = 'file' ORDER BY file_id")) {
            while (rs.next()) {
                if (!seenHashes.contains(rs.getString("content_hash"))) {
                    missing.add(rs.getString("file_name"));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("These imported files are missing from the list: "
                    + String.join(", ", missing) + ". Include every imported file. Nothing was changed.");
        }

        // Add each file's pairs and triples using the ids of words that are already stored
        for (int ix = 0; ix < statsList.size(); ix++) {
            CorpusStats stats = statsList.get(ix);
            Progress progress = new Progress(listener, names.get(ix),
                    stats.bigrams().size() + stats.trigrams().size());
            Map<String, Integer> wordIds = findWordIds(conn, stats.words().keySet());
            writeSequences(conn, UPSERT_BIGRAMS, stats.bigrams(), wordIds, progress, "word pairs");
            writeSequences(conn, UPSERT_TRIGRAMS, stats.trigrams(), wordIds, progress, "word triples");
        }
    }

    /**
     * Returns the word_id of each given word, keyed by the word, without changing
     * any counts. Throws IllegalStateException if a word isn't in the words table.
     */
    private static Map<String, Integer> findWordIds(Connection conn, Set<String> words) throws SQLException {
        Map<String, Integer> wordIds = new HashMap<>();
        try (PreparedStatement statement = conn.prepareStatement(FIND_WORD_IDS)) {
            statement.setArray(1, conn.createArrayOf("text", words.toArray()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    wordIds.put(rs.getString("word"), rs.getInt("word_id"));
                }
            }
        }
        // Can only happen if words were edited since the import, since the hashes matched
        if (wordIds.size() != words.size()) {
            throw new IllegalStateException("Some words of an imported file are missing from the words table. "
                    + "Nothing was changed.");
        }
        return wordIds;
    }

    /** Turns rows saved so far into a fraction and a message for the ProgressListener. */
    private static class Progress {
        private final ProgressListener listener;
        private final String fileName;
        private final int totalRows;   // Rows to save across every table
        private int savedRows;

        Progress(ProgressListener listener, String fileName, int totalRows) {
            this.listener = listener;
            this.fileName = fileName;
            this.totalRows = totalRows;
        }

        /** Records that this many more rows were saved and reports the new total. kind names what they were. */
        void add(int rows, String kind) {
            savedRows += rows;
            double fraction = totalRows == 0 ? 1 : (double) savedRows / totalRows;
            listener.update(fraction, String.format("Saving %s: %s… %d%%", fileName, kind,
                    Math.round(fraction * 100)));
        }
    }
}
