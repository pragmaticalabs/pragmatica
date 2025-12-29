package org.pragmatica.jbct.format;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Sealed interface for formatting errors.
 */
public sealed interface FormattingError extends Cause {
    record ParseError(String file, int line, int column, String details) implements FormattingError {
        @Override
        public String message() {
            return "Parse error at %s:%d:%d - %s".formatted(file, line, column, details);
        }

        @Override
        public Option<Cause> source() {
            return Option.none();
        }
    }

    record IoError(String file, Throwable exception) implements FormattingError {
        @Override
        public String message() {
            return "I/O error for %s: %s".formatted(file, exception.getMessage());
        }

        @Override
        public Option<Cause> source() {
            return Option.none();
        }
    }

    record FormatterError(String details, Cause causeSource) implements FormattingError {
        @Override
        public String message() {
            return "Formatting error: " + details;
        }

        @Override
        public Option<Cause> source() {
            return Option.option(causeSource);
        }
    }

    // Factory methods
    static FormattingError parseError(String file, int line, int column, String details) {
        return new ParseError(file, line, column, details);
    }

    static FormattingError ioError(String file, Throwable exception) {
        return new IoError(file, exception);
    }

    static FormattingError formatterError(String details) {
        return new FormatterError(details, null);
    }

    static FormattingError formatterError(String details, Cause causeSource) {
        return new FormatterError(details, causeSource);
    }
}
