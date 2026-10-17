/*
Prompt used (AI-assisted with Claude Code):

I'm building a Java/JavaFX sentence builder app. Please write a Java Tokenizer class that turns plain
text into sentences, with each sentence returned as an ordered list of normalized words.

Requirements:
- Treat words as runs of Latin letters, allowing apostrophes and single hyphens inside words, like
  "don't" and "well-known".
- Lowercase words consistently, normalize accents and curly apostrophes, and check the normalization
  against schema.sql.
- Skip numbers, tokens containing digits, and words longer than the database's 100-character limit.
- Use punctuation to detect sentence endings, accounting for common abbreviations like "Mr." and
  initials like "J. Watson".
- Treat single newlines as spaces and blank lines as sentence boundaries.
- Support reading from a Reader one sentence at a time, plus a helper for tokenizing a String.
- Keep database writes and word counting outside this class. The importer will use the output to
  count words, sentence starts and ends, and consecutive word pairs.

Keep the implementation straightforward and explain any sentence-splitting limitations. Add JUnit
tests for contractions, hyphenated words, abbreviations, numbers, empty input, and text without final
punctuation.

Follow-up prompt:

Please add a public static normalizeWord(String text) method to Tokenizer so autocomplete and
user-supplied starting words use the same normalization rules as imported text. Reuse the existing
logic. Return one normalized word, ignoring surrounding punctuation, or null for empty input, multiple
words, numbers, or words longer than 100 characters. Valid words don't need to already exist in the
database.

Improve sentence handling so `"What?" he asked.` and `Wait... and see.` stay together, while
`Wait... Then go.` and `One. two.` split. Handle both "..." and "…", and include "Mme." and "Mlle." as
abbreviations.

Keep the current simple structure and other behavior. Add tests for these cases and briefly explain
any remaining limitations.
*/

package tokenizer;

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

/**
 * Splits plain text into sentences, each an ordered list of normalized words.
 *
 * <p>Word rules:
 * <ul>
 *   <li>A word is a run of Latin letters. An apostrophe or a single hyphen between letters stays
 *       inside the word ("don't", "well-known"); anything else, including "--" and em dashes,
 *       separates words.</li>
 *   <li>Words are normalized to match the {@code utf8mb4_unicode_ci} collation in schema.sql, which
 *       ignores case and accents: lowercase, accents removed ("café" becomes "cafe"), "ß" becomes
 *       "ss", "œ" becomes "oe", and curly apostrophes become straight ones. Letters the collation
 *       keeps separate, such as "æ", "ø" and "ł", are left as they are.</li>
 *   <li>Anything containing a digit ("1891", "221B", "3.50") is skipped, as is any word longer than
 *       the {@code words.word} column (100 characters). Skipping does not end the sentence.</li>
 * </ul>
 *
 * <p>Sentence rules:
 * <ul>
 *   <li>A sentence ends at ".", "!", "?" or an ellipsis ("..." or "…"). A run of terminators such
 *       as "?!" or "..." counts as one.</li>
 *   <li>A single period right after a common abbreviation ("Mr.", "Dr.", "Mme.") or a single-letter
 *       initial ("J. Watson") does not end the sentence. The pronoun "I." still does.</li>
 *   <li>After "!", "?" or an ellipsis, a lowercase next word continues the sentence:
 *       "“What?” he asked." and "Wait... and see." stay together. After a single period the
 *       sentence always ends, so "One. two." splits.</li>
 *   <li>A single newline is a space; a blank line always ends a sentence. The end of the input ends
 *       the last sentence even without final punctuation.</li>
 *   <li>Sentences with no words are never returned.</li>
 * </ul>
 *
 * <p>Known sentence-splitting limitations:
 * <ul>
 *   <li>A capitalized word after "!" or "?" always starts a new sentence, even when it continues a
 *       quote: "“Seven!” I answered." and "“Who?” Holmes asked." split after the quote.</li>
 *   <li>A spaced ellipsis (". . .") is read as separate periods, so it always ends the sentence.</li>
 *   <li>An abbreviation or initial at the true end of a sentence ("He lives on Baker St.") does not
 *       end it, so it joins the next sentence.</li>
 *   <li>Abbreviations not in the list, such as "etc.", end the sentence.</li>
 * </ul>
 *
 * <p>This class only splits text. Database writes and word counting belong to the importer.
 */
public class Tokenizer {

    /** Longest word that fits the words.word column (VARCHAR(100)). */
    public static final int MAX_WORD_LENGTH = 100;

    // Words whose trailing period does not end a sentence, in normalized form
    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "st", "messrs", "mme", "mlle", "prof", "rev",
            "capt", "col", "gen", "lt", "sgt", "jr", "sr", "co", "vs", "mt");

    private final BufferedReader reader;
    private final Deque<List<String>> pending = new ArrayDeque<>();
    private boolean finished;

    /**
     * Creates a tokenizer that reads from the given reader. The caller owns the reader and is
     * responsible for closing it.
     */
    public Tokenizer(Reader reader) {
        this.reader = new BufferedReader(reader);
    }

    /**
     * Returns the next sentence as a list of normalized words, or null when the input is
     * exhausted. Reads one paragraph at a time, so large files are not loaded all at once.
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

    /** Tokenizes a whole string, such as a generated sentence fed back into the database. */
    public static List<List<String>> tokenize(String text) {
        Tokenizer tokenizer = new Tokenizer(new StringReader(text));
        List<List<String>> sentences = new ArrayList<>();
        try {
            List<String> sentence;
            while ((sentence = tokenizer.nextSentence()) != null) {
                sentences.add(sentence);
            }
        } catch (IOException e) {
            // StringReader never throws, but the signature requires handling it
            throw new UncheckedIOException(e);
        }
        return sentences;
    }

    /**
     * Normalizes a single word, such as one typed into auto-complete or chosen as a starting word,
     * the same way imported text is normalized. Surrounding punctuation is ignored, so "Hello," gives
     * "hello". Returns null for empty input, more than one word, anything containing a digit, or a
     * word longer than {@link #MAX_WORD_LENGTH}. The word does not have to exist in the database.
     */
    public static String normalizeWord(String text) {
        String word = null;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isLatinLetter(c) || Character.isDigit(c)) {
                if (word != null) {
                    return null;   // a second word
                }
                int start = i;
                i = wordEnd(text, i);
                word = toWord(text.substring(start, i));
                if (word == null) {
                    return null;   // a number or an over-long word
                }
            } else if (Character.isLetter(c)) {
                return null;   // a non-Latin letter is another word, not punctuation
            } else {
                i++;
            }
        }
        return word;
    }

    // Joins lines up to the next blank line with spaces; returns null at the end of the input
    private String readParagraph() throws IOException {
        StringBuilder paragraph = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                paragraph.append(line).append(' ');
            } else if (paragraph.length() > 0) {
                return paragraph.toString();
            }
        }
        return paragraph.length() > 0 ? paragraph.toString() : null;
    }

    // Walks the paragraph one character at a time, collecting words and ending sentences at terminators
    private static List<List<String>> splitParagraph(String text) {
        List<List<String>> sentences = new ArrayList<>();
        List<String> sentence = new ArrayList<>();
        String lastWord = null;   // normalized form of the most recent word
        int lastWordEnd = -1;     // index just after it, to tell "Mr." from "Mr ."

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isLatinLetter(c) || Character.isDigit(c)) {
                int start = i;
                i = wordEnd(text, i);
                lastWord = toWord(text.substring(start, i));
                lastWordEnd = i;
                if (lastWord != null) {
                    sentence.add(lastWord);
                }
            } else if (isTerminator(c)) {
                // Treat a run of terminators such as "?!" or "..." as one
                int start = i;
                while (i < text.length() && isTerminator(text.charAt(i))) {
                    i++;
                }
                boolean keepsSentenceOpen;
                if (text.substring(start, i).equals(".")) {
                    // "Mr." and "J." don't end a sentence; any other single period does, even in "One. two."
                    keepsSentenceOpen = lastWordEnd == start && lastWord != null
                            && (ABBREVIATIONS.contains(lastWord) || isInitial(lastWord));
                } else {
                    // After "!", "?" or an ellipsis, a lowercase word continues: "“What?” he asked."
                    keepsSentenceOpen = nextWordIsLowercase(text, i);
                }
                if (!keepsSentenceOpen && !sentence.isEmpty()) {
                    sentences.add(sentence);
                    sentence = new ArrayList<>();
                }
            } else {
                i++;
            }
        }

        // The end of a paragraph always ends a sentence
        if (!sentence.isEmpty()) {
            sentences.add(sentence);
        }
        return sentences;
    }

    // Returns the index just after the word or number that starts at start
    private static int wordEnd(String text, int start) {
        int i = start + 1;
        while (i < text.length() && isWordPart(text, i)) {
            i++;
        }
        return i;
    }

    // Normalizes a scanned word; returns null for numbers and words too long for the database
    private static String toWord(String raw) {
        if (raw.chars().anyMatch(Character::isDigit)) {
            return null;
        }
        String word = normalize(raw);
        return word.length() <= MAX_WORD_LENGTH ? word : null;
    }

    // Whether the next word after index i, skipping quotes and spaces, starts with a lowercase letter
    private static boolean nextWordIsLowercase(String text, int i) {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || isTerminator(c)) {
                return Character.isLowerCase(c);
            }
            i++;
        }
        return false;
    }

    // Whether the character at i continues the current word or number
    private static boolean isWordPart(String text, int i) {
        char c = text.charAt(i);
        if (isLatinLetter(c) || Character.isDigit(c) || isAccentMark(c)) {
            return true;
        }
        char previous = text.charAt(i - 1);
        char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
        boolean previousInWord = isLatinLetter(previous) || Character.isDigit(previous) || isAccentMark(previous);
        boolean nextInWord = isLatinLetter(next) || Character.isDigit(next);
        // An apostrophe or single hyphen joins two parts of a word: "don't", "well-known"
        if (isApostrophe(c) || isHyphen(c)) {
            return previousInWord && nextInWord;
        }
        // A decimal point or thousands comma keeps a number together, so "3.50" doesn't end a sentence
        if (c == '.' || c == ',') {
            return Character.isDigit(previous) && Character.isDigit(next);
        }
        return false;
    }

    // Matches the utf8mb4_unicode_ci collation: case- and accent-insensitive
    private static String normalize(String raw) {
        String word = Normalizer.normalize(raw, Normalizer.Form.NFKD);   // "é" becomes "e" plus an accent mark
        StringBuilder result = new StringBuilder();
        for (char c : word.toCharArray()) {
            if (!isAccentMark(c)) {
                result.append(c);
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

    // A single letter followed by a period, like "J. Watson", is an initial; "I." is the pronoun
    private static boolean isInitial(String word) {
        return word.length() == 1 && !word.equals("i");
    }

    private static boolean isLatinLetter(char c) {
        return Character.isLetter(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN;
    }

    private static boolean isAccentMark(char c) {
        return Character.getType(c) == Character.NON_SPACING_MARK;
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '’' || c == 'ʼ';
    }

    private static boolean isHyphen(char c) {
        return c == '-' || c == '‐' || c == '‑';
    }
}
