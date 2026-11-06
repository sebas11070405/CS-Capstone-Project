/*
 * One word and a count, such as a follower of another word and how often it
 * followed it, or a sentence starter and how often it began a sentence.
 */

package com.sentencebuilder.repository;

/** A word paired with how many times it was counted. */
public record WordCount(String word, int count) {}