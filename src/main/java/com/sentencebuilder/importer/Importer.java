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
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 *
 * AI-assisted: Claude Sonnet 5.5 (write order for bigrams and trigrams)
 *
 * Prompt: see src/main/java/com/sentencebuilder/database/CorpusRepository.java
 *
 * AI-assisted: Claude Code (progress reporting, ported from PR #16)
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

        // Each table is saved in one statement, so progress moves once per table. It's weighted by row
        // count, since each row costs about the same to save.
        int wordRows = stats.words().size();
        int bigramRows = stats.bigrams().size();
        double totalRows = Math.max(1, wordRows + bigramRows + stats.trigrams().size());

        listener.update(0, "Saving " + fileName + ": words…");
        repository.addWordCounts(stats.words());   // First, because the n-gram queries look up word ids
        listener.update(wordRows / totalRows, "Saving " + fileName + ": word pairs…");
        repository.addBigramCounts(stats.bigrams());
        listener.update((wordRows + bigramRows) / totalRows, "Saving " + fileName + ": word triples…");
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
