/*
 * Contains functionality shared by generators, such as cleaning and validating sentences.
 */

package com.sentencebuilder.generator;

public abstract class AbstractGenerator implements Generator{

    protected int maxWords;

    // Constructor for AbstractGenerator, takes in the maximum number of words to generate
    public AbstractGenerator(int maxWords){

        this.maxWords = maxWords;
    }

}