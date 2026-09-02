package org.pragmatica.jbct.derive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/// Shared helper for loading answer-sheet test resources from `src/test/resources/sheets`.
public final class Sheets {
    private Sheets() {}

    public static String load(String fileName) {
        try (var in = Sheets.class.getResourceAsStream("/sheets/" + fileName)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: sheets/" + fileName);
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
