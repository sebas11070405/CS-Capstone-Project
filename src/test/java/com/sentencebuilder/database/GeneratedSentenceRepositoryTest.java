/*
 * Tests GeneratedSentenceRepository on the shared database. Every test seeds a fake start
 * word and rolls back at the end, so nothing sticks around. Needs .env.
 */

package com.sentencebuilder.database;

import com.sentencebuilder.database.GeneratedSentenceRepository.DuplicateGroup;
import com.sentencebuilder.database.GeneratedSentenceRepository.GeneratedSentence;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedSentenceRepositoryTest {

    // Fake word and sentences so they can't clash with real ones
    private static final String START = "zzqgen";
    private static final String SAME = "Zzqgen walks home.";
    private static final String OTHER = "Zzqgen runs home.";

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

    private static void seedStartWord(Connection conn) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO words (word, total_count) VALUES (?, 1)")) {
            statement.setString(1, START);
            statement.executeUpdate();
        }
    }

    @Test
    void savedSentencesComeBackNewestFirst() throws Exception {
        rolledBack(conn -> {
            seedStartWord(conn);
            GeneratedSentenceRepository repository = new GeneratedSentenceRepository(conn);
            repository.save(SAME, START, "weighted");
            repository.save(OTHER, START, "trigram");

            List<GeneratedSentence> recent = repository.findRecent(2);

            assertEquals(2, recent.size());
            assertEquals(OTHER, recent.get(0).text());
            assertEquals(START, recent.get(0).startWord());
            assertEquals("trigram", recent.get(0).algorithm());
            assertEquals(SAME, recent.get(1).text());
        });
    }

    @Test
    void repeatedSentencesShowUpAsDuplicates() throws Exception {
        rolledBack(conn -> {
            seedStartWord(conn);
            GeneratedSentenceRepository repository = new GeneratedSentenceRepository(conn);
            repository.save(SAME, START, "weighted");
            repository.save(SAME, START, "greedy");
            repository.save(SAME, START, "weighted");
            repository.save(OTHER, START, "weighted");

            // Only look at ours, the shared db may hold real duplicates too
            List<DuplicateGroup> mine = repository.findDuplicates(1000).stream()
                    .filter(group -> group.text().startsWith("Zzqgen")).toList();

            assertEquals(List.of(new DuplicateGroup(SAME, 3)), mine);
        });
    }

    @Test
    void unknownStartWordIsRejected() throws Exception {
        rolledBack(conn -> {
            GeneratedSentenceRepository repository = new GeneratedSentenceRepository(conn);
            assertThrows(IllegalArgumentException.class, () -> repository.save(SAME, "zzqnope", "weighted"));
        });
    }

    @Test
    void limitBelowOneThrows() throws Exception {
        rolledBack(conn -> {
            GeneratedSentenceRepository repository = new GeneratedSentenceRepository(conn);
            assertThrows(IllegalArgumentException.class, () -> repository.findRecent(0));
            assertThrows(IllegalArgumentException.class, () -> repository.findDuplicates(-1));
        });
    }
}
