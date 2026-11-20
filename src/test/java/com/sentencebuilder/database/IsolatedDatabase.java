/*
 * Runs test code against an empty, private copy of the corpus tables, so code that opens its own
 * connections (the generators, Importer, the UI controllers) can be tested end to end without
 * reading or changing shared data.
 *
 * run opens one real connection, creates temporary source_files, words, bigrams, and trigrams
 * tables on it (they hide the shared tables of the same name on that connection only), and checks
 * that every one of those names really resolves to the temporary copy. It then points
 * Database.getConnection at that connection until the work finishes, and always rolls back at the
 * end, so the temporary tables and everything written to them disappear.
 *
 * Each connection the code under test opens is a stand-in for the one real connection. Its
 * transactions become savepoints, so a commit keeps the work for the rest of the test and a
 * rollback undoes just that transaction, the same as on a real database. Closing it does nothing.
 * A test can also make the next statement containing some SQL fail, to check rollbacks.
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
        private volatile String failOn;   // SQL text that makes the next matching statement fail

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

        /** Hands out a stand-in for the real connection, which is what Database.getConnection returns. */
        private Connection open() {
            opened.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, new StandIn());
        }

        /**
         * Passes calls through to the real connection, except the ones that would end its
         * transaction or close it. Those act on a savepoint instead, so the temporary tables stay.
         */
        private final class StandIn implements java.lang.reflect.InvocationHandler {
            private Savepoint transaction;   // Set while the caller has autocommit off

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
                        // A real connection closed mid-transaction discards that transaction
                        if (transaction != null) {
                            conn.rollback(transaction);
                            transaction = null;
                        }
                        return null;
                    case "isClosed":
                        return false;
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
     * name would still reach the shared database.
     */
    public static void run(Work work) throws Exception {
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                createTables(conn);
                checkIsolated(conn);

                // Every getConnection call goes to this connection until the work is done
                Session session = new Session(conn);
                Database.ConnectionSource previous = Database.swapConnectionSource(session::open);
                try {
                    work.run(session);
                } finally {
                    Database.swapConnectionSource(previous);
                }
            } finally {
                conn.rollback();
            }
        }
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
