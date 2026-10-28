package com.sentencebuilder.importer;

import com.sentencebuilder.database.Database;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Importer {

    private static final String UPSERT_WORDS = """
            INSERT INTO words (word, total_count, start_count, end_count)
            SELECT * FROM unnest(?::text[], ?::int[], ?::int[], ?::int[])
            ON CONFLICT (word) DO UPDATE SET
                total_count = words.total_count + EXCLUDED.total_count,
                start_count = words.start_count + EXCLUDED.start_count,
                end_count   = words.end_count   + EXCLUDED.end_count
            """;

    public CorpusStats importFile(Path file) throws IOException, SQLException {
        CorpusStats stats;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stats = CorpusStats.from(reader);
        }
        try (Connection conn = Database.getConnection()) {
            writeWords(conn, stats);
        }
        return stats;
    }

    static void writeWords(Connection conn, CorpusStats stats) throws SQLException {
        // Sorted so concurrent imports lock rows in the same order and can't deadlock
        List<String> words = new ArrayList<>(stats.words().keySet());
        Collections.sort(words);

        int n = words.size();
        Integer[] totals = new Integer[n];
        Integer[] starts = new Integer[n];
        Integer[] ends = new Integer[n];
        for (int i = 0; i < n; i++) {
            CorpusStats.WordCounts counts = stats.words().get(words.get(i));
            totals[i] = counts.total();
            starts[i] = counts.start();
            ends[i] = counts.end();
        }

        try (PreparedStatement statement = conn.prepareStatement(UPSERT_WORDS)) {
            statement.setArray(1, conn.createArrayOf("text", words.toArray()));
            statement.setArray(2, conn.createArrayOf("int4", totals));
            statement.setArray(3, conn.createArrayOf("int4", starts));
            statement.setArray(4, conn.createArrayOf("int4", ends));
            statement.executeUpdate();
        }
    }
}
