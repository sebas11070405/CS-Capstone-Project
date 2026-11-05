/*
 * Lists the libraries the app depends on: JavaFX for the UI, JDBC and the
 * Postgres driver for Supabase, and dotenv for reading .env.
 */

module com.sentencebuilder {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires org.postgresql.jdbc;
    requires io.github.cdimascio.dotenv.java;

    // FXMLLoader needs reflective access to inject the controllers' @FXML fields
    opens com.sentencebuilder.ui to javafx.fxml;
    // JavaFX must be able to reach Main to launch it
    exports com.sentencebuilder.app;
}
