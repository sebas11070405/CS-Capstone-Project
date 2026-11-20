/*
 * Drives the real main window (main-view.fxml with its Batch tab) the way a user would, to check
 * three things about task 4.5: a double click on Add to Corpus saves the batch only once, a failed
 * Add to Corpus can be retried without counting the batch twice, and an added batch shows up in
 * the Import tab's report when the user goes back to it.
 *
 * The buttons are pressed with fire(), which does nothing on a disabled button, just like a real
 * click. The controllers run their database work on background threads as usual, and every
 * connection they open goes to temporary tables (see IsolatedDatabase), so shared data is never
 * read or changed. That holds even when a test fails or times out with a task still running: a
 * late connection request from that task is refused. The corpus is the one-sentence seed from
 * BatchImportIntegrationTest.
 *
 * Every connection here is a stand-in for one real connection, and imports only release a
 * savepoint instead of committing. So the report test shows the Import tab reloads and lists the
 * batch, but not that a real commit makes it visible to the report's own, separate connection.
 * docs/batch-tab-manual-tests.md covers that by hand.
 *
 * Needs a valid .env, network access, and a screen for JavaFX. Without a screen (as on the GitHub
 * Actions runner) the tests are skipped, not failed. See docs/batch-tab-manual-tests.md for
 * checking the same things by hand. Run with ./gradlew test.
 *
 * AI-assisted: Claude Code and Codex
 *
 * Prompt: see src/test/java/com/sentencebuilder/integration/BatchImportIntegrationTest.java
 */

package com.sentencebuilder.ui;

import com.sentencebuilder.database.IsolatedDatabase;
import com.sentencebuilder.database.SourceFileRepository.SourceFile;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.sentencebuilder.integration.BatchImportIntegrationTest.START;
import static com.sentencebuilder.integration.BatchImportIntegrationTest.assertWord;
import static com.sentencebuilder.integration.BatchImportIntegrationTest.countRows;
import static com.sentencebuilder.integration.BatchImportIntegrationTest.seed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BatchTabTest {

    private static final long TIMEOUT_MS = 60_000;   // Each step talks to Supabase, which can be slow
    private static final String GENERATED_ROWS =
            "SELECT count(*) FROM source_files WHERE source_type = 'generated'";

    private static boolean toolkitStarted;   // False when there is no screen to start JavaFX on

    /** Starts JavaFX once for all the tests, or notes that it can't start here. */
    @BeforeAll
    static void startJavaFx() {
        try {
            Platform.startup(() -> {});
            toolkitStarted = true;
        } catch (IllegalStateException e) {
            toolkitStarted = true;   // Already started by an earlier test class
        } catch (RuntimeException e) {
            toolkitStarted = false;   // No display, for example on a headless CI runner
        }
        if (toolkitStarted) {
            Platform.setImplicitExit(false);   // Keep JavaFX running between tests
        }
    }

    @Test
    void doubleClickOnAddToCorpusSavesTheBatchOnce(@TempDir Path dir) throws Exception {
        assumeTrue(toolkitStarted, "JavaFX can't start without a screen");
        IsolatedDatabase.run(session -> {
            seed(dir);
            Window window = Window.open(session);
            window.generate("2");

            // Both presses land before the import finishes, the same as a quick double click
            onFx(() -> {
                window.addButton.fire();
                window.addButton.fire();
                return null;
            });
            window.waitForStatus("Added");

            Connection conn = session.connection();
            assertEquals(1, countRows(conn, GENERATED_ROWS));
            assertWord(conn, START, 3, 3, 0);
            assertTrue(onFx(window.addButton::isDisabled), "the same batch can be added again");

            // Pressing again after it's added does nothing either
            onFx(() -> {
                window.addButton.fire();
                return null;
            });
            assertEquals(1, countRows(conn, GENERATED_ROWS));
        });
    }

    @Test
    void failedAddToCorpusCanBeRetried(@TempDir Path dir) throws Exception {
        assumeTrue(toolkitStarted, "JavaFX can't start without a screen");
        IsolatedDatabase.run(session -> {
            seed(dir);
            Window window = Window.open(session);
            window.generate("2");

            // Fail on the last table saved, after the batch row, words, and pairs have been written
            session.failNextStatementContaining("INSERT INTO trigrams");
            window.press(window.addButton);
            window.waitForStatus("Couldn't add the batch");

            Connection conn = session.connection();
            assertEquals(0, countRows(conn, GENERATED_ROWS), "the failed import left its batch row");
            assertWord(conn, START, 1, 1, 0);
            assertFalse(onFx(window.addButton::isDisabled), "Add to Corpus can't be retried after a failure");
            assertEquals(2, (int) onFx(() -> window.resultsList.getItems().size()), "the batch left the screen");

            // The retry saves the same batch, and it is counted once
            window.press(window.addButton);
            window.waitForStatus("Added");
            assertEquals(1, countRows(conn, GENERATED_ROWS));
            assertWord(conn, START, 3, 3, 0);
        });
    }

    @Test
    void addedBatchAppearsInTheImportReport(@TempDir Path dir) throws Exception {
        assumeTrue(toolkitStarted, "JavaFX can't start without a screen");
        IsolatedDatabase.run(session -> {
            seed(dir);
            Window window = Window.open(session);
            window.waitForSummary("1 file and 0 generated batches, 3 words in all");

            // The report refreshes when the user comes back to the Import tab. Its read runs on the
            // same isolated connection as the import, so this doesn't test commit visibility.
            onFx(() -> {
                window.tabs.getSelectionModel().select(window.findTab("Batch"));
                return null;
            });
            window.generate("2");
            window.press(window.addButton);
            window.waitForStatus("Added");
            onFx(() -> {
                window.tabs.getSelectionModel().select(window.findTab("Import"));
                return null;
            });

            window.waitForSummary("1 file and 1 generated batch, 9 words in all");
            SourceFile newest = onFx(() -> window.sourcesTable.getItems().getFirst());
            assertEquals("Generated batch of 2 sentences", newest.fileName());
            assertTrue(newest.generated(), "the batch is listed as a file");
            assertEquals(6, newest.wordCount());
        });
    }

    /** The main window and the controls the tests use, found by their fx:id. */
    private static final class Window {
        private final TabPane tabs;
        private final TextField countField;
        private final TextField maxWordsField;
        private final Button generateButton;
        private final Button addButton;
        private final ListView<?> resultsList;
        private final Label statusLabel;                  // The Batch tab's status line
        private final TableView<SourceFile> sourcesTable;
        private final Label sourcesSummary;               // The total under the Import report

        /** Collects the controls from the loaded main window. */
        @SuppressWarnings("unchecked")
        private Window(TabPane tabs) {
            this.tabs = tabs;
            // The Batch tab comes from its own FXML file, so its controls are found under its tab
            Node batch = findTab("Batch").getContent();
            Node importTab = findTab("Import").getContent();
            countField = (TextField) batch.lookup("#countField");
            maxWordsField = (TextField) batch.lookup("#maxWordsField");
            generateButton = (Button) batch.lookup("#generateButton");
            addButton = (Button) batch.lookup("#addButton");
            resultsList = (ListView<?>) batch.lookup("#resultsList");
            statusLabel = (Label) batch.lookup("#statusLabel");
            sourcesTable = (TableView<SourceFile>) importTab.lookup("#sourcesTable");
            sourcesSummary = (Label) importTab.lookup("#sourcesSummary");
        }

        /** Finds a named tab without depending on its position among the app's other tabs. */
        private Tab findTab(String name) {
            return tabs.getTabs().stream().filter(tab -> name.equals(tab.getText())).findFirst()
                    .orElseThrow(() -> new AssertionError("Missing tab: " + name));
        }

        /**
         * Loads main-view.fxml as the app does and waits for the Import report's first load to
         * finish, so no background read is still running when the test changes anything. Claims
         * the JavaFX thread for the session first, so every task the controllers start from it,
         * even after the test ends, is tied to this session and can't reach the shared database.
         */
        static Window open(IsolatedDatabase.Session session) throws Exception {
            Window window = onFx(() -> {
                session.claimCurrentThread();
                TabPane root = new FXMLLoader(MainController.class.getResource("main-view.fxml")).load();
                // Like the app's window. Applying CSS gives the tab pane its skin, which is what tells
                // each tab it was selected, so the Import tab's reload-on-return can run.
                new Scene(root, 800, 600);
                root.applyCss();
                return new Window(root);
            });
            window.waitFor(() -> !window.sourcesSummary.getText().isEmpty(), "the first report load");
            return window;
        }

        /** Asks for the given number of sentences of at most 10 words and waits for them. */
        void generate(String count) throws Exception {
            onFx(() -> {
                countField.setText(count);
                maxWordsField.setText("10");
                return null;
            });
            press(generateButton);
            waitForStatus("Generated");
        }

        /** Presses the button once on the JavaFX thread. */
        void press(Button button) throws Exception {
            onFx(() -> {
                button.fire();
                return null;
            });
        }

        /** Waits until the Batch tab's status line starts with the given text. */
        void waitForStatus(String prefix) throws Exception {
            waitFor(() -> statusLabel.getText().startsWith(prefix), "status starting \"" + prefix + "\"");
        }

        /** Waits until the total under the Import report reads exactly the given text. */
        void waitForSummary(String summary) throws Exception {
            waitFor(() -> summary.equals(sourcesSummary.getText()), "report summary \"" + summary + "\"");
        }

        /**
         * Checks the condition on the JavaFX thread until it holds, and fails with what was on screen
         * if it doesn't within TIMEOUT_MS.
         */
        void waitFor(Callable<Boolean> condition, String what) throws Exception {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            // Background tasks report back on the JavaFX thread, so poll there rather than block it
            while (System.currentTimeMillis() < deadline) {
                if (onFx(condition)) {
                    return;
                }
                Thread.sleep(50);
            }
            fail("Timed out waiting for " + what + ". Status: \"" + onFx(statusLabel::getText)
                    + "\", report: \"" + onFx(sourcesSummary::getText) + "\"");
        }
    }

    /** Runs the work on the JavaFX thread, waits for it, and returns its result. */
    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
