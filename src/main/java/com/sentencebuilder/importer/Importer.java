/*
 * Reads a UTF-8 text file, counts its words, word pairs, and word triples with
 * CorpusStats, and adds those counts to the database. The source_files row and
 * all the counts are written in one transaction, so a failed import leaves the
 * database unchanged. If the same text was imported before (matched by content
 * hash), it throws DuplicateImportException and writes nothing. The SQL itself
 * lives in CorpusRepository.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 *
 * AI-assisted: Claude Sonnet 5.5 (write order for bigrams and trigrams)
 *
 * Prompt: see src/main/java/com/sentencebuilder/database/CorpusRepository.java
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
     * Imports a text file and returns its counts. Nothing is written if the text
     * was already imported or if any step fails.
     */
    public CorpusStats importFile(Path file) throws IOException, SQLException, DuplicateImportException {
        // Count the whole file before connecting, so the connection isn't held open while it's read
        CorpusStats stats;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stats = CorpusStats.from(reader);
        }

        String fileName = file.getFileName().toString();
        return Database.inTransaction(conn -> {
            write(conn, fileName, stats);
            return stats;
        });
    }

    /**
     * Writes the source_files row and the word, bigram, and trigram counts on the
     * given connection without committing. Package-private so ImporterTest can
     * run it inside a transaction it rolls back.
     */
    static void write(Connection conn, String fileName, CorpusStats stats)
            throws SQLException, DuplicateImportException {
        CorpusRepository repository = new CorpusRepository(conn);

        // Zero rows inserted means this text is already there, so name the earlier import and stop
        if (!repository.insertSourceFile(fileName, stats.wordCount(), stats.contentHash())) {
            throw duplicateOf(repository, stats.contentHash());
        }

        repository.addWordCounts(stats.words());   // First, because the n-gram queries look up word ids
        repository.addBigramCounts(stats.bigrams());
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
