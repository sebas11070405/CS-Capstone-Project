/*
AI Assistance: Claude Sonnet 5.5

Prompt used: I am the database architect for a Java 21 / JavaFX project with PostgreSQL on Supabase 
via JDBC. Given database/schema.sql, implement WordListRepository in com.sentencebuilder.database 
for the Words screen. The repository accepts a Connection similar to WordDetailRepository and returns 
that file’s WordInfo record. Implement findWords and countWords. Order by word, total_count, start_count, 
or end_count via an enum corresponding to column names since ORDER BY is not parameterizable and order 
alphabetically in case of tie to guarantee page non-overlap. Apply filters by prefix (lowercase), minimum 
total, begins sentence, and ends sentence, constructing WHERE clause from the active filters with parameterization. 
Ensure limit >= 1 and offset >= 0. See docs/comment-style.md. Implement WordListRepositoryTest similar to 
WordDetailRepositoryTest: create fake words with a unique prefix, rollback, test all order options with tie breaks, 
every filter, pagination, counts, wildcard characters in prefix, and invalid data.
 */

/*
 * Tests WordListRepository on the shared database. Every test seeds fake words and rolls back at
 * the end, so nothing sticks around. Each query filters on the fake words' prefix, so real words in
 * the shared database never show up in the results. Needs .env.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.database.WordDetailRepository.WordInfo;
import com.sentencebuilder.database.WordListRepository.SortColumn;
import com.sentencebuilder.database.WordListRepository.WordFilter;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordListRepositoryTest {

    private static final String PREFIX = "zzqlist";   // Fake words so they can't clash with real ones
    private static final WordFilter FAKE_ONLY = new WordFilter(PREFIX, 0, false, false);

    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

    // Always rolls back, even if the test fails
    private static void rolledBack(DatabaseWork work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    // Counts as (total, start, end): a (5,2,0) b (9,0,3) c (1,1,1) d (5,0,0)
    private static void seed(Connection conn) throws SQLException {
        insertWord(conn, PREFIX + "a", 5, 2, 0);
        insertWord(conn, PREFIX + "b", 9, 0, 3);
        insertWord(conn, PREFIX + "c", 1, 1, 1);
        insertWord(conn, PREFIX + "d", 5, 0, 0);
    }

    private static void insertWord(Connection conn, String word, int total, int start, int end)
            throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO words (word, total_count, start_count, end_count) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, word);
            statement.setInt(2, total);
            statement.setInt(3, start);
            statement.setInt(4, end);
            statement.executeUpdate();
        }
    }

    // Just the words, in order, so each test can compare short lists
    private static List<String> names(List<WordInfo> words) {
        return words.stream().map(WordInfo::word).toList();
    }

    private static List<String> fake(String... suffixes) {
        return java.util.Arrays.stream(suffixes).map(suffix -> PREFIX + suffix).toList();
    }

    @Test
    void sortsAlphabeticallyEitherWay() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            assertEquals(fake("a", "b", "c", "d"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.WORD, false, 10, 0)));
            assertEquals(fake("d", "c", "b", "a"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.WORD, true, 10, 0)));
        });
    }

    @Test
    void sortsByTotalWithAlphabeticalTies() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            // a and d both have 5, so the alphabet decides in either direction
            assertEquals(fake("b", "a", "d", "c"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.TOTAL, true, 10, 0)));
            assertEquals(fake("c", "a", "d", "b"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.TOTAL, false, 10, 0)));
        });
    }

    @Test
    void sortsByStartAndEndCounts() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            assertEquals(fake("a", "c", "b", "d"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.START, true, 10, 0)));
            assertEquals(fake("b", "c", "a", "d"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.END, true, 10, 0)));
        });
    }

    @Test
    void returnsTheCountsOfEachWord() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            assertEquals(List.of(new WordInfo(PREFIX + "b", 9, 0, 3)),
                    new WordListRepository(conn).findWords(new WordFilter(PREFIX + "b", 0, false, false),
                            SortColumn.WORD, false, 10, 0));
        });
    }

    @Test
    void filtersByMinimumTotal() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            WordFilter atLeastFive = new WordFilter(PREFIX, 5, false, false);
            assertEquals(fake("a", "b", "d"),
                    names(repository.findWords(atLeastFive, SortColumn.WORD, false, 10, 0)));
        });
    }

    @Test
    void filtersToWordsThatStartOrEndSentences() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            assertEquals(fake("a", "c"), names(repository.findWords(
                    new WordFilter(PREFIX, 0, true, false), SortColumn.WORD, false, 10, 0)));
            assertEquals(fake("b", "c"), names(repository.findWords(
                    new WordFilter(PREFIX, 0, false, true), SortColumn.WORD, false, 10, 0)));
            // Both flags together keep only words that do both
            assertEquals(fake("c"), names(repository.findWords(
                    new WordFilter(PREFIX, 0, true, true), SortColumn.WORD, false, 10, 0)));
        });
    }

    @Test
    void prefixIsCaseInsensitiveAndLiteral() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            assertEquals(fake("a", "b", "c", "d"), names(repository.findWords(
                    new WordFilter("ZZQLIST", 0, false, false), SortColumn.WORD, false, 10, 0)));
            // A typed % is not a wildcard
            assertTrue(repository.findWords(new WordFilter("zzq%", 0, false, false),
                    SortColumn.WORD, false, 10, 0).isEmpty());
            assertTrue(repository.findWords(new WordFilter("zzqlis_", 0, false, false),
                    SortColumn.WORD, false, 10, 0).isEmpty());
        });
    }

    @Test
    void pagesDontOverlapOrSkipWords() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            // Sorted by total, where a and d tie, so this also checks that the tie-break is stable
            assertEquals(fake("b", "a"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.TOTAL, true, 2, 0)));
            assertEquals(fake("d", "c"),
                    names(repository.findWords(FAKE_ONLY, SortColumn.TOTAL, true, 2, 2)));
            assertTrue(repository.findWords(FAKE_ONLY, SortColumn.TOTAL, true, 2, 4).isEmpty());
        });
    }

    @Test
    void countsMatchesIgnoringPaging() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            WordListRepository repository = new WordListRepository(conn);
            assertEquals(4, repository.countWords(FAKE_ONLY));
            assertEquals(3, repository.countWords(new WordFilter(PREFIX, 5, false, false)));
            assertEquals(0, repository.countWords(new WordFilter("zzqnope", 0, false, false)));
        });
    }

    @Test
    void noFilterIncludesTheSeededWords() throws Exception {
        rolledBack(conn -> {
            seed(conn);
            // The shared database may hold real words too, so only check that ours are counted
            assertTrue(new WordListRepository(conn).countWords(WordFilter.none()) >= 4);
        });
    }

    @Test
    void badInputThrows() throws Exception {
        rolledBack(conn -> {
            WordListRepository repository = new WordListRepository(conn);
            assertThrows(IllegalArgumentException.class,
                    () -> repository.findWords(FAKE_ONLY, SortColumn.WORD, false, 0, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.findWords(FAKE_ONLY, SortColumn.WORD, false, 10, -1));
            assertThrows(IllegalArgumentException.class, () -> new WordFilter(PREFIX, -1, false, false));
        });
    }
}