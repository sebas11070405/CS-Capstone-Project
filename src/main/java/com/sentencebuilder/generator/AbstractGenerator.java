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

}
