package org.pragmatica.jbct.derive.parse;

import org.pragmatica.config.toml.TomlError;
import org.pragmatica.lang.Cause;

/// Structural errors raised while parsing an answer sheet into the typed model — distinct from
/// entry-gate findings (SPEC.md §4), which are semantic and reported as diagnostics.
///
/// A parse failure means the sheet could not be turned into an [org.pragmatica.jbct.derive.model.AnswerSheet]
/// at all; the entry gate never runs on it. Each variant carries the sheet line where known
/// (0 when the fault is document-level).
public sealed interface SheetError extends Cause {
    /// The 1-based sheet line the fault is anchored to, or 0 when document-level.
    default int line() {
        return 0;
    }

    /// The underlying file could not be read.
    record FileReadFailed(String path, String detail) implements SheetError {
        @Override
        public String message() {
            return "Failed to read sheet '" + path + "': " + detail;
        }
    }

    /// The TOML document itself is malformed (syntax, duplicate keys, unsupported feature).
    record Malformed(int line, String detail) implements SheetError {
        @Override
        public String message() {
            return "Malformed sheet: " + detail;
        }
    }

    /// A bare (unquoted) date — the form SPEC.md §3 shows in its example, but which the shared
    /// TomlParser does not support. Rather than let it vanish, this fails loudly and tells the
    /// author to quote it.
    record UnquotedDate(int line) implements SheetError {
        @Override
        public String message() {
            return "Unquoted date at line " + line
                 + " — the TOML parser does not support bare dates; quote it as a string, e.g. date = \"2026-07-12\"";
        }
    }

    /// The mandatory root `schema_version` key is absent.
    record MissingSchemaVersion() implements SheetError {
        @Override
        public String message() {
            return "Missing 'schema_version' — every sheet must declare it (SPEC.md §3)";
        }
    }

    /// The sheet declares a schema major the engine does not support.
    record UnsupportedSchemaVersion(String found) implements SheetError {
        @Override
        public String message() {
            return "Unsupported schema_version '" + found + "' — this engine pins major 0";
        }
    }

    /// A required section or field is missing from the sheet.
    record MissingField(String location) implements SheetError {
        @Override
        public String message() {
            return "Missing required field: " + location;
        }
    }

    /// A row or fact could not be parsed into typed values.
    record MalformedRow(String table, int index, int line, String detail) implements SheetError {
        @Override
        public String message() {
            return "Malformed [[" + table + "]] row #" + (index + 1) + ": " + detail;
        }
    }

    /// Wrap a [TomlError] into a [SheetError], recovering its line where the TOML error carries one.
    /// The unsupported-date case (SPEC.md §3's own example form) is surfaced as a specific,
    /// actionable [UnquotedDate] rather than a generic malformed-sheet error.
    static SheetError fromToml(TomlError error) {
        return error instanceof TomlError.UnsupportedFeature feature && feature.feature().contains("date")
               ? new UnquotedDate(feature.line())
               : new Malformed(tomlLine(error), error.message());
    }

    private static int tomlLine(TomlError error) {
        return switch (error) {
            case TomlError.SyntaxError e -> e.line();
            case TomlError.InvalidValue e -> e.line();
            case TomlError.UnterminatedString e -> e.line();
            case TomlError.UnterminatedArray e -> e.line();
            case TomlError.UnterminatedMultilineString e -> e.line();
            case TomlError.DuplicateKey e -> e.line();
            case TomlError.DuplicateSection e -> e.line();
            case TomlError.TableTypeMismatch e -> e.line();
            case TomlError.UnsupportedFeature e -> e.line();
            case TomlError.InvalidEscapeSequence e -> e.line();
            case TomlError.InvalidSurrogate e -> e.line();
            case TomlError.DottedKeyConflict e -> e.line();
            case TomlError.FileReadFailed _ -> 0;
        };
    }
}
