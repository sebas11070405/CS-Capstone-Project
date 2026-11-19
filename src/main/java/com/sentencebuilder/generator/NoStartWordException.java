/*
 * Thrown by SentenceBatchService when no word has ever started a sentence
 * (no row in words has start_count > 0), so there is nothing to start a
 * generated sentence with. Usually it means no text has been imported yet.
 * The message says so in words the UI can show as is.
 *
 * AI-assisted: Claude Code
 *
 * Prompt: see src/main/java/com/sentencebuilder/generator/SentenceBatchService.java
 */

package com.sentencebuilder.generator;

public class NoStartWordException extends Exception {

    public NoStartWordException(String message) {
        super(message);
    }
}
