package com.sentencebuilder.importer;

import tokenizer.Tokenizer;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CorpusStats {

    private final Map<String, WordCounts> words = new HashMap<>();
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
    }

    public Map<String, WordCounts> words() {
        return Collections.unmodifiableMap(words);
    }

    public int wordCount() {
        return wordCount;
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
