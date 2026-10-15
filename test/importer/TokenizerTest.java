/*
 * Unit tests for Tokenizer, one per rule in docs/tokenizer-plan.md.
 *
 * Prompt used (AI-assisted with Claude Code):
 * "okay implement the plan"
 * The plan is docs/tokenizer-plan.md; the prompts used to write it are listed at the end of that file.
 */

package importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    // Returns all words of the text in order, ignoring sentence boundaries
    private static List<String> words(String text) {
        return Tokenizer.tokenize(text).stream().flatMap(List::stream).toList();
    }

    private static List<List<String>> sentences(String text) {
        return Tokenizer.tokenize(text);
    }

    // Word rules

    @Test
    void lowercasesWords() {
        assertEquals(List.of("the", "cat", "sat"), words("The CAT Sat"));
    }

    @Test
    void keepsInternalApostrophes() {
        assertEquals(List.of("don't", "say", "it's", "holmes's"), words("Don't say it's Holmes's"));
    }

    @Test
    void normalizesCurlyApostrophes() {
        assertEquals(List.of("don't", "engineer's"), words("Don’t Engineer’s"));
    }

    @Test
    void stripsLeadingAndTrailingApostrophes() {
        assertEquals(List.of("tis", "hello", "holmes"), words("'Tis 'hello' Holmes'"));
        assertEquals(List.of("hello", "he", "said"), words("‘Hello,’ he said"));
    }

    @Test
    void keepsSingleHyphensBetweenLetters() {
        assertEquals(List.of("well-known", "mother-in-law"), words("well-known mother-in-law"));
    }

    @Test
    void treatsUnicodeHyphensAsHyphens() {
        assertEquals(List.of("red-headed", "red-headed"), words("Red‐Headed red‑headed"));
    }

    @Test
    void splitsOnDashes() {
        assertEquals(List.of("word", "word"), words("word--word"));
        assertEquals(List.of("observer", "excellent"), words("observer—excellent"));
        assertEquals(List.of("one", "two"), words("one–two"));
        assertEquals(List.of("trailing"), words("trailing-"));
    }

    @Test
    void stripsGutenbergItalics() {
        assertEquals(List.of("always", "the", "woman"), words("always _the_ woman"));
    }

    @Test
    void skipsNumbersWithoutBreakingSentences() {
        assertEquals(List.of(List.of("in", "he", "came")), sentences("In 1891 he came."));
        assertEquals(List.of(List.of("it", "cost", "pounds")), sentences("It cost 3.50 pounds."));
        assertEquals(List.of(List.of("lived", "at", "baker", "street")), sentences("Lived at 221B Baker Street."));
        assertEquals(List.of(List.of("the", "of", "may")), sentences("The 1st of May."));
        assertEquals(List.of(List.of("in", "the", "early", "at", "the", "diggings")),
                sentences("In the early ’60’s at the diggings."));
        assertEquals(List.of(List.of("a", "carriage")), sentences("A 4-wheeled carriage."));
    }

    @Test
    void storesAbbreviationsWithoutThePeriod() {
        assertEquals(List.of(List.of("mr", "holmes", "smiled")), sentences("Mr. Holmes smiled."));
    }

    @Test
    void treatsNonLatinLettersAsSeparators() {
        assertEquals(List.of("naive", "cafe"), words("naïve σοφία café"));
        assertEquals(List.of("da", "net"), words("da да net"));
    }

    @Test
    void skipsWordsLongerThanTheColumn() {
        String hundred = "a".repeat(100);
        assertEquals(List.of(hundred), words(hundred));
        assertEquals(List.of("before", "after"), words("before " + "b".repeat(101) + " after"));
    }

    // Normalization matching utf8mb4_unicode_ci

    @Test
    void mergesWhatTheCollationMerges() {
        assertEquals("cafe", Tokenizer.normalizeWord("café"));
        assertEquals("cafe", Tokenizer.normalizeWord("café"));   // decomposed accent
        assertEquals("naive", Tokenizer.normalizeWord("naïve"));
        assertEquals("elan", Tokenizer.normalizeWord("Élan"));
        assertEquals("strasse", Tokenizer.normalizeWord("straße"));
        assertEquals("strasse", Tokenizer.normalizeWord("STRAẞE"));   // capital sharp s
        assertEquals("oeuvre", Tokenizer.normalizeWord("œuvre"));
        assertEquals("fine", Tokenizer.normalizeWord("ﬁne"));          // fi ligature
    }

    @Test
    void keepsWhatTheCollationKeepsSeparate() {
        assertEquals("æther", Tokenizer.normalizeWord("Æther"));
        assertEquals("øre", Tokenizer.normalizeWord("Øre"));
        assertEquals("łodz", Tokenizer.normalizeWord("Łodz"));
    }

    @Test
    void lowercasesIWithoutTurkishLocaleRules() {
        assertEquals("i", Tokenizer.normalizeWord("I"));
    }

    @Test
    void normalizeWordRequiresExactlyOneWord() {
        assertEquals("hello", Tokenizer.normalizeWord("Hello,"));
        assertEquals("don't", Tokenizer.normalizeWord("Don’t"));
        assertNull(Tokenizer.normalizeWord("two words"));
        assertNull(Tokenizer.normalizeWord("1891"));
        assertNull(Tokenizer.normalizeWord(""));
        assertNull(Tokenizer.normalizeWord("..."));
    }

    // Sentence boundaries

    @Test
    void splitsOnTerminators() {
        assertEquals(List.of(List.of("one"), List.of("two"), List.of("three")), sentences("One. Two! Three?"));
    }

    @Test
    void splitsBeforeClosingQuotes() {
        assertEquals(List.of(List.of("indeed"), List.of("he", "left")),
                sentences("“Indeed.” He left."));
    }

    @Test
    void doesNotSplitAfterAbbreviations() {
        assertEquals(List.of(List.of("mr", "holmes", "and", "dr", "watson", "met", "st", "simon")),
                sentences("Mr. Holmes and Dr. Watson met St. Simon."));
        assertEquals(List.of(List.of("messrs", "smith", "and", "mrs", "hudson")),
                sentences("Messrs. Smith and Mrs. Hudson"));
    }

    @Test
    void doesNotSplitAfterInitials() {
        assertEquals(List.of(List.of("j", "watson", "arrived")), sentences("J. Watson arrived."));
    }

    @Test
    void splitsAfterThePronounI() {
        assertEquals(List.of(List.of("it", "was", "i"), List.of("then", "he", "left")),
                sentences("It was I. Then he left."));
    }

    @Test
    void doesNotSplitBeforeLowercaseWords() {
        assertEquals(List.of(List.of("oh", "my", "dear", "said", "he")),
                sentences("“Oh! my dear,” said he."));
        assertEquals(List.of(List.of("what", "he", "asked")), sentences("“What?” he asked."));
    }

    @Test
    void treatsEllipsesAsOneTerminator() {
        assertEquals(List.of(List.of("wait", "and", "see")), sentences("Wait... and see."));
        assertEquals(List.of(List.of("wait"), List.of("then", "go")), sentences("Wait... Then go."));
        assertEquals(List.of(List.of("wait"), List.of("then", "go")), sentences("Wait… Then go."));
        assertEquals(List.of(List.of("wait"), List.of("then", "go")), sentences("Wait. . . Then go."));
    }

    @Test
    void treatsRepeatedTerminatorsAsOne() {
        assertEquals(List.of(List.of("really"), List.of("yes")), sentences("Really?! Yes."));
    }

    @Test
    void blankLineEndsSentence() {
        assertEquals(List.of(List.of("a", "scandal", "in", "bohemia"), List.of("to", "sherlock", "holmes")),
                sentences("A SCANDAL IN BOHEMIA\n\nTo Sherlock Holmes"));
    }

    @Test
    void singleNewlineIsASpace() {
        assertEquals(List.of(List.of("it", "was", "late")), sentences("It was\nlate."));
        assertEquals(List.of(List.of("it", "was", "late")), sentences("It was\r\nlate."));
    }

    // Input edge cases

    @Test
    void emptyInputHasNoSentences() {
        assertEquals(List.of(), sentences(""));
        assertEquals(List.of(), sentences("   \n\n \t \r\n"));
        assertEquals(List.of(), sentences("1891 -- ... !!"));
    }

    @Test
    void missingFinalTerminatorStillEndsSentence() {
        assertEquals(List.of(List.of("no", "end", "here")), sentences("no end here"));
    }

    @Test
    void nextSentenceReturnsNullAtEnd() throws IOException {
        Tokenizer tokenizer = new Tokenizer(new StringReader("One. Two."));
        assertEquals(List.of("one"), tokenizer.nextSentence());
        assertEquals(List.of("two"), tokenizer.nextSentence());
        assertNull(tokenizer.nextSentence());
        assertNull(tokenizer.nextSentence());
    }

    // Excerpts from The Adventures of Sherlock Holmes, line wrapping as in the Gutenberg file

    @Test
    void bookOpeningParagraph() {
        String text = "To Sherlock Holmes she is always _the_ woman. I have seldom heard him\r\n"
                + "mention her under any other name. In his eyes she eclipses and\r\n"
                + "predominates the whole of her sex.\r\n";
        assertEquals(List.of(
                List.of("to", "sherlock", "holmes", "she", "is", "always", "the", "woman"),
                List.of("i", "have", "seldom", "heard", "him", "mention", "her", "under", "any", "other", "name"),
                List.of("in", "his", "eyes", "she", "eclipses", "and", "predominates", "the", "whole", "of", "her", "sex")),
                sentences(text));
    }

    @Test
    void bookDialogue() {
        String text = "“Wedlock suits you,” he remarked. “I think, Watson, that you have put\r\n"
                + "on seven and a half pounds since I saw you.”\r\n"
                + "\r\n"
                + "“Seven!” I answered.\r\n";
        assertEquals(List.of(
                List.of("wedlock", "suits", "you", "he", "remarked"),
                List.of("i", "think", "watson", "that", "you", "have", "put", "on", "seven", "and", "a", "half",
                        "pounds", "since", "i", "saw", "you"),
                List.of("seven"),
                List.of("i", "answered")),
                sentences(text));
    }
}
