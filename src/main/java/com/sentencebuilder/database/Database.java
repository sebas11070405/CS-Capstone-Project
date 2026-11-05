/*
 * Opens connections to the shared Supabase Postgres database. Credentials come
 * from DB_URL, DB_USER, and DB_PASSWORD in the .env file at the project root,
 * or from real environment variables of the same name. Copy .env.example to
 * .env to get started. Use inTransaction for work that must commit or fail as
 * a whole, and getConnection for one-off queries.
 */

package com.sentencebuilder.database;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {

    // Falls back to system environment variables when a key is not in .env
    private static final Dotenv ENV = Dotenv.configure().ignoreIfMissing().load();

    /**
     * Database work passed to inTransaction as a lambda. Unlike Function, it may
     * throw SQLException and one checked exception to the caller's choosing.
     */
    @FunctionalInterface
    public interface TransactionWork<T, E extends Exception> {
        T run(Connection conn) throws SQLException, E;
    }

    /** Private because everything is static. Call Database.getConnection() instead. */
    private Database() {}

    /**
     * Opens a connection, runs the work in one transaction, and commits it.
     * Returns whatever the work returns. If the work throws, everything it did
     * is rolled back and the exception is rethrown, so a failure leaves the
     * database unchanged.
     */
    public static <T, E extends Exception> T inTransaction(TransactionWork<T, E> work) throws SQLException, E {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            // Roll back on any failure so no half-finished work is left behind
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Opens a new connection to Supabase. The caller must close it, usually with
     * try-with-resources.
     */
    public static Connection getConnection() throws SQLException {
        String url = require("DB_URL");
        String user = require("DB_USER");
        String password = require("DB_PASSWORD");

        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Returns the value of a .env or environment key. Throws with a clear message
     * when it is missing or blank, instead of failing later with a confusing
     * connection error.
     */
    private static String require(String name) {
        String value = ENV.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set in .env or the environment");
        }
        return value;
    }
}
