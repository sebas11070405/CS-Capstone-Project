/*
 * Base class for every sentence generation algorithm, so the UI can run any
 * of them the same way. Holds the maximum sentence length; each algorithm
 * extends this class and implements generateSentence.
 */

package com.sentencebuilder.generator;

public abstract class AbstractGenerator {

    protected int maxWords;   // Longest sentence a generator may build, in words

    /** Creates a generator that builds sentences of at most maxWords words. */
    public AbstractGenerator(int maxWords){

        this.maxWords = maxWords;
    }

    /** Generates one sentence of at most maxWords words and returns its text. */
    public abstract String generateSentence();
}
