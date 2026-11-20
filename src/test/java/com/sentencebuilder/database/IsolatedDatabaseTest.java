/*
 * Regression tests for IsolatedDatabase: background work that outlives a failed test must never
 * get a connection, because the next connection after a test would otherwise go to the shared
 * database. Each test fails on purpose inside IsolatedDatabase.run while a background thread is
 * waiting, and only lets that thread ask for a connection after run has returned.
 *
 * The first test covers a thread the test started itself. The second covers a long-lived thread
 * that existed before the test and starts new work after it, the way the JavaFX thread starts a
 * Task from a success or failure handler.
 *
 * The late requests are refused before any connection is opened, so nothing reaches shared data.
 * Needs a valid .env and network access for the isolated session itself. Run with ./gradlew test.
 *
 * AI-assisted: Claude Code
 *
 * Prompt: see src/test/java/com/sentencebuilder/integration/BatchImportIntegrationTest.java
 */

package com.sentencebuilder.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class IsolatedDatabaseTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Test
    void delayedRequestFromAFailedTestIsRefused() throws Exception {
        CountDownLatch testOver = new CountDownLatch(1);   // Holds the request until run has returned
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();

        AssertionError failure = assertThrows(AssertionError.class, () -> IsolatedDatabase.run(session -> {
            // Started during the test like a JavaFX Task, but slow enough to ask after the test failed
            Thread lateTask = new Thread(() -> outcome.complete(connectAfter(testOver)));
            lateTask.setDaemon(true);
            lateTask.start();
            fail("simulated test failure");
        }));
        assertEquals("simulated test failure", failure.getMessage());

        testOver.countDown();
        assertRefused(outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void workStartedLaterByAClaimedThreadIsRefused() throws Exception {
        // Stands in for the JavaFX thread: it exists before the test and outlives it
        ExecutorService eventThread = Executors.newSingleThreadExecutor();
        try {
            // Create its thread now, so it doesn't inherit the session just by being started in it
            eventThread.submit(() -> {}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThrows(AssertionError.class, () -> IsolatedDatabase.run(session -> {
                eventThread.submit(session::claimCurrentThread).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                fail("simulated timeout while a task was running");
            }));

            // After the test, the event thread starts a new task, as a late onSucceeded handler would
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            eventThread.submit(() -> {
                Thread followUp = new Thread(() -> outcome.complete(connectAfter(new CountDownLatch(0))));
                followUp.setDaemon(true);
                followUp.start();
            });
            assertRefused(outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            eventThread.shutdownNow();
        }
    }

    /**
     * Waits for the latch, then asks Database for a connection the way the app's tasks do.
     * Returns what went wrong, or null if it got a connection, which would mean it reached a
     * database it shouldn't have.
     */
    private static Throwable connectAfter(CountDownLatch go) {
        try {
            go.await();
            try (Connection conn = Database.getConnection()) {
                return null;
            }
        } catch (Throwable e) {
            return e;
        }
    }

    /** Asserts the late request got IsolatedDatabase's refusal, not a connection or some other error. */
    private static void assertRefused(Throwable outcome) {
        assertInstanceOf(SQLException.class, outcome, "the late request got a connection or failed oddly");
        assertTrue(outcome.getMessage().startsWith("Refused a database connection"), outcome.getMessage());
    }
}
