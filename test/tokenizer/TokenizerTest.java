/*
Unit tests for Tokenizer. The prompt used is in the header of tokenizer/Tokenizer.java.

To run from the repo root on JDK 17 with the JUnit console launcher jar
(junit-platform-console-standalone-1.11.4.jar from Maven Central):
  javac -encoding UTF-8 -d out -cp junit-platform-console-standalone-1.11.4.jar tokenizer/Tokenizer.java test/tokenizer/TokenizerTest.java
  java -jar junit-platform-console-standalone-1.11.4.jar execute -cp out --select-package tokenizer
*/

package tokenizer;

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

    // Words

    @Test
    void lowercasesWords() {
        assertEquals(List.of("the", "cat", "sat"), words("The CAT Sat"));
    }

    @Test
    void keepsContractions() {
        assertEquals(List.of("don't", "say", "it's", "holmes's"), words("Don't say it's Holmes's"));
        assertEquals(List.of("don't", "engineer's"), words("Don’t Engineer’s"));
    }

    @Test
    void stripsApostrophesUsedAsQuotes() {
        assertEquals(List.of("tis", "hello", "holmes"), words("'Tis 'hello' Holmes'"));
        assertEquals(List.of("hello", "he", "said"), words("‘Hello,’ he said"));
    }

    @Test
    void keepsHyphenatedWords() {
        assertEquals(List.of("well-known", "mother-in-law"), words("well-known mother-in-law"));
        assertEquals(List.of("red-headed"), words("Red‐Headed"));   // Unicode hyphen
    }

    @Test
    void splitsOnDashes() {
        assertEquals(List.of("word", "word"), words("word--word"));
        assertEquals(List.of("observer", "excellent"), words("observer—excellent"));
        assertEquals(List.of("trailing"), words("trailing-"));
    }

    @Test
    void skipsNonLatinLetters() {
        assertEquals(List.of("da", "net"), words("da да net"));
    }

    @Test
    void skipsWordsLongerThanTheColumn() {
        String hundred = "a".repeat(100);
        assertEquals(List.of(hundred), words(hundred));
        assertEquals(List.of("before", "after"), words("before " + "b".repeat(101) + " after"));
    }

    // Normalization, checked against utf8mb4_unicode_ci in schema.sql

    @Test
    void removesAccents() {
        assertEquals(List.of("cafe", "cafe", "naive", "elan"), words("café café naïve Élan"));   // second is a decomposed accent
    }

    @Test
    void mergesWhatTheCollationMerges() {
        assertEquals(List.of("strasse", "strasse", "oeuvre", "fine"), words("straße STRAẞE œuvre ﬁne"));
    }

    @Test
    void keepsWhatTheCollationKeepsSeparate() {
        assertEquals(List.of("æther", "øre", "łodz"), words("Æther Øre Łodz"));
    }

    // Numbers

    @Test
    void skipsNumbersWithoutEndingTheSentence() {
        assertEquals(List.of(List.of("in", "he", "came")), sentences("In 1891 he came."));
        assertEquals(List.of(List.of("it", "cost", "pounds")), sentences("It cost 3.50 pounds."));
        assertEquals(List.of(List.of("lived", "at", "baker", "street")), sentences("Lived at 221B Baker Street."));
        assertEquals(List.of(List.of("the", "of", "may")), sentences("The 1st of May."));
        assertEquals(List.of(List.of("in", "the", "early")), sentences("In the early ’60’s."));
    }

    // Sentence endings

    @Test
    void splitsOnTerminators() {
        assertEquals(List.of(List.of("one"), List.of("two"), List.of("three"), List.of("four")),
                sentences("One. Two! Three? Four…"));
    }

    @Test
    void doesNotSplitAfterAbbreviations() {
        assertEquals(List.of(List.of("mr", "holmes", "and", "dr", "watson", "met", "st", "simon")),
                sentences("Mr. Holmes and Dr. Watson met St. Simon."));
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
    void repeatedTerminatorsDoNotCreateEmptySentences() {
        assertEquals(List.of(List.of("really"), List.of("yes")), sentences("Really?! Yes..."));
    }

    @Test
    void splitsBeforeClosingQuotes() {
        assertEquals(List.of(List.of("indeed"), List.of("he", "left")), sentences("“Indeed.” He left."));
    }

    // Documented limitation: a terminator inside a quote ends the sentence
    @Test
    void splitsAfterQuotedQuestion() {
        assertEquals(List.of(List.of("what"), List.of("he", "asked")), sentences("“What?” he asked."));
    }

    @Test
    void textWithoutFinalPunctuationIsStillASentence() {
        assertEquals(List.of(List.of("no", "end", "here")), sentences("no end here"));
        assertEquals(List.of(List.of("one"), List.of("two")), sentences("One. Two"));
    }

    // Lines

    @Test
    void singleNewlineIsASpace() {
        assertEquals(List.of(List.of("it", "was", "late")), sentences("It was\nlate."));
        assertEquals(List.of(List.of("it", "was", "late")), sentences("It was\r\nlate."));
    }

    @Test
    void blankLineEndsSentence() {
        assertEquals(List.of(List.of("a", "scandal", "in", "bohemia"), List.of("to", "sherlock", "holmes")),
                sentences("A SCANDAL IN BOHEMIA\n\nTo Sherlock Holmes"));
    }

    // Empty input

    @Test
    void emptyInputHasNoSentences() {
        assertEquals(List.of(), sentences(""));
        assertEquals(List.of(), sentences("   \n\n \t \r\n"));
        assertEquals(List.of(), sentences("1891 -- ... !!"));
    }

    // Reading from a Reader

    @Test
    void nextSentenceReturnsOneSentenceAtATime() throws IOException {
        Tokenizer tokenizer = new Tokenizer(new StringReader("One. Two."));
        assertEquals(List.of("one"), tokenizer.nextSentence());
        assertEquals(List.of("two"), tokenizer.nextSentence());
        assertNull(tokenizer.nextSentence());
        assertNull(tokenizer.nextSentence());
    }

    // Excerpt from The Adventures of Sherlock Holmes, line wrapping as in the Gutenberg file

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
}
