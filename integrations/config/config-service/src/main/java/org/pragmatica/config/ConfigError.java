package org.pragmatica.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Error types for configuration operations.
public sealed interface ConfigError extends Cause {
    /// Config section not found in configuration.
    record SectionNotFound(String section) implements ConfigError {
        public static Result<SectionNotFound> sectionNotFound(String section) {
            return success(new SectionNotFound(section));
        }

        @Override
        public String message() {
            return "Config section not found: " + section;
        }
    }

    static SectionNotFound sectionNotFound(String section) {
        return SectionNotFound.sectionNotFound(section)
                              .unwrap();
    }

    /// Failed to parse configuration section.
    record ParseFailed(String section, String reason, Option<Throwable> cause) implements ConfigError {
        public static Result<ParseFailed> parseFailed(String section, String reason) {
            return success(new ParseFailed(section, reason, none()));
        }

        public static Result<ParseFailed> parseFailed(String section, Throwable cause) {
            return success(new ParseFailed(section, cause.getMessage(), option(cause)));
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
        return ParseFailed.parseFailed(section, reason)
                          .unwrap();
    }

    static ParseFailed parseFailed(String section, Throwable cause) {
        return ParseFailed.parseFailed(section, cause)
                          .unwrap();
    }

    /// Type mismatch in configuration section.
    record TypeMismatch(String section, String expected, String actual) implements ConfigError {
        public static Result<TypeMismatch> typeMismatch(String section, String expected, String actual) {
            return success(new TypeMismatch(section, expected, actual));
        }

        @Override
        public String message() {
            return "Config type mismatch in '" + section + "': expected " + expected + ", got " + actual;
        }
    }

    static TypeMismatch typeMismatch(String section, String expected, String actual) {
        return TypeMismatch.typeMismatch(section, expected, actual)
                           .unwrap();
    }

    /// Configuration file not found.
    record FileNotFound(String path) implements ConfigError {
        public static Result<FileNotFound> fileNotFound(String path) {
            return success(new FileNotFound(path));
        }

        @Override
        public String message() {
            return "Config file not found: " + path;
        }
    }

    static FileNotFound fileNotFound(String path) {
        return FileNotFound.fileNotFound(path)
                           .unwrap();
    }

    /// Failed to read configuration file.
    record ReadFailed(String path, Throwable cause) implements ConfigError {
        public static Result<ReadFailed> readFailed(String path, Throwable cause) {
            return success(new ReadFailed(path, cause));
        }

        @Override
        public String message() {
            return "Failed to read config file '" + path + "': " + cause.getMessage();
        }

        @Override
        public Option<Cause> source() {
            return some(Causes.fromThrowable(cause));
        }
    }

    static ReadFailed readFailed(String path, Throwable cause) {
        return ReadFailed.readFailed(path, cause)
                         .unwrap();
    }

    /// Failed to resolve a secret placeholder in configuration.
    record SecretResolutionFailed(String key, String secretPath, Cause underlying) implements ConfigError {
        public static Result<SecretResolutionFailed> secretResolutionFailed(String key,
                                                                            String secretPath,
                                                                            Cause underlying) {
            return success(new SecretResolutionFailed(key, secretPath, underlying));
        }

        @Override
        public String message() {
            return "Failed to resolve secret '${secrets:" + secretPath + "}' in config key '" + key + "': " + underlying.message();
        }

        @Override
        public Option<Cause> source() {
            return some(underlying);
        }
    }

    static SecretResolutionFailed secretResolutionFailed(String key, String secretPath, Cause underlying) {
        return SecretResolutionFailed.secretResolutionFailed(key, secretPath, underlying)
                                     .unwrap();
    }

    record unused() implements ConfigError {
        public static Result<unused> unused() {
            return success(new unused());
        }

        @Override
        public String message() {
            return "";
        }
    }
}
