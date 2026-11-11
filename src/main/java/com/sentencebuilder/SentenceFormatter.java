/*
 * Turns the raw text a generator returns into a sentence that reads normally. Generators build
 * sentences from lowercase words with no punctuation, so this capitalizes the first word, writes
 * the pronoun "I" (and I'm, I'll, I've, I'd) with a capital, tidies the spacing, and ends the
 * sentence with a period if it has no ending punctuation yet.
 *
 * Call SentenceFormatter.format(text) on a generator's output, or call generateFormattedSentence()
 * on any Generator. It only changes how a sentence looks, never which words it contains, and it
 * can't know which words are names, so those stay lowercase.
 */

package com.sentencebuilder.generator;

public final class SentenceFormatter
{

    private static final String SENTENCE_ENDINGS = ".!?";   // Characters that already end a sentence

    private SentenceFormatter() {
        // Only static methods, so there is nothing to create
    }

    /**
     * Formats one generated sentence for display. Takes the raw sentence, such as "i think the dog
     * ran", and returns it as "I think the dog ran." Returns an empty string for null or blank text.
     */
    public static String format(String rawSentence) {
        if (rawSentence == null || rawSentence.isBlank()) {
            return "";
        }

        // Split on any run of whitespace so extra spaces and line breaks collapse to single spaces
        String[] words = rawSentence.trim().split("\\s+");
        for (int ix = 0; ix < words.length; ix++) {
            if (isPronounI(words[ix])) {
                words[ix] = "I" + words[ix].substring(1);
            }
        }
        String sentence = String.join(" ", words);

        // The first word starts the sentence, so it gets a capital letter
        sentence = Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);

        // Leave a period, question mark, or exclamation point alone, and add a period otherwise
        char lastChar = sentence.charAt(sentence.length() - 1);
        if (SENTENCE_ENDINGS.indexOf(lastChar) < 0) {
            sentence = sentence + ".";
        }
        return sentence;
    }

    /**
     * Says whether a word is the pronoun "i" or one of its contractions (i'm, i'll, i've, i'd).
     * Words that only begin with the letter i, such as "it" or "island", are not.
     */
    private static boolean isPronounI(String word) {
        return word.equals("i") || word.startsWith("i'") || word.startsWith("i\u2019");
    }
}