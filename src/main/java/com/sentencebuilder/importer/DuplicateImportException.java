/*
 * Thrown by Importer when the text being imported was already imported, even
 * if it was under another file name. The message names the earlier file and
 * when it was imported, so the UI can show it as is.
 */

package com.sentencebuilder.importer;

public class DuplicateImportException extends Exception {

    public DuplicateImportException(String message) {
        super(message);
    }
}
