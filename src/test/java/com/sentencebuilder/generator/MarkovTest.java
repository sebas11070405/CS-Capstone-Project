//package com.sentencebuilder.generator;
//
//import com.sentencebuilder.database.Database;
//import com.sentencebuilder.database.WordRepository;
//import org.junit.jupiter.api.Test;
//
//import java.sql.Connection;
//import java.sql.SQLException;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Integration tests for the MarkovGenerator class.
// *
// * These tests verify that the Markov generator can generate
// * sentences using word transitions retrieved from the PostgreSQL
// * database and handle cases where no transitions are available.
// *
// * @see MarkovGenerator
// * @see WordRepository
// */
//public class MarkovTest {
//
//    /**
//     * Tests sentence generation using transitions from PostgreSQL.
//     * Verifies that the generated sentence is not null or empty.
//     */
//    @Test
//    public void testGetTransitions() throws SQLException {
//        try (Connection conn = Database.getConnection()) {
//            WordRepository repo = new WordRepository(conn);
//
//            MarkovGenerator generator = new MarkovGenerator(3, repo, "the");
//
//
//            String sentence = generator.generateSentence();
//
//            assertNotNull(sentence);
//            assertFalse(sentence.isEmpty());
//
//            System.out.println("Sentence: " + sentence);
//        }
//    }
//
//    /**
//     * Tests sentence generation when no word transitions exist.
//     *
//     * Uses a starting word that is not expected to exist
//     * in the database. When no transitions are available,
//     * the generator should stop and return only the starting word.
//     *
//     * Verifies that the generator handles missing transitions
//     * without throwing an exception.
//     */
//    @Test
//    public void testNoTransitions() throws SQLException {
//        try (Connection conn = Database.getConnection()) {
//            WordRepository repo = new WordRepository(conn);
//
//            MarkovGenerator generator = new MarkovGenerator(5, repo, "abcde");
//
//            String sentence = generator.generateSentence();
//
//            assertEquals("abcde", sentence);
//
//            System.out.println("Sentence: " + sentence);
//        }
//    }
//}
