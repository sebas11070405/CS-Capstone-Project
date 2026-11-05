/*
 * Offers the user choices for the next word and records which word they use.
 * The UI should call getSuggestions once a word is complete, after a space,
 * period, or comma, and call recordChoice with the word the user picked or typed.
 * Words are normalized the same way imported text is, so "The" and "the," both
 * mean "the".
 *
 * Needs a WordRepository on an open connection. To save all of a choice or none
 * of it, call recordChoice inside Database.inTransaction with a repository on
 * that transaction's connection.
 */

package com.sentencebuilder.autocomplete;

import com.sentencebuilder.database.WordRepository;
import com.sentencebuilder.model.WordTransition;
import com.sentencebuilder.tokenizer.Tokenizer;

import java.util.List;

public class Autocomplete {

    private final WordRepository repo;   // Finds next words and saves the user's choices

    /** Creates an autocomplete that reads from and writes to repo. */
    public Autocomplete(WordRepository repo) {
        this.repo = repo;
    }

    /**
     * Returns up to limit words that can follow previousWord, most frequent first.
     * Returns an empty list if limit isn't positive, if previousWord isn't one
     * valid word, or if nothing has ever followed it.
     */
    public List<WordTransition> getSuggestions(String previousWord, int limit) {
        String previous = normalize(previousWord);
        if (previous == null || limit <= 0) {
            return List.of();
        }

        // The repository returns the most frequent words first, so these are the top suggestions
        return repo.getTransitions(previous).stream().limit(limit).toList();
    }

    /**
     * Records that the user chose chosenWord after previousWord. The spec says to
     * add a word that isn't stored yet, so either word or the pair may be new.
     * Throws IllegalArgumentException unless both are exactly one valid word.
     */
    public void recordChoice(String previousWord, String chosenWord) {
        String previous = normalize(previousWord);
        String chosen = normalize(chosenWord);
        if (previous == null || chosen == null) {
            throw new IllegalArgumentException("Not valid words: " + previousWord + ", " + chosenWord);
        }

        repo.addWord(previous);
        repo.addWord(chosen);
        repo.recordSelection(previous, chosen);
    }

    /** Returns the word as it is stored in the database, or null if the text isn't one valid word. */
    private static String normalize(String text) {
        return text == null ? null : Tokenizer.normalizeWord(text);
    }
}
