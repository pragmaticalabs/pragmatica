package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/// Controls how operators can override per-route security in blueprint.toml.
public enum OverridePolicy {
    /// Operator can only make routes MORE restrictive (default).
    STRENGTHEN_ONLY,
    /// Operator can change security in any direction.
    FULL,
    /// No overrides allowed — security is baked in at compile time.
    NONE;

    private static final Cause EMPTY_POLICY = Causes.cause("Empty override policy");
    private static final Fn1<Cause, String> UNKNOWN_POLICY = Causes.forOneValue("Unknown override policy: %s");

    /// Parse from TOML string value.
    public static Result<OverridePolicy> parse(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_POLICY.result();
        }

        return switch (value.trim().toLowerCase()) {
            case "strengthen_only" -> Result.success(STRENGTHEN_ONLY);
            case "full" -> Result.success(FULL);
            case "none" -> Result.success(NONE);
            default -> UNKNOWN_POLICY.apply(value).result();
        };
    }
}
