/*
 * Opens connections to the shared Supabase Postgres database. Credentials come
 * from DB_URL, DB_USER, and DB_PASSWORD in the .env file at the project root,
 * or from real environment variables of the same name. Copy .env.example to
 * .env to get started.
 */

package com.sentencebuilder.database;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {

    // Falls back to system environment variables when a key is not in .env
    private static final Dotenv ENV = Dotenv.configure().ignoreIfMissing().load();

    /** Private because everything is static. Call Database.getConnection() instead. */
    private Database() {}

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
