/*
 * Records generated sentences in the generated_sentences table, so the user can look back at what
 * was made and find duplicates (spec 5, issue 50). The Generate tab records each sentence it makes,
 * and SentenceBatchService records each batch. Recording is separate from Add to Corpus: every
 * generated sentence is recorded, whether or not it is ever added to the corpus.
 *
 * Each sentence is saved as raw, unformatted text, because duplicates are matched on the exact
 * text. The algorithm is saved as its Algorithm name (for example "WEIGHTED_RANDOM"), so every
 * caller labels the same algorithm the same way. Start words must already be in the words table.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database), and the generated_sentences table
 * from database/schema.sql.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.GeneratedSentenceRepository;
import com.sentencebuilder.generator.SentenceBatchService.Algorithm;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class SentenceHistory {

    /** Private because everything is static. Call SentenceHistory.record instead. */
    private SentenceHistory() {}

    /**
     * Saves each sentence with the word it started from and the algorithm that made it, all in one
     * transaction, so either the whole batch is recorded or none of it is. startWords and sentences
     * are matched by position. Throws IllegalArgumentException if the two lists differ in length or
     * a start word isn't in the words table.
     */
    public static void record(Algorithm algorithm, List<String> startWords, List<String> sentences)
            throws SQLException {
        Database.inTransaction(conn -> {
            write(conn, algorithm, startWords, sentences);
            return null;
        });
    }

    /**
     * Same as record, but on the given connection and without committing. Package-private so
     * SentenceHistoryTest can run it inside a transaction it rolls back.
     */
    static void write(Connection conn, Algorithm algorithm, List<String> startWords, List<String> sentences)
            throws SQLException {
        // Checked before saving anything, so a mismatch can't record sentences under the wrong word
        if (startWords.size() != sentences.size()) {
            throw new IllegalArgumentException("Got " + startWords.size() + " start words for "
                    + sentences.size() + " sentences");
        }

        GeneratedSentenceRepository repository = new GeneratedSentenceRepository(conn);
        for (int iSentence = 0; iSentence < sentences.size(); iSentence++) {
            repository.save(sentences.get(iSentence), startWords.get(iSentence), algorithm.name());
        }
    }
}
