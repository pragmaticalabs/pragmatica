package org.pragmatica.jbct.derive;

import org.junit.jupiter.api.Assertions;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.parse.SheetParser;

/// Test helper: parse an inline TOML sheet, failing the test if it does not parse. Stage unit tests
/// author minimal synthetic sheets this way (integration-first: they exercise the real parser).
public final class Inline {
    private Inline() {}

    public static AnswerSheet sheet(String toml) {
        return SheetParser.parse(toml, "inline")
                          .fold(cause -> Assertions.fail("inline sheet did not parse: " + cause.message()), sheet -> sheet);
    }
}
