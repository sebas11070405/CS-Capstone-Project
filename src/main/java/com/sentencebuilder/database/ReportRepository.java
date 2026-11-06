/*
 * The queries behind the word report and the duplicates report, and the one
 * that saves a generated sentence so the duplicates report has data. Every
 * method uses the connection the caller passes in, like WordRepository.
 */

package com.sentencebuilder.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReportRepository
{

    /** The columns the word report can be sorted by. Only these names ever reach the SQL. */
    public enum WordSort {
        WORD("word"), TOTAL("total_count"), START("start_count"), END("end_count");

        private final String column;

        WordSort(String column) {
            this.column = column;
        }

        /** Returns the words table column to sort by. */
        public String column() {
            return column;
        }
    }

    /** One word with how often it appears overall, at the start of a sentence, and at the end. */
    public record WordReportRow(String word, int totalCount, int startCount, int endCount) {}

    /** A generated sentence that appeared more than once, and when it first and last appeared. */
    public record DuplicateRow(String sentence, int timesGenerated,
                               OffsetDateTime firstGenerated, OffsetDateTime lastGenerated) {}

    // Groups on the md5 so it can use ix_gen_text; min() picks one copy of the shared text
    private static final String DUPLICATES = """
            SELECT min(sentence_text) AS sentence,
                   count(*)           AS times_generated,
                   min(generated_at)  AS first_generated,
                   max(generated_at)  AS last_generated
            FROM generated_sentences
            GROUP BY md5(sentence_text)
            HAVING count(*) > 1
            ORDER BY times_generated DESC, sentence
            """;

    // Selects the start word's id, so an unknown start word inserts nothing instead of failing
    private static final String SAVE_GENERATED = """
            INSERT INTO generated_sentences (sentence_text, start_word_id, algorithm)
            SELECT ?, word_id, ? FROM words WHERE word = ?
            """;

    /**
     * Returns every word with its counts, sorted by the chosen column. Only words
     * containing the given text are returned (blank means all), and only words
     * seen at least minTotalCount times. Ties are broken alphabetically.
     */
    public List<WordReportRow> wordReport(Connection conn, WordSort sortBy, boolean descending,
                                          String containing, int minTotalCount) throws SQLException {
        String text = containing == null ? "" : containing.trim().toLowerCase(Locale.ROOT);

        // Only the enum's fixed column name and a fixed direction are added to the SQL text;
        // everything the caller typed goes through ? parameters
        String sql = "SELECT word, total_count, start_count, end_count FROM words "
                + "WHERE total_count >= ? "
                + (text.isEmpty() ? "" : "AND word LIKE ? ESCAPE '\\' ")
                + "ORDER BY " + sortBy.column() + (descending ? " DESC" : " ASC") + ", word";

        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setInt(1, minTotalCount);
            if (!text.isEmpty()) {
                statement.setString(2, "%" + escapeLike(text) + "%");
            }
            List<WordReportRow> rows = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new WordReportRow(results.getString("word"), results.getInt("total_count"),
                            results.getInt("start_count"), results.getInt("end_count")));
                }
            }
            return rows;
        }
    }

    /** Returns the generated sentences produced more than once, most repeated first. */
    public List<DuplicateRow> duplicateReport(Connection conn) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(DUPLICATES);
             ResultSet results = statement.executeQuery()) {
            List<DuplicateRow> rows = new ArrayList<>();
            while (results.next()) {
                rows.add(new DuplicateRow(results.getString("sentence"), results.getInt("times_generated"),
                        results.getObject("first_generated", OffsetDateTime.class),
                        results.getObject("last_generated", OffsetDateTime.class)));
            }
            return rows;
        }
    }

    /**
     * Saves a generated sentence. The start word must already be in the words
     * table (use the normalized form). Returns false if it isn't, and saves nothing.
     */
    public boolean saveGenerated(Connection conn, String sentence, String startWord, String algorithm)
            throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(SAVE_GENERATED)) {
            statement.setString(1, sentence);
            statement.setString(2, algorithm);
            statement.setString(3, startWord);
            return statement.executeUpdate() == 1;
        }
    }

    /** Makes %, _ and \ match themselves in a LIKE pattern, so typing "50%" isn't a wildcard. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}