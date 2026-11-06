/*
 * Answers ReportRepository's questions from the Supabase database. Needs
 * DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 */

package com.sentencebuilder.repository;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.repository.ReportRows.DuplicateRow;
import com.sentencebuilder.repository.ReportRows.WordReportRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JdbcReportRepository implements ReportRepository {

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

    @Override
    public List<WordReportRow> wordReport(WordSort sortBy, boolean descending,
                                          String containing, int minTotalCount) {
        String text = containing == null ? "" : containing.trim().toLowerCase(Locale.ROOT);

        // Only the enum's fixed column name and a fixed direction are added to the SQL text;
        // everything the caller typed goes through ? parameters
        String sql = "SELECT word, total_count, start_count, end_count FROM words "
                + "WHERE total_count >= ? "
                + (text.isEmpty() ? "" : "AND word LIKE ? ESCAPE '\\' ")
                + "ORDER BY " + sortBy.column() + (descending ? " DESC" : " ASC") + ", word";

        try (Connection conn = Database.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
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
        } catch (SQLException e) {
            throw new RepositoryException("Could not load the word report", e);
        }
    }

    @Override
    public List<DuplicateRow> duplicateReport() {
        try (Connection conn = Database.getConnection();
             PreparedStatement statement = conn.prepareStatement(DUPLICATES);
             ResultSet results = statement.executeQuery()) {
            List<DuplicateRow> rows = new ArrayList<>();
            while (results.next()) {
                rows.add(new DuplicateRow(results.getString("sentence"), results.getInt("times_generated"),
                        results.getObject("first_generated", OffsetDateTime.class),
                        results.getObject("last_generated", OffsetDateTime.class)));
            }
            return rows;
        } catch (SQLException e) {
            throw new RepositoryException("Could not load the duplicates report", e);
        }
    }

    /** Makes %, _ and \ match themselves in a LIKE pattern, so typing "50%" doesn't act as a wildcard. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}