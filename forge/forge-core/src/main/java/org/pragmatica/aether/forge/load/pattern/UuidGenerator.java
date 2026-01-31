package org.pragmatica.aether.forge.load.pattern;

import java.util.UUID;

/**
 * Generates random UUIDs.
 * <p>
 * Pattern: {@code ${uuid}}
 */
public record UuidGenerator() implements PatternGenerator {
    public static final String TYPE = "uuid";

    public static UuidGenerator uuidGenerator() {
        return new UuidGenerator();
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
