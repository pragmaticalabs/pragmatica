package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Result;

import java.util.UUID;

import static org.pragmatica.lang.Result.success;

/// Generates random UUIDs.
///
/// Pattern: `${uuid`}
public record UuidGenerator() implements PatternGenerator {
    public static final String TYPE = "uuid";

    public static Result<UuidGenerator> uuidGenerator() {
        return success(new UuidGenerator());
    }

    @Override
    public String generate() {
        return UUID.randomUUID()
                   .toString();
    }

    @Override
    public String pattern() {
        return "${uuid}";
    }
}
