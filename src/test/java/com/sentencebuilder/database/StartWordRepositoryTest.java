/*
 * Checks that randomStartWord picks only words with start_count > 0, gives
 * each an equal chance, can return the same word again, and returns empty when
 * no word qualifies.
 *
 * They need a valid .env and network access. Each test creates a temporary
 * words table that hides the shared one for its own connection only, and
 * rolls everything back at the end, so shared data is never read, changed, or
 * locked. Run them with ./gradlew test.
 *
 * AI-assisted: Claude Code
 *
 * Prompt: see src/main/java/com/sentencebuilder/database/StartWordRepository.java
 */

package com.sentencebuilder.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartWordRepositoryTest {

    // Enough picks that a fair 50/50 split lands between PICK_MIN and PICK_MAX almost surely (about 8
    // standard deviations), while weighting by start_count (1 in 6) would fall far below PICK_MIN
    private static final int PICKS = 400;
    private static final int PICK_MIN = 120;
    private static final int PICK_MAX = 280;

    // Same columns as words in schema.sql, written out so the shared table isn't touched at all.
    // A temporary table comes first in the search path, so "words" means this copy on this connection.
    private static final String CREATE_TEMP_WORDS = """
            CREATE TEMP TABLE words
            (
                word_id     INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                word        VARCHAR(100) NOT NULL UNIQUE,
                total_count INT          NOT NULL DEFAULT 0,
                start_count INT          NOT NULL DEFAULT 0,
                end_count   INT          NOT NULL DEFAULT 0
            ) ON COMMIT DROP
            """;

    /** Database work a test passes in as a lambda. Unlike Runnable, it may throw. */
    private interface DatabaseWork {
        void run(Connection conn) throws Exception;
    }

    /**
     * Runs the work against an empty temporary words table, and always rolls back
     * so the table disappears even if the work fails. First checks that "words"
     * really resolves to the temporary copy, so a test can't hit shared data.
     */
    private static void inTemporaryWords(DatabaseWork work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement statement = conn.createStatement()) {
                    statement.execute(CREATE_TEMP_WORDS);
                }
                try (Statement statement = conn.createStatement();
                     ResultSet rs = statement.executeQuery(
                             "SELECT relnamespace = pg_my_temp_schema() FROM pg_class "
                                     + "WHERE oid = to_regclass('words')")) {
                    assertTrue(rs.next() && rs.getBoolean(1), "words does not resolve to the temporary table");
                }
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    /** Adds a word with the given start count to the temporary words table. */
    private static void addWord(Connection conn, String word, int startCount) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO words (word, total_count, start_count) VALUES (?, ?, ?)")) {
            statement.setString(1, word);
            statement.setInt(2, Math.max(startCount, 1));
            statement.setInt(3, startCount);
            statement.executeUpdate();
        }
    }

    @Test
    void emptyTableHasNoStartWord() throws Exception {
        inTemporaryWords(conn ->
                assertEquals(Optional.empty(), new StartWordRepository(conn).randomStartWord()));
    }

    @Test
    void wordsThatNeverStartASentenceAreNotPicked() throws Exception {
        inTemporaryWords(conn -> {
            addWord(conn, "middle", 0);
            addWord(conn, "ending", 0);
            assertEquals(Optional.empty(), new StartWordRepository(conn).randomStartWord());
        });
    }

    @Test
    void sameWordCanBePickedAgain() throws Exception {
        inTemporaryWords(conn -> {
            addWord(conn, "only", 3);
            StartWordRepository repository = new StartWordRepository(conn);
            for (int pick = 0; pick < 3; pick++) {
                assertEquals(Optional.of("only"), repository.randomStartWord());
            }
        });
    }

    @Test
    void eligibleWordsHaveAnEqualChance() throws Exception {
        inTemporaryWords(conn -> {
            addWord(conn, "rare", 1);
            addWord(conn, "common", 5);   // Five times the starts, but should be picked just as often
            addWord(conn, "never", 0);
            StartWordRepository repository = new StartWordRepository(conn);

            // Tally the picks; every one must be an eligible word
            Map<String, Integer> tally = new HashMap<>();
            for (int pick = 0; pick < PICKS; pick++) {
                String word = repository.randomStartWord().orElseThrow();
                tally.merge(word, 1, Integer::sum);
            }

            assertEquals(Set.of("rare", "common"), tally.keySet());
            int rarePicks = tally.get("rare");
            assertTrue(rarePicks >= PICK_MIN && rarePicks <= PICK_MAX,
                    "rare was picked " + rarePicks + " of " + PICKS + " times");
        });
    }
}
