package org.pragmatica.aether.forge.load;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Result.success;

/// Error types for load configuration parsing and validation.
public sealed interface LoadConfigError extends Cause {
    /// TOML parsing failed.
    record ParseFailed(String details) implements LoadConfigError {
        public static Result<ParseFailed> parseFailed(String details) {
            return success(new ParseFailed(details));
        }

        @Override
        public String message() {
            return "Failed to parse load config: " + details;
        }
    }

    /// Configuration validation failed.
    record ValidationFailed(List<String> errors) implements LoadConfigError {
        public static Result<ValidationFailed> validationFailed(List<String> errors) {
            return success(new ValidationFailed(errors));
        }

        @Override
        public String message() {
            return "Load config validation failed: " + String.join("; ", errors);
        }
    }

    /// File not found or cannot be read.
    record FileReadFailed(String path, Throwable cause) implements LoadConfigError {
        public static Result<FileReadFailed> fileReadFailed(String path, Throwable cause) {
            return success(new FileReadFailed(path, cause));
        }

        @Override
        public String message() {
            return "Cannot read load config file: " + path + " - " + cause.getMessage();
        }
    }

    /// Invalid pattern in body or path variable.
    record PatternInvalid(String pattern, String reason) implements LoadConfigError {
        public static Result<PatternInvalid> patternInvalid(String pattern, String reason) {
            return success(new PatternInvalid(pattern, reason));
        }

        @Override
        public String message() {
            return "Invalid pattern '" + pattern + "': " + reason;
        }
    }

    record unused() implements LoadConfigError {
        @Override
        public String message() {
            return "";
        }
    }
}
