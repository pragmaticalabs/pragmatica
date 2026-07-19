package org.pragmatica.jbct.derive.model;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

/// Whether a run is derived from nothing (`greenfield`) or audited against a system's
/// current position (`living`) — SPEC.md §3. A `living` run supplies a `[current_vector]`.
public enum Mode {
    GREENFIELD,
    LIVING;

    private static final Fn1<Cause, String> INVALID_MODE =
        Causes.forOneValue("Invalid mode '%s' — expected 'greenfield' or 'living'");

    /// Parse a raw mode string (case-insensitive).
    public static Result<Mode> mode(String raw) {
        return Verify.ensure(raw, Verify.Is::present, INVALID_MODE)
                     .map(String::trim)
                     .flatMap(Mode::parse);
    }

    private static Result<Mode> parse(String trimmed) {
        return switch (trimmed.toLowerCase()) {
            case "greenfield" -> Result.success(GREENFIELD);
            case "living" -> Result.success(LIVING);
            default -> INVALID_MODE.apply(trimmed).result();
        };
    }
}
