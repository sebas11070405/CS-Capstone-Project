/*
 * Every sentence generation algorithm implements this, so the UI can run any
 * of them the same way. Most implementations should extend AbstractGenerator
 * for its shared helpers.
 */

package com.sentencebuilder.generator;

public interface Generator{
    /** Generates one sentence and returns its text. */
    String generateSentence();
}
