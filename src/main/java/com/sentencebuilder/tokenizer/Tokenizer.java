/*
 * Splits plain text into sentences, each an ordered list of normalized words.
 * The importer, autocomplete, and user-supplied starting words all use it, so
 * a word is always stored and looked up the same way.
 *
 * A word is a run of Latin letters, which may contain apostrophes and single
 * hyphens ("don't", "well-known"). Words are lowercased, accents are removed,
 * and curly apostrophes become straight ones. Numbers and any token containing
 * a digit are skipped. So are words longer than the 100-character words.word
 * column.
 *
 * A sentence ends at ".", "!", "?", or an ellipsis, and at a blank line. A
 * single newline is treated as a space. A period after an abbreviation ("Mr.")
 * or an initial ("J.") doesn't end the sentence, and neither does "?", "!", or
 * an ellipsis followed by a lowercase word ("What?" he asked.). Known
 * limitation: a capitalized word after a quoted exclamation still splits, as
 * in "Seven!" I answered.
 *
 * Use new Tokenizer(reader).nextSentence() to stream a large file, tokenize()
 * for a short string, and normalizeWord() for one word typed by the user.
 *
 * AI-assisted: Claude Code
 *
 * Prompt:
 * I'm building a Java/JavaFX sentence builder app. Please write a Java Tokenizer class that turns plain
 * text into sentences, with each sentence returned as an ordered list of normalized words.
 *
 * Requirements:
 * - Treat words as runs of Latin letters, allowing apostrophes and single hyphens inside words, like
 *   "don't" and "well-known".
 * - Lowercase words consistently, normalize accents and curly apostrophes, and check the normalization
 *   against schema.sql.
 * - Skip numbers, tokens containing digits, and words longer than the database's 100-character limit.
 * - Use punctuation to detect sentence endings, accounting for common abbreviations like "Mr." and
 *   initials like "J. Watson".
 * - Treat single newlines as spaces and blank lines as sentence boundaries.
 * - Support reading from a Reader one sentence at a time, plus a helper for tokenizing a String.
 * - Keep database writes and word counting outside this class. The importer will use the output to
 *   count words, sentence starts and ends, and consecutive word pairs.
 *
 * Keep the implementation straightforward and explain any sentence-splitting limitations. Add JUnit
 * tests for contractions, hyphenated words, abbreviations, numbers, empty input, and text without final
 * punctuation.
 *
 * Follow-up prompt:
 * Please add a public static normalizeWord(String text) method to Tokenizer so autocomplete and
 * user-supplied starting words use the same normalization rules as imported text. Reuse the existing
 * logic. Return one normalized word, ignoring surrounding punctuation, or null for empty input, multiple
 * words, numbers, or words longer than 100 characters. Valid words don't need to already exist in the
 * database.
 *
 * Improve sentence handling so `"What?" he asked.` and `Wait... and see.` stay together, while
 * `Wait... Then go.` and `One. two.` split. Handle both "..." and "…", and include "Mme." and "Mlle." as
 * abbreviations.
 *
 * Keep the current simple structure and other behavior. Add tests for these cases and briefly explain
 * any remaining limitations.
 *
 * Follow-up prompt:
 * I noticed normalizeWord("hello𝟎") returns "hello" instead of null, and the accent test repeats "café"
 * even though the comment says one is decomposed. Please fix the Unicode digit handling, use
 * "cafe\u0301" for the decomposed case, and add a regression test. Keep everything else unchanged.
 */

package com.sentencebuilder.tokenizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class Tokenizer {

    // Length of the words.word column
    public static final int MAX_WORD_LENGTH = 100;

    // Stored without the period, the way they come out of toWord
    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "st", "messrs", "mme", "mlle", "prof", "rev",
            "capt", "col", "gen", "lt", "sgt", "jr", "sr", "co", "vs", "mt");

    private final BufferedReader reader;
    private final Deque<List<String>> pending = new ArrayDeque<>();   // Sentences split but not yet returned
    private boolean finished;                                         // True once the reader is exhausted

    /** Creates a tokenizer that reads from the given reader. The caller closes the reader. */
    public Tokenizer(Reader reader) {
        this.reader = new BufferedReader(reader);
    }

    /**
     * Returns the next sentence as a list of normalized words, or null at the end
     * of the input. Reads one paragraph at a time, so large files never have to
     * fit in memory.
     */
    public List<String> nextSentence() throws IOException {
        while (pending.isEmpty() && !finished) {
            String paragraph = readParagraph();
            if (paragraph == null) {
                finished = true;
            } else {
                pending.addAll(splitParagraph(paragraph));
            }
        }
        return pending.poll();
    }

    /** Tokenizes a whole string at once and returns every sentence in it. */
    public static List<List<String>> tokenize(String text) {
        Tokenizer tokenizer = new Tokenizer(new StringReader(text));
        List<List<String>> sentences = new ArrayList<>();
        try {
            List<String> sentence;
            while ((sentence = tokenizer.nextSentence()) != null) {
                sentences.add(sentence);
            }
        } catch (IOException e) {
            // A StringReader never actually throws
            throw new UncheckedIOException(e);
        }
        return sentences;
    }

    /**
     * Normalizes a single word the same way imported text is normalized. Returns null unless the
     * text holds exactly one valid word.
     */
    public static String normalizeWord(String text) {
        String word = null;
        int pos = 0;
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (isLatinLetter(ch) || isDigitAt(text, pos)) {
                // A second word means the text isn't a single word
                if (word != null) {
                    return null;
                }
                int start = pos;
                pos = wordEnd(text, pos);
                word = toWord(text.substring(start, pos));
                if (word == null) {
                    return null;
                }
            } else if (Character.isLetter(ch)) {
                // A non-Latin letter is another word, not punctuation
                return null;
            } else {
                pos++;
            }
        }
        return word;
    }

    /**
     * Reads lines up to the next blank line and joins them with spaces. Returns
     * null when there is no text left.
     */
    private String readParagraph() throws IOException {
        StringBuilder paragraph = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                paragraph.append(line).append(' ');
            } else if (!paragraph.isEmpty()) {
                return paragraph.toString();
            }
        }
        return !paragraph.isEmpty() ? paragraph.toString() : null;
    }

    /** Splits one paragraph into sentences of normalized words. Empty sentences are dropped. */
    private static List<List<String>> splitParagraph(String text) {
        List<List<String>> sentences = new ArrayList<>();
        List<String> sentence = new ArrayList<>();
        String lastWord = null;
        int lastWordEnd = -1;     // Tells "Mr." from "Mr ."

        // Walk the text, collecting words and ending a sentence at each terminator that really ends one
        int pos = 0;
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (isLatinLetter(ch) || isDigitAt(text, pos)) {
                int start = pos;
                pos = wordEnd(text, pos);
                lastWord = toWord(text.substring(start, pos));
                lastWordEnd = pos;
                if (lastWord != null) {
                    sentence.add(lastWord);
                }
            } else if (isTerminator(ch)) {
                // Treat a run like "?!" or "..." as one terminator
                int start = pos;
                while (pos < text.length() && isTerminator(text.charAt(pos))) {
                    pos++;
                }
                boolean keepsSentenceOpen;
                if (text.substring(start, pos).equals(".")) {
                    keepsSentenceOpen = lastWordEnd == start && lastWord != null
                            && (ABBREVIATIONS.contains(lastWord) || isInitial(lastWord));
                } else {
                    // “What?” he asked.
                    keepsSentenceOpen = nextWordIsLowercase(text, pos);
                }
                if (!keepsSentenceOpen && !sentence.isEmpty()) {
                    sentences.add(sentence);
                    sentence = new ArrayList<>();
                }
            } else {
                pos++;
            }
        }

        if (!sentence.isEmpty()) {
            sentences.add(sentence);
        }
        return sentences;
    }

    /** Returns the index just past the word that starts at start. */
    private static int wordEnd(String text, int start) {
        int end = start + 1;
        while (end < text.length() && isWordPart(text, end)) {
            end++;
        }
        return end;
    }

    /**
     * Turns a raw token into a stored word. Returns null for tokens that contain a
     * digit or are too long for the database.
     */
    private static String toWord(String raw) {
        if (raw.codePoints().anyMatch(Character::isDigit)) {
            return null;
        }
        String word = normalize(raw);
        return word.length() <= MAX_WORD_LENGTH ? word : null;
    }

    /**
     * Returns true if the next letter, digit, or terminator after pos is a
     * lowercase letter, which means the sentence continues.
     */
    private static boolean nextWordIsLowercase(String text, int pos) {
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (Character.isLetter(ch) || isDigitAt(text, pos) || isTerminator(ch)) {
                return Character.isLowerCase(ch);
            }
            pos++;
        }
        return false;
    }

    /**
     * Returns true if the character at pos continues the current word. Apostrophes
     * and hyphens count only between word characters, so quotes and dashes split words.
     */
    private static boolean isWordPart(String text, int pos) {
        char ch = text.charAt(pos);
        if (isLatinLetter(ch) || isDigitAt(text, pos) || isAccentMark(ch)) {
            return true;
        }
        char previous = text.charAt(pos - 1);
        boolean hasNext = pos + 1 < text.length();
        boolean previousIsDigit = isDigitAt(text, pos - 1);
        boolean nextIsDigit = hasNext && isDigitAt(text, pos + 1);
        boolean previousInWord = isLatinLetter(previous) || previousIsDigit || isAccentMark(previous);
        boolean nextInWord = (hasNext && isLatinLetter(text.charAt(pos + 1))) || nextIsDigit;
        if (isApostrophe(ch) || isHyphen(ch)) {
            return previousInWord && nextInWord;
        }
        // Keeps "3.50" whole so its period doesn't end the sentence
        if (ch == '.' || ch == ',') {
            return previousIsDigit && nextIsDigit;
        }
        return false;
    }

    // Also catches digits outside the BMP, which take two chars
    private static boolean isDigitAt(String text, int pos) {
        return Character.isDigit(text.codePointAt(pos)) || Character.isDigit(text.codePointBefore(pos + 1));
    }

    /**
     * Lowercases a word, strips its accents, and maps characters that should
     * match ("ß" and "ss", curly and straight apostrophes) to one form.
     */
    private static String normalize(String raw) {
        // NFKD splits "é" into "e" plus a combining accent, which is then dropped
        String word = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        StringBuilder result = new StringBuilder();
        for (char ch : word.toCharArray()) {
            if (!isAccentMark(ch)) {
                result.append(ch);
            }
        }
        // Locale.ROOT so "I" lowercases to "i" even on a Turkish system
        return result.toString().toLowerCase(Locale.ROOT)
                .replace("ß", "ss")
                .replace("œ", "oe")
                .replace('’', '\'')
                .replace('ʼ', '\'')
                .replace('‐', '-')
                .replace('‑', '-');
    }

    // "I." is the pronoun, not an initial
    private static boolean isInitial(String word) {
        return word.length() == 1 && !word.equals("i");
    }

    private static boolean isLatinLetter(char ch) {
        return Character.isLetter(ch) && Character.UnicodeScript.of(ch) == Character.UnicodeScript.LATIN;
    }

    private static boolean isAccentMark(char ch) {
        return Character.getType(ch) == Character.NON_SPACING_MARK;
    }

    private static boolean isTerminator(char ch) {
        return ch == '.' || ch == '!' || ch == '?' || ch == '…';
    }

    private static boolean isApostrophe(char ch) {
        return ch == '\'' || ch == '’' || ch == 'ʼ';
    }

    private static boolean isHyphen(char ch) {
        return ch == '-' || ch == '‐' || ch == '‑';
    }
}
