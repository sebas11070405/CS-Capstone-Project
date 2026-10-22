/*
 * Opens connections to the Supabase Postgres database.
 * Credentials come from DB_URL, DB_USER and DB_PASSWORD in the .env file
 * at the project root, or from real environment variables of the same name.
 */

package com.sentencebuilder.database;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {

    // Falls back to system environment variables when a key is not in .env
    private static final Dotenv ENV = Dotenv.configure().ignoreIfMissing().load();

    // Private so no one can create a Database object; everything is static, call Database.getConnection()
    private Database() {}

    // Opens a new connection; callers should close it with try-with-resources
    public static Connection getConnection() throws SQLException {
        String url = require("DB_URL");
        String user = require("DB_USER");
        String password = require("DB_PASSWORD");

        return DriverManager.getConnection(url, user, password);
    }

    private static String require(String name) {
        String value = ENV.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set in .env or the environment");
        }
        return value;
    }
}
