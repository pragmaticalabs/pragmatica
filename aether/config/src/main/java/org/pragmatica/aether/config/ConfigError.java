package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

/// Error types for configuration operations.
public sealed interface ConfigError extends Cause {
    /// Config section not found in configuration.
    record SectionNotFound(String section) implements ConfigError {
        public static SectionNotFound sectionNotFound(String section) {
            return new SectionNotFound(section);
        }

        @Override
        public String message() {
            return "Config section not found: " + section;
        }
    }

    static SectionNotFound sectionNotFound(String section) {
        return SectionNotFound.sectionNotFound(section);
    }

    /// Failed to parse configuration section.
    record ParseFailed(String section, String reason, Option<Throwable> cause) implements ConfigError {
        public static ParseFailed parseFailed(String section, String reason) {
            return new ParseFailed(section, reason, Option.none());
        }

        public static ParseFailed parseFailed(String section, Throwable cause) {
            return new ParseFailed(section, cause.getMessage(), Option.option(cause));
        }

        @Override
        public String message() {
            return "Failed to parse config section '" + section + "': " + reason;
        }

        @Override
        public Option<Cause> source() {
            return cause.map(Causes::fromThrowable);
        }
    }

    static ParseFailed parseFailed(String section, String reason) {
        return ParseFailed.parseFailed(section, reason);
    }

    static ParseFailed parseFailed(String section, Throwable cause) {
        return ParseFailed.parseFailed(section, cause);
    }

    /// Type mismatch in configuration section.
    record TypeMismatch(String section, String expected, String actual) implements ConfigError {
        public static TypeMismatch typeMismatch(String section, String expected, String actual) {
            return new TypeMismatch(section, expected, actual);
        }

        @Override
        public String message() {
            return "Config type mismatch in '" + section + "': expected " + expected + ", got " + actual;
        }
    }

    static TypeMismatch typeMismatch(String section, String expected, String actual) {
        return TypeMismatch.typeMismatch(section, expected, actual);
    }

    /// Configuration file not found.
    record FileNotFound(String path) implements ConfigError {
        public static FileNotFound fileNotFound(String path) {
            return new FileNotFound(path);
        }

        @Override
        public String message() {
            return "Config file not found: " + path;
        }
    }

    static FileNotFound fileNotFound(String path) {
        return FileNotFound.fileNotFound(path);
    }

    /// Failed to read configuration file.
    record ReadFailed(String path, Throwable cause) implements ConfigError {
        public static ReadFailed readFailed(String path, Throwable cause) {
            return new ReadFailed(path, cause);
        }

        @Override
        public String message() {
            return "Failed to read config file '" + path + "': " + cause.getMessage();
        }

        @Override
        public Option<Cause> source() {
            return Option.some(Causes.fromThrowable(cause));
        }
    }

    static ReadFailed readFailed(String path, Throwable cause) {
        return ReadFailed.readFailed(path, cause);
    }

    /// Failed to resolve a secret placeholder in configuration.
    record SecretResolutionFailed(String key, String secretPath, Cause underlying) implements ConfigError {
        public static SecretResolutionFailed secretResolutionFailed(String key, String secretPath, Cause underlying) {
            return new SecretResolutionFailed(key, secretPath, underlying);
        }

        @Override
        public String message() {
            return "Failed to resolve secret '${secrets:" + secretPath + "}' in config key '" + key + "': " + underlying.message();
        }

        @Override
        public Option<Cause> source() {
            return Option.some(underlying);
        }
    }

    static SecretResolutionFailed secretResolutionFailed(String key, String secretPath, Cause underlying) {
        return SecretResolutionFailed.secretResolutionFailed(key, secretPath, underlying);
    }
}
