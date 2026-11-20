# Batch Tab Manual Checks

These steps check by hand what `BatchTabTest` checks automatically: Add to Corpus can't save a batch
twice, a failed Add to Corpus can be retried, and added batches show up in the Import report.

## What the automated tests cover

`src/test/java/com/sentencebuilder/ui/BatchTabTest.java` loads the real `main-view.fxml`, presses the
buttons with `fire()`, and runs against temporary tables, so it never touches shared data. It checks:

- Two presses of Add to Corpus before the first finishes save one batch, and the button stays disabled
  after it's added.
- When the import fails partway (after the batch row, words, and pairs are written), nothing is left
  behind, the sentences stay on screen, Add to Corpus is enabled again, and the retry counts the batch
  once.
- After a batch is added, going back to the Import tab lists it as Generated with its word count, and
  the total underneath counts it as a generated batch.

It runs only where JavaFX can open a screen. On the GitHub Actions runner it is skipped, so run
`./gradlew test` locally to see it pass.

If a test fails or times out while one of the app's background tasks is still running, that task's
later connection requests are refused, so it can't fall back to the shared database.

What it can't show:

- Real commits. Every connection in the test is a stand-in for one real connection, and an import's
  commit only releases a savepoint inside a transaction that is rolled back at the end. The report
  reads on that same connection. So the test doesn't show that a committed batch persists, or that
  the Import tab's own separate connection sees it. Step 3 below checks that.
- Real mouse clicks. It calls `fire()`, which ignores a disabled button the same way a click does.
- A real network failure, and how the window looks.

The steps below cover these.

## Before you start

**These steps write to the shared database.** Every batch you add stays in the corpus and in the
Import report, and there is no undo in the app. To keep shared data clean, point `.env` at a separate
test database built from `database/schema.sql` with a little text imported. If you use the shared
database, add small batches (2 or 3 sentences) and tell the team.

Run the app with `./gradlew run`. At least one text file must already be imported.

## 1. Double click on Add to Corpus

1. Open the **Batch** tab. Set Sentences to `3`, keep Max words at `20`, and press **Generate**.
2. When the sentences appear, double-click **Add to Corpus** as fast as you can.
3. Expected: the status line says "Adding 3 sentences to the corpus…", then "Added 3 sentences to the
   corpus (N words)". Add to Corpus stays greyed out.
4. Open the **Import** tab. Expected: exactly one new "Generated batch of 3 sentences" row at the top.
5. Back on **Batch**, press **Generate** again. Add to Corpus is enabled for the new batch.

## 2. Retry after a failed import

1. On the **Batch** tab, generate 2 sentences.
2. Turn off Wi-Fi (or unplug the network).
3. Press **Add to Corpus**. Expected: after a few seconds the status says "Couldn't add the batch: …",
   the sentences are still listed, and Add to Corpus is enabled again.
4. Turn the network back on and press **Add to Corpus** again. Expected: "Added 2 sentences…".
5. Open the **Import** tab. Expected: one "Generated batch of 2 sentences" row, not two.

Going offline makes the connection fail before anything is written. The automated test also covers a
failure partway through the save, which can't easily be caused by hand.

## 3. Batch in the Import report

1. Note the total under the **Import** tab's table, for example "2 files and 1 generated batch,
   12,345 words in all".
2. On the **Batch** tab, generate and add 2 sentences.
3. Go back to the **Import** tab. Expected: the newest row is "Generated batch of 2 sentences", Type
   "Generated", with the word count from the Batch status line. The total shows one more generated
   batch and that many more words.
4. Close the app and run it again. Expected: the batch is still listed. This is the part the
   automated tests can't check, since they never really commit.
