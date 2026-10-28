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

Second follow-up prompt:

I noticed normalizeWord("hello𝟎") returns "hello" instead of null, and the accent test repeats "café"
even though the comment says one is decomposed. Please fix the Unicode digit handling, use
"cafe\u0301" for the decomposed case, and add a regression test. Keep everything else unchanged.
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

/** Splits plain text into sentences, each an ordered list of normalized words. */
public class Tokenizer {

    // Length of the words.word column
    public static final int MAX_WORD_LENGTH = 100;

    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "st", "messrs", "mme", "mlle", "prof", "rev",
            "capt", "col", "gen", "lt", "sgt", "jr", "sr", "co", "vs", "mt");

    private final BufferedReader reader;
    private final Deque<List<String>> pending = new ArrayDeque<>();
    private boolean finished;

    public Tokenizer(Reader reader) {
        this.reader = new BufferedReader(reader);
    }

    /** Returns the next sentence, or null at the end of the input. */
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

    public static List<List<String>> tokenize(String text) {
        Tokenizer tokenizer = new Tokenizer(new StringReader(text));
        List<List<String>> sentences = new ArrayList<>();
        try {
            List<String> sentence;
            while ((sentence = tokenizer.nextSentence()) != null) {
                sentences.add(sentence);
            }
        } catch (IOException e) {
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
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isLatinLetter(c) || isDigitAt(text, i)) {
                if (word != null) {
                    return null;
                }
                int start = i;
                i = wordEnd(text, i);
                word = toWord(text.substring(start, i));
                if (word == null) {
                    return null;
                }
            } else if (Character.isLetter(c)) {
                return null;   // a non-Latin letter is another word, not punctuation
            } else {
                i++;
            }
        }
        return word;
    }

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

    private static List<List<String>> splitParagraph(String text) {
        List<List<String>> sentences = new ArrayList<>();
        List<String> sentence = new ArrayList<>();
        String lastWord = null;
        int lastWordEnd = -1;     // tells "Mr." from "Mr ."

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isLatinLetter(c) || isDigitAt(text, i)) {
                int start = i;
                i = wordEnd(text, i);
                lastWord = toWord(text.substring(start, i));
                lastWordEnd = i;
                if (lastWord != null) {
                    sentence.add(lastWord);
                }
            } else if (isTerminator(c)) {
                int start = i;
                while (i < text.length() && isTerminator(text.charAt(i))) {
                    i++;
                }
                boolean keepsSentenceOpen;
                if (text.substring(start, i).equals(".")) {
                    keepsSentenceOpen = lastWordEnd == start && lastWord != null
                            && (ABBREVIATIONS.contains(lastWord) || isInitial(lastWord));
                } else {
                    // “What?” he asked.
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

        if (!sentence.isEmpty()) {
            sentences.add(sentence);
        }
        return sentences;
    }

    private static int wordEnd(String text, int start) {
        int i = start + 1;
        while (i < text.length() && isWordPart(text, i)) {
            i++;
        }
        return i;
    }

    private static String toWord(String raw) {
        if (raw.codePoints().anyMatch(Character::isDigit)) {
            return null;
        }
        String word = normalize(raw);
        return word.length() <= MAX_WORD_LENGTH ? word : null;
    }

    private static boolean nextWordIsLowercase(String text, int i) {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || isDigitAt(text, i) || isTerminator(c)) {
                return Character.isLowerCase(c);
            }
            i++;
        }
        return false;
    }

    private static boolean isWordPart(String text, int i) {
        char c = text.charAt(i);
        if (isLatinLetter(c) || isDigitAt(text, i) || isAccentMark(c)) {
            return true;
        }
        char previous = text.charAt(i - 1);
        boolean hasNext = i + 1 < text.length();
        boolean previousIsDigit = isDigitAt(text, i - 1);
        boolean nextIsDigit = hasNext && isDigitAt(text, i + 1);
        boolean previousInWord = isLatinLetter(previous) || previousIsDigit || isAccentMark(previous);
        boolean nextInWord = (hasNext && isLatinLetter(text.charAt(i + 1))) || nextIsDigit;
        if (isApostrophe(c) || isHyphen(c)) {
            return previousInWord && nextInWord;
        }
        // Keeps "3.50" whole so its period doesn't end the sentence
        if (c == '.' || c == ',') {
            return previousIsDigit && nextIsDigit;
        }
        return false;
    }

    // Also catches digits outside the BMP, which take two chars
    private static boolean isDigitAt(String text, int i) {
        return Character.isDigit(text.codePointAt(i)) || Character.isDigit(text.codePointBefore(i + 1));
    }

    private static String normalize(String raw) {
        String word = Normalizer.normalize(raw, Normalizer.Form.NFKD);
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

    // "I." is the pronoun, not an initial
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
