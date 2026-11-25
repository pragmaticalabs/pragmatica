package org.pragmatica.jbct.format;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;


/// Sealed interface for formatting errors.
public sealed interface FormattingError extends Cause {
    record ParseFailed(String file, int line, int column, String details) implements FormattingError {
        @Override
        public String message() {
            return "Parse error at %s:%d:%d - %s".formatted(file, line, column, details);
        }

        @Override
        public Option<Cause> source() {
            return Option.none();
        }
    }

    record IoFailed(String file, Throwable exception) implements FormattingError {
        @Override
        public String message() {
            return "I/O error for %s: %s".formatted(file, exception.getMessage());
        }

        @Override
        public Option<Cause> source() {
            return Option.none();
        }
    }

    record FormatterError(String details, Option<Cause> causeSource) implements FormattingError {
        @Override
        public String message() {
            return "Formatting error: " + details;
        }

        @Override
        public Option<Cause> source() {
            return causeSource;
        }
    }

    /// The formatted text would lose or change content (#3691): the file is left untouched.
    record ContentChanged(String file, int line, String details) implements FormattingError {
        @Override
        public String message() {
            return "Formatter refused %s: %s (input line %d); the file was left untouched".formatted(file, details, line);
        }

        @Override
        public Option<Cause> source() {
            return Option.none();
        }
    }

    // Factory methods
    static FormattingError parseFailed(String file, int line, int column, String details) {
        return new ParseFailed(file, line, column, details);
    }

    static FormattingError ioFailed(String file, Throwable exception) {
        return new IoFailed(file, exception);
    }

    static FormattingError contentChanged(String file, int line, String details) {
        return new ContentChanged(file, line, details);
    }

    static FormattingError formatterError(String details) {
        return new FormatterError(details, Option.none());
    }

    static FormattingError formatterError(String details, Cause causeSource) {
        return new FormatterError(details, Option.option(causeSource));
    }
}
