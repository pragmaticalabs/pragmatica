package org.pragmatica.aether.forge.load;

import org.pragmatica.lang.Cause;

import java.util.List;

/**
 * Error types for load configuration parsing and validation.
 */
public sealed interface LoadConfigError extends Cause {
    /**
     * TOML parsing failed.
     */
    record ParseFailed(String details) implements LoadConfigError {
        public static ParseFailed parseFailed(String details) {
            return new ParseFailed(details);
        }

        @Override
        public String message() {
            return "Failed to parse load config: " + details;
        }
    }

    /**
     * Configuration validation failed.
     */
    record ValidationFailed(List<String> errors) implements LoadConfigError {
        @Override
        public String message() {
            return "Load config validation failed: " + String.join("; ", errors);
        }
    }

    /**
     * File not found or cannot be read.
     */
    record FileReadFailed(String path, Throwable cause) implements LoadConfigError {
        @Override
        public String message() {
            return "Cannot read load config file: " + path + " - " + cause.getMessage();
        }
    }

    /**
     * Invalid pattern in body or path variable.
     */
    record PatternInvalid(String pattern, String reason) implements LoadConfigError {
        @Override
        public String message() {
            return "Invalid pattern '" + pattern + "': " + reason;
        }
    }
}
