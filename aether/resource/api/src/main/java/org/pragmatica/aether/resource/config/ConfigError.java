package org.pragmatica.aether.resource.config;

import org.pragmatica.lang.Cause;


/// Errors for configuration operations.
public sealed interface ConfigError extends Cause {
    record KeyNotFound(String key, ConfigScope scope) implements ConfigError {
        @Override public String message() {
            return "Configuration key not found: " + key + " in scope " + scope;
        }
    }

    record ParseFailed(String location, String reason) implements ConfigError {
        @Override public String message() {
            return "Failed to parse configuration from " + location + ": " + reason;
        }
    }

    record ValidationFailed(String key, String reason) implements ConfigError {
        @Override public String message() {
            return "Configuration validation failed for " + key + ": " + reason;
        }
    }

    record WatchFailed(String key, String reason) implements ConfigError {
        @Override public String message() {
            return "Failed to watch configuration key " + key + ": " + reason;
        }
    }
}
