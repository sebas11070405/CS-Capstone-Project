/*
 * Every sentence generation algorithm implements this, so the UI can run any
 * of them the same way. Most implementations should extend AbstractGenerator
 * for its shared helpers.
 */

package com.sentencebuilder.generator;

public interface Generator{
    /** Generates one sentence and returns its text. */
    String generateSentence();

    /**
     * Generates one sentence and returns it formatted for display, with a capital first letter,
     * a capital "I", and ending punctuation. The UI shows and saves this version, while
     * generateSentence stays the raw words so the generators can be tested directly.
     */
    default String generateFormattedSentence() {
        return SentenceFormatter.format(generateSentence());
    }
}