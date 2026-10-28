package com.sentencebuilder.importer;

import tokenizer.Tokenizer;

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

    private final Map<String, WordCounts> words = new HashMap<>();
    private final MessageDigest digest = newDigest();
    private int wordCount;

    public static CorpusStats from(Reader reader) throws IOException {
        CorpusStats stats = new CorpusStats();
        Tokenizer tokenizer = new Tokenizer(reader);
        List<String> sentence;
        while ((sentence = tokenizer.nextSentence()) != null) {
            stats.addSentence(sentence);
        }
        return stats;
    }

    public void addSentence(List<String> sentence) {
        if (sentence.isEmpty()) {
            return;
        }
        for (String word : sentence) {
            words.computeIfAbsent(word, w -> new WordCounts()).total++;
        }
        words.get(sentence.getFirst()).start++;
        words.get(sentence.getLast()).end++;
        wordCount += sentence.size();

        // Words never contain spaces or newlines, so this encoding is unambiguous
        digest.update(String.join(" ", sentence).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    public Map<String, WordCounts> words() {
        return Collections.unmodifiableMap(words);
    }

    public int wordCount() {
        return wordCount;
    }

    /** SHA-256 of the tokenized sentences, so formatting and line endings don't change it. */
    public String contentHash() {
        try {
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
            throw new IllegalStateException(e);
        }
    }

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
