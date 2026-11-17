/*
 * Reads a UTF-8 text file, counts its words, word pairs, and word triples with
 * CorpusStats, and adds those counts to the database. The source_files row and
 * all the counts are written in one transaction, so a failed import leaves the
 * database unchanged. If the same text was imported before (matched by content
 * hash), it throws DuplicateImportException and writes nothing. The SQL itself
 * lives in CorpusRepository.
 *
 * importFile can take a ProgressListener so the UI can show how far along the
 * import is. Completion (a fraction of 1) is reported only after the
 * transaction commits, so a failed import never looks finished.
 *
 * importGenerated saves a list of generated sentences the same way, with
 * source_type 'generated' and without progress reporting. Each sentence is
 * counted on its own, so no pair or triple spans two of them. Batches are
 * never treated as duplicates, so the same sentences can be fed back in more
 * than once, and a batch never blocks a file with the same text.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 *
 * AI-assisted: Claude Sonnet 5.5 (write order for bigrams and trigrams)
 *
 * Prompt: see src/main/java/com/sentencebuilder/database/CorpusRepository.java
 *
 * AI-assisted: Claude Code (progress reporting and generated-sentence imports, ported from PR #16)
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
 * Create a new branch from the latest main called import-progress. Extract the progress-reporting
 * feature from PR #16 and adapt it to the current Importer, CorpusRepository, and
 * Database.inTransaction structure.
 *
 * Reuse the existing progress listener, progress bar, and UI bindings where practical. Preserve
 * importFile(Path), keep the UI responsive, and report completion only after commit.
 *
 * Include only progress reporting and its tests. Leave out backfill, generated imports, and unrelated
 * counting changes. Follow docs/comment-style.md, preserve the original attribution and add this
 * follow-up prompt. Run the tests and summarize the changes before opening a PR.
 *
 * Follow-up prompt:
 * I noticed files like "Hello." and "Hello world." report 100% progress before the transaction
 * commits because they have no triples. Keep every pre-commit progress value below 1 and report 1
 * only after a successful commit. Add regression tests for both cases, including a failure before
 * commit. Keep the change focused and rerun the tests.
 *
 * Follow-up prompt:
 * Create a new branch from the latest main called generated-imports. Extract the generated-sentence
 * import feature from PR #16 and adapt it to the current Importer, CorpusStats, CorpusRepository, and
 * Database.inTransaction structure.
 *
 * Accept a list of generated sentences, keeping each sentence separate when counting words, bigrams,
 * and trigrams. Save the batch with source_type = 'generated' and allow repeated batches. Keep all
 * writes in one transaction and preserve duplicate detection for file imports.
 *
 * Reuse the existing counting and database methods. Include only generated imports and focused
 * tests—no generator changes, UI, progress reporting, or backfill.
 *
 * Follow docs/comment-style.md, preserve the original attribution, and add this follow-up prompt. Run
 * the tests, disclose any exclusions, and summarize before opening a PR.
 *
 * Follow-up prompt:
 * Rebase the generated-imports branch for PR #24 onto the latest main. Resolve the conflicts while
 * preserving both file-import progress reporting and generated-sentence imports, including their tests
 * and prompt headers. Run the relevant tests without changing shared database data, then summarize
 * before pushing.
 */

package com.sentencebuilder.importer;

import com.sentencebuilder.database.CorpusRepository;
import com.sentencebuilder.database.Database;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class Importer {

    // For example, "Oct 28, 2026 at 7:15 PM"
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    /**
     * Receives progress while an import runs. fraction goes from 0 to 1, or is -1
     * while the amount of work isn't known. It's called on the importing thread,
     * so UI code must hand it to the JavaFX thread (a Task does this).
     */
    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (fraction, message) -> {};   // For callers that don't show progress

        void update(double fraction, String message);
    }

    /**
     * Imports a text file and returns its counts, without reporting progress.
     * Nothing is written if the text was already imported or if any step fails.
     */
    public CorpusStats importFile(Path file) throws IOException, SQLException, DuplicateImportException {
        return importFile(file, ProgressListener.NONE);
    }

    /**
     * Imports a text file and returns its counts, reporting progress to the
     * listener. Nothing is written if the text was already imported or if any
     * step fails, and in that case completion is never reported.
     */
    public CorpusStats importFile(Path file, ProgressListener listener)
            throws IOException, SQLException, DuplicateImportException {
        String fileName = file.getFileName().toString();

        // Count the whole file before connecting, so the connection isn't held open while it's read.
        // Reading is fast next to saving, so it shows as busy rather than as a percentage.
        listener.update(-1, "Reading " + fileName + "…");
        CorpusStats stats;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stats = CorpusStats.from(reader);
        }

        Database.inTransaction(conn -> {
            write(conn, fileName, stats, listener);
            // inTransaction commits after this returns, and how long that takes isn't known
            listener.update(-1, "Saving " + fileName + ": committing…");
            return null;
        });

        // Only reached once the commit succeeded, so the import really is finished
        listener.update(1, "Imported " + fileName);
        return stats;
    }

    /**
     * Imports a batch of generated sentences, one sentence per string, and
     * returns their counts. The batch is saved with source_type 'generated' and
     * may be imported again. Nothing is written if any step fails. Throws
     * IllegalArgumentException if the sentences have no words, since an empty
     * batch would only add an empty source_files row.
     */
    public CorpusStats importGenerated(List<String> sentences) throws SQLException {
        CorpusStats stats = CorpusStats.fromSentences(sentences);
        if (stats.wordCount() == 0) {
            throw new IllegalArgumentException("The generated sentences have no words to import");
        }

        String batchName = String.format("Generated batch of %d sentences", sentences.size());
        return Database.inTransaction(conn -> {
            writeGenerated(conn, batchName, stats);
            return stats;
        });
    }

    /**
     * Writes the source_files row and the word, bigram, and trigram counts on the
     * given connection without committing or reporting progress. Package-private
     * so ImporterTest can run it inside a transaction it rolls back.
     */
    static void write(Connection conn, String fileName, CorpusStats stats)
            throws SQLException, DuplicateImportException {
        write(conn, fileName, stats, ProgressListener.NONE);
    }

    /**
     * Same as write above, but reports each table to the listener as it starts.
     * The fraction never reaches 1 here, because the caller reports completion
     * only after the commit.
     */
    static void write(Connection conn, String fileName, CorpusStats stats, ProgressListener listener)
            throws SQLException, DuplicateImportException {
        CorpusRepository repository = new CorpusRepository(conn);

        // Zero rows inserted means this text is already there, so name the earlier import and stop
        listener.update(0, "Saving " + fileName + "…");
        if (!repository.insertSourceFile(fileName, stats.wordCount(), stats.contentHash())) {
            throw duplicateOf(repository, stats.contentHash());
        }
        addCounts(repository, stats, fileName, listener);
    }

    /**
     * Writes a 'generated' source_files row and the batch's word, bigram, and
     * trigram counts on the given connection without committing. There is no
     * duplicate check, so the same batch can be written again. Package-private
     * so ImporterTest can run it inside a transaction it rolls back.
     */
    static void writeGenerated(Connection conn, String batchName, CorpusStats stats) throws SQLException {
        CorpusRepository repository = new CorpusRepository(conn);
        repository.insertGeneratedBatch(batchName, stats.wordCount(), stats.contentHash());
        addCounts(repository, stats, batchName, ProgressListener.NONE);
    }

    /**
     * Adds the word, bigram, and trigram counts, shared by file and generated
     * imports. Reports each table to the listener as it starts, using label (the
     * file or batch name) in the messages. The fraction never reaches 1 here,
     * because only importFile reports completion, after the commit.
     */
    private static void addCounts(CorpusRepository repository, CorpusStats stats, String label,
                                  ProgressListener listener) throws SQLException {
        // Each table is saved in one statement, so progress moves once per table. It's weighted by row
        // count, since each row costs about the same to save. The commit counts as one more unit, so
        // every fraction here stays below 1 even when a file has no pairs or triples.
        int wordRows = stats.words().size();
        int bigramRows = stats.bigrams().size();
        double totalUnits = wordRows + bigramRows + stats.trigrams().size() + 1;

        listener.update(0, "Saving " + label + ": words…");
        repository.addWordCounts(stats.words());   // First, because the n-gram queries look up word ids
        listener.update(wordRows / totalUnits, "Saving " + label + ": word pairs…");
        repository.addBigramCounts(stats.bigrams());
        listener.update((wordRows + bigramRows) / totalUnits, "Saving " + label + ": word triples…");
        repository.addTrigramCounts(stats.trigrams());
    }

    /**
     * Builds the error for a repeat import, naming the file the text was first
     * imported as and when, in local time.
     */
    private static DuplicateImportException duplicateOf(CorpusRepository repository, String contentHash)
            throws SQLException {
        // The insert only skips when a row with this hash exists, so it can't be missing here
        CorpusRepository.SourceFile earlier = repository.findSourceFile(contentHash).orElseThrow();
        String importedAt = earlier.importedAt()
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DATE_FORMAT);
        return new DuplicateImportException(String.format("This text was already imported as %s on %s.",
                earlier.fileName(), importedAt));
    }
}
