/*
 * Splits plain text into sentences of normalized words. The import module counts
 * words, sentence starts and ends, and follow pairs from these sentences.
 * The rules are documented in docs/tokenizer-plan.md.
 *
 * Prompt used (AI-assisted with Claude Code):
 * "okay implement the plan"
 * The plan is docs/tokenizer-plan.md; the prompts used to write it are listed at the end of that file.
 */

package importer;

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
import java.util.regex.Pattern;

/**
 * Reads text and returns it one sentence at a time, each sentence being a list of
 * normalized words. Normalized words match the database's utf8mb4_unicode_ci collation,
 * so two words are equal here exactly when the words table treats them as the same word.
 */
public class Tokenizer {

    /** Longest word that fits the words.word column (VARCHAR(100)). */
    public static final int MAX_WORD_LENGTH = 100;

    // Words whose trailing period does not end a sentence, in normalized form
    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "st", "messrs", "mme", "mlle", "prof", "rev",
            "capt", "col", "gen", "lt", "sgt", "jr", "sr", "co", "vs", "mt");

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private enum Kind { WORD, NUMBER, TERMINATOR }

    // A piece of the text: a word, a number to skip, or a sentence terminator
    private static final class Token {
        final Kind kind;
        final String raw;
        final String word;   // normalized form; null for non-words and over-long words
        final int start;
        final int end;

        Token(Kind kind, String raw, String word, int start, int end) {
            this.kind = kind;
            this.raw = raw;
            this.word = word;
            this.start = start;
            this.end = end;
        }
    }

    private final BufferedReader reader;
    private final Deque<List<String>> pending = new ArrayDeque<>();
    private boolean finished;

    /**
     * Creates a tokenizer that reads from the given reader. The caller owns the reader
     * and is responsible for closing it.
     */
    public Tokenizer(Reader reader) {
        this.reader = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
    }

    /**
     * Returns the next sentence as a list of normalized words, or null when the input is
     * exhausted. Returned sentences are never empty.
     */
    public List<String> nextSentence() throws IOException {
        while (pending.isEmpty() && !finished) {
            String paragraph = readParagraph();
            if (paragraph == null) {
                finished = true;
            } else {
                splitParagraph(paragraph, pending);
            }
        }
        return pending.poll();
    }

    /** Tokenizes a whole string, such as generated sentences fed back into the corpus. */
    public static List<List<String>> tokenize(String text) {
        Tokenizer tokenizer = new Tokenizer(new StringReader(text));
        List<List<String>> sentences = new ArrayList<>();
        try {
            List<String> sentence;
            while ((sentence = tokenizer.nextSentence()) != null) {
                sentences.add(sentence);
            }
        } catch (IOException e) {
            // StringReader does not throw, but the signature requires handling it
            throw new UncheckedIOException(e);
        }
        return sentences;
    }

    /**
     * Normalizes a single word, such as one typed into auto-complete, the same way the
     * importer does. Surrounding punctuation is ignored. Returns null unless the text
     * contains exactly one word and no numbers.
     */
    public static String normalizeWord(String text) {
        String word = null;
        for (Token token : scan(text)) {
            if (token.kind == Kind.NUMBER) {
                return null;
            }
            if (token.kind == Kind.WORD) {
                if (word != null || token.word == null) {
                    return null;
                }
                word = token.word;
            }
        }
        return word;
    }

    // Joins lines up to the next blank line; returns null at the end of the input
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

    // Groups a paragraph's words into sentences; the paragraph's end always ends a sentence
    private static void splitParagraph(String paragraph, Deque<List<String>> sentences) {
        List<Token> tokens = scan(paragraph);
        List<String> sentence = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind == Kind.WORD) {
                if (token.word != null) {
                    sentence.add(token.word);
                }
                continue;
            }
            if (token.kind == Kind.NUMBER) {
                continue;
            }

            // Treat a run of terminators such as "?!" or ". . ." as one
            int last = i;
            while (last + 1 < tokens.size() && tokens.get(last + 1).kind == Kind.TERMINATOR) {
                last++;
            }
            Token before = i > 0 ? tokens.get(i - 1) : null;
            Token after = last + 1 < tokens.size() ? tokens.get(last + 1) : null;
            boolean lonePeriod = last == i && token.raw.equals(".");

            if (!sentence.isEmpty() && endsSentence(before, token, lonePeriod, after)) {
                sentences.add(sentence);
                sentence = new ArrayList<>();
            }
            i = last;
        }

        if (!sentence.isEmpty()) {
            sentences.add(sentence);
        }
    }

    // Decides whether a terminator ends the sentence, given the tokens around it
    private static boolean endsSentence(Token before, Token terminator, boolean lonePeriod, Token after) {
        boolean periodAttachedToWord = lonePeriod && before != null && before.kind == Kind.WORD
                && before.word != null && before.end == terminator.start;
        if (periodAttachedToWord) {
            if (ABBREVIATIONS.contains(before.word)) {
                return false;
            }
            // A single-letter initial such as "J." but not the pronoun "I."
            if (before.word.length() == 1 && !before.word.equals("i")) {
                return false;
            }
        }
        // A lowercase word after the terminator continues the sentence: "Oh! my dear"
        return after == null || after.kind != Kind.WORD || !Character.isLowerCase(after.raw.codePointAt(0));
    }

    // Splits text into words, numbers and terminators; everything else is a separator
    private static List<Token> scan(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (isLetter(cp) || Character.isDigit(cp)) {
                int start = i;
                boolean hasDigit = false;
                while (i < text.length()) {
                    int c = text.codePointAt(i);
                    if (Character.isDigit(c)) {
                        hasDigit = true;
                    } else if (!isLetter(c) && !isMark(c) && !isJoiner(text, i, c)) {
                        break;
                    }
                    i += Character.charCount(c);
                }
                String raw = text.substring(start, i);
                // Anything containing a digit, such as "1891", "221B" or "1st", is a number
                if (hasDigit) {
                    tokens.add(new Token(Kind.NUMBER, raw, null, start, i));
                } else {
                    tokens.add(new Token(Kind.WORD, raw, normalize(raw), start, i));
                }
            } else if (isTerminator(cp)) {
                tokens.add(new Token(Kind.TERMINATOR, text.substring(i, i + 1), null, i, i + 1));
                i++;
            } else {
                i += Character.charCount(cp);
            }
        }
        return tokens;
    }

    // Applies the schema-matching normalization; returns null if the result is too long
    private static String normalize(String raw) {
        String word = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        word = COMBINING_MARKS.matcher(word).replaceAll("");
        word = word.toLowerCase(Locale.ROOT)
                .replace("ß", "ss")
                .replace("œ", "oe")
                .replace('’', '\'')
                .replace('ʼ', '\'')
                .replace('‐', '-')
                .replace('‑', '-');
        return word.codePointCount(0, word.length()) <= MAX_WORD_LENGTH ? word : null;
    }

    // Only Latin-script letters form words; see the plan for why other scripts are excluded
    private static boolean isLetter(int cp) {
        return Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    // An apostrophe or single hyphen between letters, or a decimal point or comma inside a number.
    // Digits count too, so "’60’s" stays one token and is skipped as a number instead of leaving "s".
    private static boolean isJoiner(String text, int i, int c) {
        int next = i + 1 < text.length() ? text.codePointAt(i + 1) : -1;
        int previous = text.codePointBefore(i);
        if (isApostrophe(c) || isHyphen(c)) {
            return (isLetter(previous) || isMark(previous) || Character.isDigit(previous))
                    && next != -1 && (isLetter(next) || Character.isDigit(next));
        }
        if (c == '.' || c == ',') {
            return Character.isDigit(previous) && next != -1 && Character.isDigit(next);
        }
        return false;
    }

    private static boolean isApostrophe(int cp) {
        return cp == '\'' || cp == '’' || cp == 'ʼ';
    }

    private static boolean isHyphen(int cp) {
        return cp == '-' || cp == '‐' || cp == '‑';
    }

    private static boolean isTerminator(int cp) {
        return cp == '.' || cp == '!' || cp == '?' || cp == '…';
    }
}
