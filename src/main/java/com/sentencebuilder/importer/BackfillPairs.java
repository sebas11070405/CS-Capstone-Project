/*
 * One-time command that adds the bigrams and trigrams of files imported before
 * pairs and triples were counted, without counting their words again. Pass
 * every such file, all in one run:
 *
 *   ./gradlew backfillPairs --args="texts/adventures-of-sherlock-holmes.txt other.txt"
 *
 * Run it before anyone imports a file with the new Importer. It refuses to
 * run, and changes nothing, if bigrams or trigrams already has rows, if a
 * file doesn't match an earlier import, or if any imported file is left out
 * (it lists those). See Importer.backfillPairs.
 *
 * Needs DB_URL, DB_USER, and DB_PASSWORD in .env (see Database).
 *
 * Prompt: see src/main/java/com/sentencebuilder/importer/Importer.java
 */

package com.sentencebuilder.importer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BackfillPairs {

    /** Backfills the files named in args and prints progress to the console. */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: ./gradlew backfillPairs --args=\"file1.txt file2.txt ...\"");
            System.exit(1);
        }
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            files.add(Path.of(arg));
        }

        // Print only when the message changes, so the console isn't flooded with repeats
        String[] lastMessage = {""};
        new Importer().backfillPairs(files, (fraction, message) -> {
            if (!message.equals(lastMessage[0])) {
                System.out.println(message);
                lastMessage[0] = message;
            }
        });
        System.out.println("Done. Added word pairs and triples for " + files.size() + " file(s).");
    }
}
