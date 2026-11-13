/*
 * Keeps track of every sentence the app generates, so the user can see what was made and
 * spot duplicates. Runs on the connection you pass in and never commits. The start word
 * has to already be in the words table (normalized, see Tokenizer.normalizeWord).
 * Duplicates are matched on the exact text, so save sentences in one consistent form.
 */

package com.sentencebuilder.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public final class GeneratedSentenceRepository {

    // Looks up the start word's id, inserts nothing if the word isn't stored
    private static final String INSERT_SENTENCE = """
            INSERT INTO generated_sentences (sentence_text, start_word_id, algorithm)
            SELECT ?, word_id, ? FROM words WHERE word = ?
            """;

    // Newest first, sentence_id breaks ties since one transaction shares one timestamp
    private static final String FIND_RECENT = """
            SELECT g.sentence_text, w.word, g.algorithm, g.generated_at
            FROM generated_sentences g
            JOIN words w ON w.word_id = g.start_word_id
            ORDER BY g.generated_at DESC, g.sentence_id DESC
            LIMIT ?
            """;

    // Groups on md5 so it can use the index on md5(sentence_text)
    private static final String FIND_DUPLICATES = """
            SELECT min(sentence_text), count(*)
            FROM generated_sentences
            GROUP BY md5(sentence_text)
            HAVING count(*) > 1
            ORDER BY count(*) DESC, min(sentence_text)
            LIMIT ?
            """;

    private final Connection conn;   // Caller opens, commits, and closes it

    /** One saved sentence with the word it started from and which generator made it. */
    public record GeneratedSentence(String text, String startWord, String algorithm,
                                    OffsetDateTime generatedAt) {}

    /** A sentence that was generated more than once, and how many times. */
    public record DuplicateGroup(String text, int timesGenerated) {}

    public GeneratedSentenceRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Saves one generated sentence. The algorithm is a short label like "trigram" (50 characters max).
     * Throws IllegalArgumentException if the start word isn't in the words table.
     */
    public void save(String sentence, String startWord, String algorithm) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(INSERT_SENTENCE)) {
            statement.setString(1, sentence);
            statement.setString(2, algorithm);
            statement.setString(3, startWord);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Start word is not stored: " + startWord);
            }
        }
    }

    /** Returns up to limit saved sentences, newest first. */
    public List<GeneratedSentence> findRecent(int limit) throws SQLException {
        checkLimit(limit);
        List<GeneratedSentence> sentences = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(FIND_RECENT)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    sentences.add(new GeneratedSentence(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class)));
                }
            }
        }
        return sentences;
    }

    /** Returns up to limit sentences generated more than once, most repeated first. */
    public List<DuplicateGroup> findDuplicates(int limit) throws SQLException {
        checkLimit(limit);
        List<DuplicateGroup> duplicates = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(FIND_DUPLICATES)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    duplicates.add(new DuplicateGroup(rs.getString(1), rs.getInt(2)));
                }
            }
        }
        return duplicates;
    }

    private static void checkLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
    }
}
