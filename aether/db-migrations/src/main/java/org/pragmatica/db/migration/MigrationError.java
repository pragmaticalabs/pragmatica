// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
}
