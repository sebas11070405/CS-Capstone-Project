/*
 * Test helper that runs database tests against a small made-up corpus. It loads
 * the text into the shared Supabase database inside a transaction and always
 * rolls it back, so nothing is left behind and the tests can check exact results.
 * Needs a valid .env.
 *
 * Tests should only use made-up words starting with "zzq", so real words in the
 * shared database can't change their results.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.importer.CorpusStats;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class TestCorpus {

    /** Test code passed in as a lambda. Unlike Runnable, it may throw. */
    public interface Work {
        void run(Connection conn, WordRepository repo) throws Exception;
    }

    /** Private because everything is static. */
    private TestCorpus() {}

    /**
     * Loads the text's word and bigram counts, runs the work, and rolls everything
     * back, even if the work fails. The work gets a repository on the same
     * connection, so it can see the uncommitted test data.
     */
    public static void with(String text, Work work) throws Exception {
        CorpusStats stats = CorpusStats.from(new StringReader(text));

        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                CorpusRepository corpus = new CorpusRepository(conn);
                corpus.addWordCounts(stats.words());   // First, because the bigram query looks up word ids
                corpus.addBigramCounts(stats.bigrams());
                work.run(conn, new WordRepository(conn));
            } finally {
                conn.rollback();
            }
        }
    }

    /** Returns true if the exact word is in the words table. */
    public static boolean wordIsStored(Connection conn, String word) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("SELECT 1 FROM words WHERE word = ?")) {
            statement.setString(1, word);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Returns how often users chose nextWord after word, or -1 if the pair isn't stored. */
    public static int userSelected(Connection conn, String word, String nextWord) throws SQLException {
        String sql = """
                SELECT b.user_selected
                FROM bigrams b
                JOIN words w1 ON b.word_id = w1.word_id
                JOIN words w2 ON b.next_word_id = w2.word_id
                WHERE w1.word = ? AND w2.word = ?
                """;
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, word);
            statement.setString(2, nextWord);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
