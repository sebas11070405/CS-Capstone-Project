/*
 * Generates a batch of sentences for task 4.5: given how many sentences to
 * make, which algorithm to use, and the longest a sentence may be, it picks a
 * random starting word for each sentence with StartWordRepository and runs the
 * chosen generator once per sentence. It returns the sentences as a List in
 * the order they were made.
 *
 * Starting words are picked independently, so the same word (and so the same
 * sentence) can come up more than once in a batch. If no word has ever started
 * a sentence, generateBatch throws NoStartWordException before generating
 * anything. It only reads from the database. Saving the batch is left to the
 * caller (see Importer.importGenerated), and nothing here depends on the UI.
 *
 * Generators are created only in createGenerator, so a change to the generator
 * constructors needs only that method updated.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 *
 * AI-assisted: Claude Code
 *
 * Prompt:
 * Create a new branch from the latest main for the batch-generation part of task 4.5. Add a small
 * service that takes a sentence count, an algorithm choice, and a maximum word count. Use
 * StartWordRepository to pick a random starting word for each sentence, call the existing generators,
 * and return a List. Allow repeated starting words and sentences, validate the inputs, and handle
 * having no eligible starting words clearly.
 *
 * Use the current generator API. Keep generator creation in one place so we can adapt it when the
 * planned refactor lands. Keep this focused on generating the batch—no UI, automatic imports, or
 * changes to the generation algorithms.
 *
 * Follow docs/comment-style.md and include this prompt in the header. Add focused tests without
 * changing shared database data. Run the tests, disclose any exclusions, and summarize before pushing
 * or opening a PR.
 */

package com.sentencebuilder.generator;

import com.sentencebuilder.database.Database;
import com.sentencebuilder.database.StartWordRepository;
import com.sentencebuilder.repository.PostgresTransitionRepo;
import com.sentencebuilder.repository.PostgresTrigramRepo;
import com.sentencebuilder.repository.TransitionRepository;
import com.sentencebuilder.repository.TrigramRepo;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SentenceBatchService {

    /** The generation algorithms a batch can use, named as in database/schema.sql. */
    public enum Algorithm {
        MOST_LIKELY,       // FrequentWordGenerator: always the most frequent next word
        WEIGHTED_RANDOM,   // MarkovGenerator: next word chosen in proportion to how often it follows
        TRIGRAM_BACKOFF    // TrigramBackoffGenerator: uses the last two words, falling back to one
    }

    /**
     * Supplies the starting words for a batch, one per sentence, in order.
     * Throws NoStartWordException if there are none to pick from.
     */
    @FunctionalInterface
    interface StartWordPicker {
        List<String> pick(int count) throws SQLException, NoStartWordException;
    }

    private final StartWordPicker startWords;     // Database-backed unless a test supplies its own
    private final TransitionRepository bigrams;   // Next-word counts for every algorithm
    private final TrigramRepo trigrams;           // Two-word counts, used only by TRIGRAM_BACKOFF

    /** Creates a service that reads starting words and word counts from the shared database. */
    public SentenceBatchService() {
        this(SentenceBatchService::pickFromDatabase, new PostgresTransitionRepo(), new PostgresTrigramRepo());
    }

    /** Creates a service with the given sources. Package-private so tests can avoid the database. */
    SentenceBatchService(StartWordPicker startWords, TransitionRepository bigrams, TrigramRepo trigrams) {
        this.startWords = startWords;
        this.bigrams = bigrams;
        this.trigrams = trigrams;
    }

    /**
     * Generates sentenceCount sentences with the given algorithm, each starting
     * with its own randomly picked word and at most maxWords words long. Returns
     * them in the order they were made, repeats included. Throws
     * IllegalArgumentException if sentenceCount or maxWords is below 1 or the
     * algorithm is null, before anything is read.
     *
     * @throws NoStartWordException if no word has ever started a sentence
     */
    public List<String> generateBatch(int sentenceCount, Algorithm algorithm, int maxWords)
            throws SQLException, NoStartWordException {
        // Check every input up front, so a bad request never reaches the database
        if (sentenceCount < 1) {
            throw new IllegalArgumentException("Sentence count must be at least 1, but was " + sentenceCount);
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("Choose an algorithm to generate with");
        }
        if (maxWords < 1) {
            throw new IllegalArgumentException("Maximum word count must be at least 1, but was " + maxWords);
        }

        // Pick every starting word first, so the database connection is closed before generating
        List<String> starts = startWords.pick(sentenceCount);

        // A new generator per sentence, because each one is built around its starting word
        List<String> sentences = new ArrayList<>(sentenceCount);
        for (String startWord : starts) {
            sentences.add(createGenerator(algorithm, maxWords, startWord, bigrams, trigrams).generateSentence());
        }
        return sentences;
    }

    /**
     * Creates the generator for one sentence. This is the only place generators
     * are constructed, so it is the one method to change when their constructors
     * change. TrigramBackoffGenerator takes two starting words, but a batch has
     * only one, so the second is null and it falls back to bigrams for the
     * second word.
     */
    static AbstractGenerator createGenerator(Algorithm algorithm, int maxWords, String startWord,
                                             TransitionRepository bigrams, TrigramRepo trigrams) {
        return switch (algorithm) {
            case MOST_LIKELY -> new FrequentWordGenerator(maxWords, bigrams, startWord);
            case WEIGHTED_RANDOM -> new MarkovGenerator(maxWords, bigrams, startWord);
            case TRIGRAM_BACKOFF -> new TrigramBackoffGenerator(maxWords, bigrams, trigrams, startWord, null);
        };
    }

    /**
     * Picks count random starting words using the given repository, each pick
     * independent of the others, so repeats are allowed. Package-private so tests
     * can run it against a temporary table.
     *
     * @throws NoStartWordException if no word has a start_count above zero
     */
    static List<String> pickStartWords(StartWordRepository repository, int count)
            throws SQLException, NoStartWordException {
        List<String> words = new ArrayList<>(count);
        for (int pick = 0; pick < count; pick++) {
            Optional<String> word = repository.randomStartWord();
            // Empty means no eligible words at all, so stop rather than return a short batch
            if (word.isEmpty()) {
                throw new NoStartWordException("No word has started a sentence yet, so there is nothing "
                        + "to start from. Import a text file first.");
            }
            words.add(word.get());
        }
        return words;
    }

    /** Picks count starting words from the shared database on one connection, closed before returning. */
    private static List<String> pickFromDatabase(int count) throws SQLException, NoStartWordException {
        try (Connection conn = Database.getConnection()) {
            return pickStartWords(new StartWordRepository(conn), count);
        }
    }
}
