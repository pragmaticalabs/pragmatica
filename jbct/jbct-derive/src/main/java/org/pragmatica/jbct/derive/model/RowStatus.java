package org.pragmatica.jbct.derive.model;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

/// The status of an answer row (SPEC.md §3): a real answer, or an explicit `UNKNOWN`.
///
/// `UNKNOWN` is a first-class, valid input — never guessed. It passes the entry gate and
/// propagates as UNKNOWN pressure (SPEC.md §4).
public enum RowStatus {
    ANSWERED,
    UNKNOWN;

    private static final Fn1<Cause, String> INVALID_STATUS =
        Causes.forOneValue("Invalid status '%s' — expected 'answered' or 'UNKNOWN'");

    /// Parse a raw status string (case-insensitive).
    public static Result<RowStatus> rowStatus(String raw) {
        return Verify.ensure(raw, Verify.Is::present, INVALID_STATUS)
                     .map(String::trim)
                     .flatMap(RowStatus::parse);
    }

    private static Result<RowStatus> parse(String trimmed) {
        return switch (trimmed.toLowerCase()) {
            case "answered" -> Result.success(ANSWERED);
            case "unknown" -> Result.success(UNKNOWN);
            default -> INVALID_STATUS.apply(trimmed).result();
        };
    }
}
