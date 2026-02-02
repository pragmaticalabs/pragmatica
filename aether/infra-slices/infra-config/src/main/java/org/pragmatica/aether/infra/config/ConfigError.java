package org.pragmatica.aether.infra.config;

import org.pragmatica.lang.Cause;

/**
 * Errors for configuration operations.
 */
public sealed interface ConfigError extends Cause {
    /**
     * Configuration key not found.
     */
    record KeyNotFound(String key, ConfigScope scope) implements ConfigError {
        @Override
        public String message() {
            return "Configuration key not found: " + key + " in scope " + scope;
        }
    }

    /**
     * Configuration parsing failed.
     */
    record ParseFailed(String location, String reason) implements ConfigError {
        @Override
        public String message() {
            return "Failed to parse configuration from " + location + ": " + reason;
        }
    }

    /**
     * Configuration validation failed.
     */
    record ValidationFailed(String key, String reason) implements ConfigError {
        @Override
        public String message() {
            return "Configuration validation failed for " + key + ": " + reason;
        }
    }

    /**
     * Watcher registration failed.
     */
    record WatchFailed(String key, String reason) implements ConfigError {
        @Override
        public String message() {
            return "Failed to watch configuration key " + key + ": " + reason;
        }
    }
}
