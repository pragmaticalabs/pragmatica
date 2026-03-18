package org.pragmatica.aether.deployment.schema;

import org.pragmatica.lang.Cause;

/// Error types for schema migration failures.
public sealed interface SchemaError extends Cause {
    record ChecksumMismatch(String datasource, int version, long expected, long actual) implements SchemaError {
        public static ChecksumMismatch checksumMismatch(String datasource, int version, long expected, long actual) {
            return new ChecksumMismatch(datasource, version, expected, actual);
        }

        @Override
        public String message() {
            return "Checksum mismatch for datasource '" + datasource + "' at version " + version + ": expected " + expected
                   + " but found " + actual;
        }
    }

    record MigrationFailed(String datasource, int version, String detail) implements SchemaError {
        public static MigrationFailed migrationFailed(String datasource, int version, String detail) {
            return new MigrationFailed(datasource, version, detail);
        }

        @Override
        public String message() {
            return "Migration failed for datasource '" + datasource + "' at version " + version + ": " + detail;
        }
    }

    record DatasourceUnreachable(String datasource, String detail) implements SchemaError {
        public static DatasourceUnreachable datasourceUnreachable(String datasource, String detail) {
            return new DatasourceUnreachable(datasource, detail);
        }

        @Override
        public String message() {
            return "Datasource unreachable: '" + datasource + "' — " + detail;
        }
    }

    record LockAcquisitionFailed(String datasource) implements SchemaError {
        public static LockAcquisitionFailed lockAcquisitionFailed(String datasource) {
            return new LockAcquisitionFailed(datasource);
        }

        @Override
        public String message() {
            return "Failed to acquire migration lock for datasource '" + datasource + "'";
        }
    }

    record BaselineConflict(String datasource, int existingVersion) implements SchemaError {
        public static BaselineConflict baselineConflict(String datasource, int existingVersion) {
            return new BaselineConflict(datasource, existingVersion);
        }

        @Override
        public String message() {
            return "Baseline conflict for datasource '" + datasource
                   + "': versioned migrations already applied up to version " + existingVersion;
        }
    }

    record UndoNotAvailable(String datasource, int version) implements SchemaError {
        public static UndoNotAvailable undoNotAvailable(String datasource, int version) {
            return new UndoNotAvailable(datasource, version);
        }

        @Override
        public String message() {
            return "Undo script not available for datasource '" + datasource + "' at version " + version;
        }
    }

    record InvalidMigrationFormat(String filename, String detail) implements SchemaError {
        public static InvalidMigrationFormat invalidMigrationFormat(String filename, String detail) {
            return new InvalidMigrationFormat(filename, detail);
        }

        @Override
        public String message() {
            return "Invalid migration filename '" + filename + "': " + detail;
        }
    }
}
