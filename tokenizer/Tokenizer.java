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
 *   <li>A sentence ends at ".", "!", "?" or "…", except for a period right after a common
 *       abbreviation ("Mr.", "Dr.", "St.") or a single-letter initial ("J. Watson"). The pronoun
 *       "I." still ends a sentence.</li>
 *   <li>A single newline is a space; a blank line always ends a sentence. The end of the input ends
 *       the last sentence even without final punctuation.</li>
 *   <li>Sentences with no words are never returned.</li>
 * </ul>
 *
 * <p>Known sentence-splitting limitations:
 * <ul>
 *   <li>A terminator inside a quote is treated as the end of the sentence, so
 *       "“What?” he asked." becomes two sentences: [what] and [he, asked].</li>
 *   <li>Each period in an ellipsis can end a sentence, so "Wait... and see." splits after "wait".</li>
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
            "mr", "mrs", "ms", "dr", "st", "messrs", "prof", "rev",
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
                boolean hasDigit = false;
                while (i < text.length() && isWordPart(text, i)) {
                    hasDigit |= Character.isDigit(text.charAt(i));
                    i++;
                }
                lastWord = hasDigit ? null : normalize(text.substring(start, i));
                lastWordEnd = i;
                if (lastWord != null && lastWord.length() <= MAX_WORD_LENGTH) {
                    sentence.add(lastWord);
                }
            } else if (c == '.' || c == '!' || c == '?' || c == '…') {
                boolean keepsSentenceOpen = c == '.' && lastWordEnd == i && lastWord != null
                        && (ABBREVIATIONS.contains(lastWord) || isInitial(lastWord));
                if (!keepsSentenceOpen && !sentence.isEmpty()) {
                    sentences.add(sentence);
                    sentence = new ArrayList<>();
                }
                i++;
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

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '’' || c == 'ʼ';
    }

    private static boolean isHyphen(char c) {
        return c == '-' || c == '‐' || c == '‑';
    }
}
