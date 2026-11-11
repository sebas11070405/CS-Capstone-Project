/*
 * Pages through the words table for the Words screen's list and reports. The user picks a sort
 * column and direction and an optional filter, and gets one page of words with their counts plus
 * a total so the UI can show how many pages there are. Read only, so it never changes any counts.
 *
 * Runs on the connection you pass in. The filter prefix needs to be lowercase and accent-free like
 * stored words (see Tokenizer.normalizeWord); WordFilter lowercases it but does nothing more.
 *
 * Needs the words table from database/schema.sql.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.database.WordDetailRepository.WordInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WordListRepository {

    private static final String SELECT_WORDS = "SELECT word, total_count, start_count, end_count FROM words";

    private static final String COUNT_WORDS = "SELECT count(*) FROM words";

    private final Connection conn;   // Caller opens and closes it

    /**
     * The columns the list can be sorted by. ORDER BY can't take a query parameter, so each choice
     * carries its own column name. That keeps user input out of the SQL text.
     */
    public enum SortColumn {
        WORD("word"),
        TOTAL("total_count"),
        START("start_count"),
        END("end_count");

        private final String column;   // Real column name in the words table

        SortColumn(String column) {
            this.column = column;
        }
    }

    /**
     * What to leave out of the list. An empty prefix, a minTotal of 0, and false flags let
     * everything through. startersOnly keeps words that began at least one sentence, and
     * endersOnly keeps words that ended at least one.
     */
    public record WordFilter(String prefix, int minTotal, boolean startersOnly, boolean endersOnly) {

        /** Cleans up the prefix (null becomes empty, then trimmed and lowercased) and rejects a negative minTotal. */
        public WordFilter {
            if (minTotal < 0) {
                throw new IllegalArgumentException("minTotal can't be negative: " + minTotal);
            }
            prefix = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        }

        /** A filter that keeps every word. */
        public static WordFilter none() {
            return new WordFilter("", 0, false, false);
        }
    }

    /** Creates a repository that runs every query on the given connection. */
    public WordListRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Returns one page of words that pass the filter, sorted by the chosen column. Takes the sort
     * column, whether to sort high to low (or Z to A), how many rows to return (at least 1), and how
     * many matching rows to skip first (0 or more). Ties are broken alphabetically so pages never
     * overlap or skip a word. Returns an empty list if the page is past the last match.
     */
    public List<WordInfo> findWords(WordFilter filter, SortColumn sortBy, boolean descending,
                                    int limit, int offset) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset can't be negative: " + offset);
        }

        // Filter values become query parameters, and the sort comes from the enum, never from user text
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SELECT_WORDS).append(whereClause(filter, params));
        sql.append(" ORDER BY ").append(sortBy.column).append(descending ? " DESC" : " ASC");
        // Sorting by word is already unique, so only the count columns need a tie-breaker
        if (sortBy != SortColumn.WORD) {
            sql.append(", word");
        }
        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<WordInfo> words = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    words.add(new WordInfo(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
                }
            }
        }
        return words;
    }

    /**
     * Counts every word that passes the filter, ignoring paging. The UI divides this by the page
     * size to show how many pages there are.
     */
    public long countWords(WordFilter filter) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = COUNT_WORDS + whereClause(filter, params);
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();   // A count query always returns one row
                return rs.getLong(1);
            }
        }
    }

    /**
     * Builds the WHERE clause for a filter and adds its values to params in the order the ? marks
     * appear. Only the conditions the filter turns on are included. starts_with matches the prefix
     * literally, so a typed % or _ isn't treated as a wildcard the way it would be with LIKE.
     */
    private static String whereClause(WordFilter filter, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE true");
        if (!filter.prefix().isEmpty()) {
            where.append(" AND starts_with(word, ?)");
            params.add(filter.prefix());
        }
        if (filter.minTotal() > 0) {
            where.append(" AND total_count >= ?");
            params.add(filter.minTotal());
        }
        if (filter.startersOnly()) {
            where.append(" AND start_count > 0");
        }
        if (filter.endersOnly()) {
            where.append(" AND end_count > 0");
        }
        return where.toString();
    }

    /** Sets each value on the statement, in order, as parameters 1, 2, 3 and so on. */
    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int ix = 0; ix < params.size(); ix++) {
            statement.setObject(ix + 1, params.get(ix));
        }
    }
}