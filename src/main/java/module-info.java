module com.sentencebuilder {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires org.postgresql.jdbc;

    opens com.sentencebuilder.ui to javafx.fxml;
    exports com.sentencebuilder.app;
}
