// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import org.pragmatica.lang.Cause;


/// Execution failures; transport status and deployment recovery policy belong to runtime adapters.
public sealed interface MigrationError extends Cause {
    record ChecksumMismatch(String datasource, int version, long expected, long actual) implements MigrationError {
        public static ChecksumMismatch checksumMismatch(String datasource, int version, long expected, long actual) {
            return new ChecksumMismatch(datasource, version, expected, actual);
        }

        @Override
        public String message() {
            return "Checksum mismatch for datasource '" + datasource
                 + "' at version " + version
                 + ": expected " + expected
                 + " but found " + actual;
        }
    }

    record BaselineConflict(String datasource, int existingVersion) implements MigrationError {
        public static BaselineConflict baselineConflict(String datasource, int existingVersion) {
            return new BaselineConflict(datasource, existingVersion);
        }

        @Override
        public String message() {
            return "Baseline conflict for datasource '" + datasource
                 + "': versioned migrations already applied up to version " + existingVersion;
        }
    }

    record UndoNotAvailable(String datasource, int version) implements MigrationError {
        public static UndoNotAvailable undoNotAvailable(String datasource, int version) {
            return new UndoNotAvailable(datasource, version);
        }

        @Override
        public String message() {
            return "Undo script not available for datasource '" + datasource + "' at version " + version;
        }
    }

    record InvalidMigrationFormat(String filename, String detail) implements MigrationError {
        public static InvalidMigrationFormat invalidMigrationFormat(String filename, String detail) {
            return new InvalidMigrationFormat(filename, detail);
        }

        @Override
        public String message() {
            return "Invalid migration filename '" + filename + "': " + detail;
        }
    }

    record PhysicalDatasourceOwnershipConflict(String datasource, String currentOwnerBase, String rejectedBase) implements MigrationError {
        public static PhysicalDatasourceOwnershipConflict physicalDatasourceOwnershipConflict(String datasource,
                                                                                              String currentOwnerBase,
                                                                                              String rejectedBase) {
            return new PhysicalDatasourceOwnershipConflict(datasource, currentOwnerBase, rejectedBase);
        }

        @Override
        public String message() {
            return "Blueprint '" + rejectedBase
                 + "' rejected — the physical database behind datasource '" + datasource
                 + "' is already migrated by blueprint '" + currentOwnerBase
                 + "' (its 'aether_schema_owner' claim). No migration was applied."
                 + " To recover: point this blueprint's '" + datasource
                 + "' config section at a different physical database, or consolidate both blueprints'"
                 + " migrations into the single blueprint '" + currentOwnerBase
                 + "' that owns it.";
        }
    }

    /// FDB-2a (#2591): another database session holds this physical database's migration fence, so this attempt ran
    /// nothing and read no history. The fence is a session-scoped database lock: it ends when the holder's session
    /// does. `holder` names that session as the database reports it.
    record MigrationFencedInDatabase(String datasource, String holder) implements MigrationError {
        public static MigrationFencedInDatabase migrationFencedInDatabase(String datasource, String holder) {
            return new MigrationFencedInDatabase(datasource, holder);
        }

        @Override
        public String message() {
            return "Migration of datasource '" + datasource
                 + "' is fenced: another database session holds its migration fence (" + holder
                 + "). Nothing was run. The fence ends when that session ends; to end it now, terminate that session"
                 + " (PostgreSQL: SELECT pg_terminate_backend(pid); MySQL: KILL id).";
        }
    }

    /// FDB-2a (#2591): the migration fence cannot be held on this database or topology, so the migration is refused
    /// before any statement runs. Never an unfenced fallback.
    record FenceUnavailable(String datasource, String reason) implements MigrationError {
        public static FenceUnavailable fenceUnavailable(String datasource, String reason) {
            return new FenceUnavailable(datasource, reason);
        }

        @Override
        public String message() {
            return "Migration of datasource '" + datasource
                 + "' refused: the database migration fence cannot be held (" + reason
                 + "). No statement was run.";
        }
    }

    /// FDB-2a (#2591, F8): a `CREATE INDEX CONCURRENTLY` left an INVALID index behind (cancelled by the statement
    /// timeout, or its session died). `IF NOT EXISTS` passes over an invalid index silently, so the runner checks
    /// after every such statement and stops here instead of recording success.
    record InvalidIndexLeftBehind(String filename, String index) implements MigrationError {
        public static InvalidIndexLeftBehind invalidIndexLeftBehind(String filename, String index) {
            return new InvalidIndexLeftBehind(filename, index);
        }

        @Override
        public String message() {
            return "Migration '" + filename
                 + "' stopped: index '" + index
                 + "' is INVALID (a CREATE INDEX CONCURRENTLY was cancelled or its session died). To recover: DROP INDEX CONCURRENTLY " + index
                 + "; then retry the migration.";
        }
    }
}
