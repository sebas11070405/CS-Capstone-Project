/*
 * Runs test code against an empty, private copy of the corpus tables, so code that opens its own
 * connections (the generators, Importer, the UI controllers) can be tested end to end without
 * reading or changing shared data.
 *
 * run opens one real connection, creates temporary source_files, words, bigrams, and trigrams
 * tables on it (they hide the shared tables of the same name on that connection only), and checks
 * that every one of those names really resolves to the temporary copy. While the work runs,
 * Database.getConnection hands out that connection. At the end it always rolls back, so the
 * temporary tables and everything written to them disappear.
 *
 * Work a test starts can outlive it: a JavaFX Task may still be running when an assertion fails
 * or a wait times out. So every thread that belongs to a session is marked: the test's own thread,
 * any thread a test claims (like the JavaFX thread, see claimCurrentThread), and every thread those
 * start, at any time. Once the session ends, a connection request from a marked thread throws
 * SQLException. It is never sent to the shared database. Before rolling back, run waits a while for
 * connections still in use to close, so work in flight finishes on the isolated tables. Requests
 * from unmarked threads go to the shared database between sessions, as before, so other tests see
 * no difference.
 *
 * Each connection the code under test opens is a stand-in for the one real connection. Its
 * transactions become savepoints: commit releases the savepoint and rollback returns to it.
 * Closing it does nothing. Because everything runs on one connection and in one real transaction
 * that is rolled back, nothing is ever really committed. Later steps of a test see earlier work
 * because they share the connection, not because it was committed. These tests don't show that
 * committed data persists or is visible to other, independent connections. A test can also make
 * the next statement containing some SQL fail, to check rollbacks.
 *
 * Needs a valid .env and network access, like the other database tests. Run with ./gradlew test.
 *
 * AI-assisted: Claude Code
 *
 * Prompt: see src/test/java/com/sentencebuilder/integration/BatchImportIntegrationTest.java
 */

package com.sentencebuilder.database;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class IsolatedDatabase {

    // The tables the corpus code reads and writes. Each must resolve to a temporary copy.
    private static final List<String> TABLES = List.of("source_files", "words", "bigrams", "trigrams");

    // Same columns, checks, and indexes as database/schema.sql, written out so the shared tables
    // aren't touched at all. The partial unique index is what makes file imports detect duplicates.
    private static final List<String> CREATE_TABLES = List.of("""
            CREATE TEMP TABLE source_files
            (
                file_id      INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                file_name    VARCHAR(255) NOT NULL,
                source_type  VARCHAR(20)  NOT NULL DEFAULT 'file'
                                          CHECK (source_type IN ('file', 'generated')),
                word_count   INT          NOT NULL DEFAULT 0 CHECK (word_count >= 0),
                imported_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                content_hash CHAR(64),
                CHECK (source_type <> 'file' OR content_hash IS NOT NULL)
            ) ON COMMIT DROP
            """, """
            CREATE UNIQUE INDEX ON pg_temp.source_files (content_hash) WHERE source_type = 'file'
            """, """
            CREATE TEMP TABLE words
            (
                word_id      INT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                word         VARCHAR(100) NOT NULL UNIQUE CHECK (word = lower(word)),
                total_count  INT          NOT NULL DEFAULT 0 CHECK (total_count >= 0),
                start_count  INT          NOT NULL DEFAULT 0 CHECK (start_count >= 0),
                end_count    INT          NOT NULL DEFAULT 0 CHECK (end_count >= 0),
                CHECK (start_count <= total_count AND end_count <= total_count)
            ) ON COMMIT DROP
            """, """
            CREATE TEMP TABLE bigrams
            (
                word_id        INT NOT NULL REFERENCES pg_temp.words (word_id),
                next_word_id   INT NOT NULL REFERENCES pg_temp.words (word_id),
                occurrences    INT NOT NULL DEFAULT 0 CHECK (occurrences >= 0),
                user_selected  INT NOT NULL DEFAULT 0 CHECK (user_selected >= 0),
                PRIMARY KEY (word_id, next_word_id)
            ) ON COMMIT DROP
            """, """
            CREATE TEMP TABLE trigrams
            (
                word1_id      INT NOT NULL REFERENCES pg_temp.words (word_id),
                word2_id      INT NOT NULL REFERENCES pg_temp.words (word_id),
                next_word_id  INT NOT NULL REFERENCES pg_temp.words (word_id),
                occurrences   INT NOT NULL DEFAULT 0 CHECK (occurrences >= 0),
                PRIMARY KEY (word1_id, word2_id, next_word_id)
            ) ON COMMIT DROP
            """);

    // How long run waits, after the work ends, for connections still in use to be closed
    private static final long DRAIN_MS = 10_000;

    // The session a thread belongs to. Threads inherit it from the thread that starts them, so
    // background work started during a test stays tied to that test, however late it runs.
    private static final InheritableThreadLocal<Session> OWNER = new InheritableThreadLocal<>();

    private static volatile Session active;           // The session running now, or null between tests
    private static Database.ConnectionSource shared;  // The real source, set once the router is installed

    /** Test code passed to run as a lambda. Unlike Runnable, it may throw. */
    @FunctionalInterface
    public interface Work {
        void run(Session session) throws Exception;
    }

    /**
     * The isolated setup a test is running in. connection() is the real connection, for checking
     * results directly. connectionsOpened() counts how many connections the code under test asked
     * Database for, so a test can show they all came from here.
     */
    public static final class Session {
        private final Connection conn;                              // The one real connection
        private final AtomicInteger opened = new AtomicInteger();   // Background threads open them too
        private final AtomicInteger inUse = new AtomicInteger();    // Opened and not yet closed
        private volatile String failOn;   // SQL text that makes the next matching statement fail
        private volatile boolean ended;   // Once true, every request from this session's threads fails

        private Session(Connection conn) {
            this.conn = conn;
        }

        public Connection connection() {
            return conn;
        }

        public int connectionsOpened() {
            return opened.get();
        }

        /**
         * Makes the next statement whose SQL contains sqlFragment throw SQLException instead of
         * running, as if the database failed at that point. Only the first match fails.
         */
        public void failNextStatementContaining(String sqlFragment) {
            failOn = sqlFragment;
        }

        /**
         * Marks the calling thread, and every thread it starts from now on, as part of this
         * session. For threads that already existed when the session began but start work for it,
         * like the JavaFX thread. The mark stays after the session ends, so anything such a thread
         * starts later is refused too, until another session claims it.
         */
        public void claimCurrentThread() {
            OWNER.set(this);
        }

        /**
         * Hands out a stand-in for the real connection, which is what Database.getConnection
         * returns. Throws once the session has ended, so late work can't use a connection that is
         * about to be rolled back and closed.
         */
        private Connection open() throws SQLException {
            if (ended) {
                throw lateRequest();
            }
            opened.incrementAndGet();
            inUse.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, new StandIn());
        }

        /**
         * Waits up to DRAIN_MS for every stand-in connection to be closed, so work that was already
         * using one can finish on the isolated tables before they're rolled back. Work still going
         * after that fails once the real connection closes, which only ever touches the isolated
         * connection.
         */
        private void awaitIdle() throws InterruptedException {
            long deadline = System.currentTimeMillis() + DRAIN_MS;
            while (inUse.get() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }

        /**
         * Passes calls through to the real connection, except the ones that would end its
         * transaction or close it. Those act on a savepoint instead, so the temporary tables stay.
         */
        private final class StandIn implements java.lang.reflect.InvocationHandler {
            private Savepoint transaction;   // Set while the caller has autocommit off
            private final AtomicBoolean closed = new AtomicBoolean();   // So a second close isn't counted

            /** Handles one call made on the stand-in connection and returns its result. */
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                switch (method.getName()) {
                    case "setAutoCommit":
                        // Turning autocommit off starts a transaction, so mark where it began
                        if (!(Boolean) args[0] && transaction == null) {
                            transaction = conn.setSavepoint();
                        }
                        return null;
                    case "getAutoCommit":
                        return transaction == null;
                    case "commit":
                        // Keep the work but leave the real transaction open, so it's rolled back later
                        if (transaction != null) {
                            conn.releaseSavepoint(transaction);
                            transaction = null;
                        }
                        return null;
                    case "rollback":
                        if (args == null && transaction != null) {
                            conn.rollback(transaction);
                            transaction = null;
                        }
                        return null;
                    case "close":
                        if (closed.compareAndSet(false, true)) {
                            inUse.decrementAndGet();
                        }
                        // A real connection closed mid-transaction discards that transaction
                        if (transaction != null) {
                            conn.rollback(transaction);
                            transaction = null;
                        }
                        return null;
                    case "isClosed":
                        return closed.get();
                    case "prepareStatement":
                        failIfAsked((String) args[0]);
                        break;
                    default:
                        break;
                }
                try {
                    return method.invoke(conn, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }

            /** Throws the failure a test asked for, the first time matching SQL is prepared. */
            private void failIfAsked(String sql) throws SQLException {
                String fragment = failOn;
                if (fragment != null && sql.contains(fragment)) {
                    failOn = null;
                    throw new SQLException("Failure injected by the test at: " + fragment);
                }
            }
        }
    }

    /** Private because everything is static. Call IsolatedDatabase.run instead. */
    private IsolatedDatabase() {}

    /**
     * Runs the work with every Database connection going to empty temporary corpus tables, then
     * rolls everything back, even if the work fails. Fails before running the work if any table
     * name would still reach the shared database. Afterward, any connection request from a thread
     * the work started throws instead of reaching the shared database. Sessions can't be nested.
     */
    public static void run(Work work) throws Exception {
        installRouter();
        if (active != null) {
            throw new IllegalStateException("IsolatedDatabase.run can't be nested");
        }
        try (Connection conn = shared.open()) {
            conn.setAutoCommit(false);
            try {
                createTables(conn);
                checkIsolated(conn);

                // Every getConnection call goes to this connection while the work runs
                Session session = new Session(conn);
                OWNER.set(session);
                active = session;
                try {
                    work.run(session);
                } finally {
                    // End the session before anything else, so no request slips through to the shared
                    // database while the rest of the cleanup runs
                    session.ended = true;
                    active = null;
                    OWNER.remove();   // The test thread itself goes on to other tests
                    session.awaitIdle();
                }
            } finally {
                conn.rollback();
            }
        }
    }

    /**
     * Points Database.getConnection at route, the first time a session runs. It stays in place for
     * the rest of the test run, because a thread from an ended session could ask for a connection
     * at any time. Between sessions it passes unmarked requests straight to the real source.
     */
    private static synchronized void installRouter() {
        if (shared == null) {
            shared = Database.swapConnectionSource(IsolatedDatabase::route);
        }
    }

    /**
     * Decides where one Database.getConnection call goes: refused if it comes from a session that
     * has ended, to the running session if there is one, and otherwise to the real source.
     */
    private static Connection route() throws SQLException {
        Session owner = OWNER.get();
        if (owner != null && owner.ended) {
            throw lateRequest();
        }
        Session current = active;
        return current != null ? current.open() : shared.open();
    }

    /** The error a late request gets, saying why it was refused. */
    private static SQLException lateRequest() {
        return new SQLException("Refused a database connection for work that outlived its isolated "
                + "test, so it can't reach the shared database");
    }

    /** Creates the temporary tables on the given connection. */
    private static void createTables(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            for (String sql : CREATE_TABLES) {
                statement.execute(sql);
            }
        }
    }

    /** Throws unless every table name resolves to this connection's temporary schema. */
    private static void checkIsolated(Connection conn) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT relnamespace = pg_my_temp_schema() FROM pg_class WHERE oid = to_regclass(?)")) {
            for (String table : TABLES) {
                statement.setString(1, table);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        throw new IllegalStateException(table + " does not resolve to the temporary table");
                    }
                }
            }
        }
    }
}
