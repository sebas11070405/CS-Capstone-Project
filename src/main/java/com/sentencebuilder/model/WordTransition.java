/*
 * One word that can follow another word, used by the sentence generators and
 * autocomplete. WordRepository creates these from the bigrams table.
 */

package com.sentencebuilder.model;

/** A word that can follow the current word, and how many times it did in the imported text. */
public record WordTransition(String nextWord, int frequency) {}
