package org.pragmatica.aether.pg.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/// Loads the PostgreSQL PEG grammar from the resource file.
public final class PostgresGrammar {
    private PostgresGrammar() {}

    public static final String GRAMMAR = loadGrammar();

    private static String loadGrammar() {
        try (var stream = PostgresGrammar.class.getResourceAsStream("postgres.peg")) {
            if (stream == null) {
                throw new IllegalStateException("postgres.peg resource not found");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load postgres.peg", e);
        }
    }
}
