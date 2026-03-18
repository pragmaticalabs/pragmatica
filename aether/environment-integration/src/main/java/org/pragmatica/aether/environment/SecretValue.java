package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.time.Instant;

import static org.pragmatica.lang.Result.success;

/// A resolved secret with optional metadata (version, expiry).
public record SecretValue(String value,
                           Option<String> version,
                           Option<Instant> expiresAt) {
    public static Result<SecretValue> secretValue(String value) {
        return success(new SecretValue(value, Option.empty(), Option.empty()));
    }
}
