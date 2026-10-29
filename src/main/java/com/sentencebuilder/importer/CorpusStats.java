/*
 * Holds the counts the Importer writes to the database. For each normalized
 * word it records how often it appears in total, at the start of a sentence,
 * and at the end. It also tracks the total word count and a SHA-256 hash of
 * the tokenized text. The hash catches a repeat import of the same text, even
 * when the formatting or file name is different.
 *
 * Build one with CorpusStats.from(reader), or create one and call addSentence
 * for each tokenized sentence.
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

        // Words never contain spaces or newlines, so this encoding is unambiguous
        digest.update(String.join(" ", sentence).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    /** Returns the counts for each normalized word. The map is read-only. */
    public Map<String, WordCounts> words() {
        return Collections.unmodifiableMap(words);
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
