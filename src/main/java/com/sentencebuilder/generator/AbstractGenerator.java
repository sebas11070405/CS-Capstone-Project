/*
 * Holds what every generator shares: the maximum sentence length and helpers
 * for cleaning and checking a generated sentence. Each generation algorithm
 * extends this class and implements generateSentence.
 */

package com.sentencebuilder.generator;

public abstract class AbstractGenerator implements Generator{

    protected int maxWords;   // Longest sentence a generator may build, in words

    /** Creates a generator that builds sentences of at most maxWords words. */
    public AbstractGenerator(int maxWords){

        this.maxWords = maxWords;
    }

    /**
     * Cleans up a generated sentence before it is used. For now it only trims
     * leading and trailing whitespace.
     */
    protected String cleanSentence(String sentence){

        return sentence.trim();
    }

    /** Returns true if the sentence has text: not null, empty, or only whitespace. */
    protected boolean isValidSentence(String sentence){

        return sentence != null && !sentence.isBlank();
    }
}
