package org.pragmatica.aether.pg.parser;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/// Loads the PostgreSQL PEG grammar from the resource file.
public final class PostgresGrammar {
    private PostgresGrammar() {}

    public static final String GRAMMAR = loadGrammar().unwrap();

    private static Result<String> loadGrammar() {
        try (var stream = PostgresGrammar.class.getResourceAsStream("postgres.peg")) {
            if ( stream == null) {
            return Causes.cause("postgres.peg resource not found").result();}
            return Result.success(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }


















        catch (IOException e) {
            return Causes.cause("Failed to load postgres.peg: " + e.getMessage()).result();
        }
    }
}
