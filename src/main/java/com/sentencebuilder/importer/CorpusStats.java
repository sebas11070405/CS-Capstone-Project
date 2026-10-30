/*
 * Holds the counts the Importer writes to the database. For each normalized
 * word it records how often it appears in total, at the start of a sentence,
 * and at the end. It also counts each pair and triple of consecutive words for
 * the bigrams and trigrams tables, only within a sentence, never across two.
 * It also tracks the total word count and a SHA-256 hash of the tokenized
 * text. The hash catches a repeat import of the same text, even when the
 * formatting or file name is different.
 *
 * Build one with CorpusStats.from(reader) for a file, fromSentences(list) for
 * generated sentences, or create one and call addSentence for each tokenized
 * sentence.
 */

package com.sentencebuilder.importer;

import com.sentencebuilder.tokenizer.Tokenizer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class CorpusStats {

    private final Map<String, WordCounts> words = new HashMap<>();   // Keyed by normalized word
    private final Map<List<String>, Integer> bigrams = new HashMap<>();    // [word, next word] -> count
    private final Map<List<String>, Integer> trigrams = new HashMap<>();   // [word1, word2, next] -> count
    private final MessageDigest digest = newDigest();                // Grows with each sentence added
    private int wordCount;                                           // Every word, including repeats

    /** Tokenizes all the reader's text and returns its counts. The caller closes the reader. */
    public static CorpusStats from(Reader reader) throws IOException {
        CorpusStats stats = new CorpusStats();
        Tokenizer tokenizer = new Tokenizer(reader);
        List<String> sentence;
        while ((sentence = tokenizer.nextSentence()) != null) {
            stats.addSentence(sentence);
        }
        return stats;
    }

    /**
     * Tokenizes a batch of generated sentences and returns their counts. Each
     * string is tokenized on its own, so no pair spans two generated sentences.
     */
    public static CorpusStats fromSentences(List<String> sentences) {
        CorpusStats stats = new CorpusStats();
        for (String text : sentences) {
            for (List<String> sentence : Tokenizer.tokenize(text)) {
                stats.addSentence(sentence);
            }
        }
        return stats;
    }

    /** Adds one tokenized sentence to the counts and the content hash. Empty sentences are ignored. */
    public void addSentence(List<String> sentence) {
        if (sentence.isEmpty()) {
            return;
        }
        for (String word : sentence) {
            words.computeIfAbsent(word, key -> new WordCounts()).total++;
        }
        words.get(sentence.getFirst()).start++;
        words.get(sentence.getLast()).end++;
        wordCount += sentence.size();

        // Count pairs and triples only inside this sentence, so the last word of one sentence is
        // never recorded as followed by the first word of the next
        for (int ix = 0; ix + 1 < sentence.size(); ix++) {
            bigrams.merge(List.of(sentence.get(ix), sentence.get(ix + 1)), 1, Integer::sum);
            if (ix + 2 < sentence.size()) {
                trigrams.merge(List.of(sentence.get(ix), sentence.get(ix + 1), sentence.get(ix + 2)),
                        1, Integer::sum);
            }
        }

        // Words never contain spaces or newlines, so this encoding is unambiguous
        digest.update(String.join(" ", sentence).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    /** Returns the counts for each normalized word. The map is read-only. */
    public Map<String, WordCounts> words() {
        return Collections.unmodifiableMap(words);
    }

    /** Returns how often each [word, next word] pair appears in a sentence. The map is read-only. */
    public Map<List<String>, Integer> bigrams() {
        return Collections.unmodifiableMap(bigrams);
    }

    /** Returns how often each [word1, word2, next word] triple appears in a sentence. Read-only. */
    public Map<List<String>, Integer> trigrams() {
        return Collections.unmodifiableMap(trigrams);
    }

    public int wordCount() {
        return wordCount;
    }

    /** SHA-256 of the tokenized sentences, so formatting and line endings don't change it. */
    public String contentHash() {
        try {
            // digest() resets the digest, so hash a copy to keep adding sentences afterward
            MessageDigest copy = (MessageDigest) digest.clone();
            return HexFormat.of().formatHex(copy.digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Can't happen: every Java runtime is required to support SHA-256
            throw new IllegalStateException(e);
        }
    }

    /** How often one word appears in total, at the start of a sentence, and at the end. */
    public static class WordCounts {
        private int total;
        private int start;
        private int end;

        public int total() {
            return total;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }
    }
}
