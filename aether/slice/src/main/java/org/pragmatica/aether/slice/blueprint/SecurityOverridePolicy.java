package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.serialization.Codec;

/// Policy controlling how blueprint security overrides are applied to route security.
///
/// - STRENGTHEN_ONLY: override applied only if new security level >= original level
/// - FULL: any override is applied regardless of direction
/// - NONE: all overrides are rejected (logged as warnings)
@Codec public enum SecurityOverridePolicy {
    STRENGTHEN_ONLY,
    FULL,
    NONE;
    /// Parse policy from blueprint TOML string.
    public static SecurityOverridePolicy fromString(String raw) {
        return switch (raw.toLowerCase().strip()) {case "strengthen_only" -> STRENGTHEN_ONLY;case "full" -> FULL;case "none" -> NONE;default -> STRENGTHEN_ONLY;};
    }
}
