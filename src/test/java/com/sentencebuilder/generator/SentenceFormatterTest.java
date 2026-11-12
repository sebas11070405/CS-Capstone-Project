/*
 * Tests SentenceFormatter and Generator.generateFormattedSentence. Needs no database or .env.
 */

package com.sentencebuilder.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SentenceFormatterTest {

    @Test
    void capitalizesTheFirstWordAndEndsWithAPeriod() {
        assertEquals("The dog ran home.", SentenceFormatter.format("the dog ran home"));
    }

    @Test
    void keepsExistingEndingPunctuation() {
        assertEquals("Is the dog home?", SentenceFormatter.format("is the dog home?"));
        assertEquals("Run!", SentenceFormatter.format("run!"));
        assertEquals("Done.", SentenceFormatter.format("done."));
    }

    @Test
    void writesThePronounIWithACapital() {
        assertEquals("I think I am wrong.", SentenceFormatter.format("i think i am wrong"));
        assertEquals("I'm sure I'll go and I've seen it.",
                SentenceFormatter.format("i'm sure i'll go and i've seen it"));
    }

    @Test
    void leavesOtherWordsThatStartWithIAlone() {
        assertEquals("It is an island in the ink.", SentenceFormatter.format("it is an island in the ink"));
    }

    @Test
    void tidiesSpacing() {
        assertEquals("The dog ran.", SentenceFormatter.format("  the   dog\tran \n"));
    }

    @Test
    void oneWordWorks() {
        assertEquals("Go.", SentenceFormatter.format("go"));
    }

    @Test
    void blankOrNullGivesAnEmptyString() {
        assertEquals("", SentenceFormatter.format(""));
        assertEquals("", SentenceFormatter.format("   "));
        assertEquals("", SentenceFormatter.format(null));
    }

    @Test
    void generatorsCanReturnTheFormattedSentence() {
        Generator fixed = () -> "i saw the dog";
        assertEquals("i saw the dog", fixed.generateSentence());
        assertEquals("I saw the dog.", fixed.generateFormattedSentence());
    }
}