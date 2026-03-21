package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.deployment.schema.SchemaError.InvalidMigrationFormat.invalidMigrationFormat;

/// A migration entry enriched with metadata parsed from its filename.
///
/// Supported filename patterns:
///   V001__description.sql — versioned migration
///   R__description.sql    — repeatable migration
///   U001__description.sql — undo migration
///   B001__description.sql — baseline migration
///
/// @param entry       the original migration entry
/// @param type        migration type derived from filename prefix
/// @param version     version number (0 for repeatable migrations)
/// @param description human-readable description extracted from filename
public record ParsedMigration(MigrationEntry entry, MigrationType type, int version, String description) {
    /// Classification of migration scripts by their filename prefix.
    public enum MigrationType {
        /// Standard forward migration (V prefix).
        VERSIONED,
        /// Re-executed when checksum changes (R prefix).
        REPEATABLE,
        /// Reverses a versioned migration (U prefix).
        UNDO,
        /// Marks a version as the starting point without executing SQL (B prefix).
        BASELINE
    }

    private static final int SEPARATOR_LENGTH = 2;

    /// Parses a MigrationEntry filename into a ParsedMigration with type, version, and description.
    ///
    /// @param entry the migration entry to parse
    /// @return Result containing the parsed migration or a parse failure
    public static Result<ParsedMigration> parsedMigration(MigrationEntry entry) {
        return Option.option(entry.filename())
                     .filter(f -> !f.isEmpty())
                     .toResult(invalidMigrationFormat("", "filename is empty"))
                     .flatMap(filename -> parseFilename(entry, filename));
    }

    private static Result<ParsedMigration> parseFilename(MigrationEntry entry, String filename) {
        if (!filename.endsWith(".sql")) {
            return invalidMigrationFormat(filename, "must end with .sql").result();
        }
        var prefix = filename.charAt(0);
        return switch (prefix) {
            case 'R' -> parseRepeatable(entry, filename);
            case 'V' -> parseVersioned(entry, filename, MigrationType.VERSIONED);
            case 'U' -> parseVersioned(entry, filename, MigrationType.UNDO);
            case 'B' -> parseVersioned(entry, filename, MigrationType.BASELINE);
            default -> invalidMigrationFormat(filename, "unknown prefix '" + prefix + "', expected V/R/U/B").result();
        };
    }

    private static Result<ParsedMigration> parseRepeatable(MigrationEntry entry, String filename) {
        var separatorIndex = filename.indexOf("__");
        if (separatorIndex < 0) {
            return invalidMigrationFormat(filename, "missing '__' separator").result();
        }
        var description = filename.substring(separatorIndex + SEPARATOR_LENGTH, filename.length() - 4);
        if (description.isEmpty()) {
            return invalidMigrationFormat(filename, "description is empty").result();
        }
        return Result.success(new ParsedMigration(entry, MigrationType.REPEATABLE, 0, description));
    }

    private static Result<ParsedMigration> parseVersioned(MigrationEntry entry, String filename, MigrationType type) {
        var separatorIndex = filename.indexOf("__");
        if (separatorIndex < 1) {
            return invalidMigrationFormat(filename, "missing '__' separator or version number").result();
        }
        var versionStr = filename.substring(1, separatorIndex);
        return parseVersion(filename, versionStr).map(version -> extractDescription(filename, separatorIndex))
                           .flatMap(desc -> validateDescription(filename, desc))
                           .map(desc -> new ParsedMigration(entry,
                                                            type,
                                                            parseVersionUnchecked(versionStr),
                                                            desc));
    }

    private static Result<Integer> parseVersion(String filename, String versionStr) {
        if (versionStr.isEmpty()) {
            return invalidMigrationFormat(filename, "version number is empty").result();
        }
        return Result.lift(cause -> invalidMigrationFormat(filename, "invalid version number '" + versionStr + "'"),
                           () -> Integer.parseInt(versionStr));
    }

    private static String extractDescription(String filename, int separatorIndex) {
        return filename.substring(separatorIndex + SEPARATOR_LENGTH, filename.length() - 4);
    }

    private static Result<String> validateDescription(String filename, String description) {
        if (description.isEmpty()) {
            return invalidMigrationFormat(filename, "description is empty").result();
        }
        return Result.success(description);
    }

    private static int parseVersionUnchecked(String versionStr) {
        return Integer.parseInt(versionStr);
    }
}
