/*
 * The questions the generators and the UI ask the database. Every word passed
 * in is normalized the same way the importer normalizes it, so "Holmes" and
 * "holmes" give the same answer. Lists are ordered from most to least frequent
 * and are empty, never null, when nothing is found.
 *
 * Generators should depend on this interface, not on JdbcWordRepository, so
 * they can be tested with fake data and no database.
 */

package com.sentencebuilder.repository;

import java.util.List;

public interface WordRepository {

    /**
     * Returns the words that followed the given word in the imported texts, with
     * how often each followed it. Used by weighted random, greedy, and autocomplete.
     */
    List<WordCount> followersOf(String word);

    /**
     * Returns the words that followed the given word pair, with how often each
     * followed it. Used by trigram backoff. Empty means the pair never appeared,
     * so the caller should fall back to followersOf(second).
     */
    List<WordCount> followersOf(String first, String second);

    /**
     * Returns the words that began a sentence, with how often each did. Used to
     * start a sentence when the user didn't type a word.
     */
    List<WordCount> sentenceStarters();

    /**
     * Records that the user picked the next word from the autocomplete
     * suggestions after the given word. Returns false if that pair was never
     * seen in the imported texts, in which case nothing is changed.
     */
    boolean recordSelection(String word, String next);
}