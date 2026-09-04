// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


public sealed interface SchemaError extends Cause {
    /// #543 condition 4: surfaces as HTTP 422 — the request (which script version to migrate)
    /// is well-formed and the datasource exists; what's wrong is the content already applied to it,
    /// not a conflict with current cluster/database state (that's [BaselineConflict]'s 409) and not
    /// a malformed request (that's a 400 at the route layer).
    record ChecksumMismatch(String datasource, int version, long expected, long actual) implements SchemaError, HttpStatusAware {
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

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
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

    /// #543 condition 4: surfaces as HTTP 409 on `/baseline` — the request is well-formed and the
    /// datasource exists; the conflict is with existing database state (versioned migrations already
    /// applied), exactly the [DatasourceOwnershipConflict] precedent below. Recovery: baseline is not
    /// the right operation for a datasource with applied history — undo to the target version instead,
    /// or baseline at (or above) `existingVersion`.
    record BaselineConflict(String datasource, int existingVersion) implements SchemaError, HttpStatusAware {
        public static BaselineConflict baselineConflict(String datasource, int existingVersion) {
            return new BaselineConflict(datasource, existingVersion);
        }

        @Override
        public String message() {
            return "Baseline conflict for datasource '" + datasource
                 + "': versioned migrations already applied up to version " + existingVersion;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// #543 condition 4: surfaces as HTTP 422 — the requested target version exists in the applied
    /// history but the artifact carries no matching `U`-prefixed undo script for it, so the content
    /// needed to fulfill the request is missing. Not a state conflict (409): the datasource's current
    /// state is exactly what it should be, and re-issuing the same request against different cluster
    /// state would not change the outcome — only publishing an artifact with the undo script would.
    /// Recovery: publish a blueprint revision carrying the missing `U<version>__*.sql` script, or
    /// choose a target version this artifact can actually undo to.
    record UndoNotAvailable(String datasource, int version) implements SchemaError, HttpStatusAware {
        public static UndoNotAvailable undoNotAvailable(String datasource, int version) {
            return new UndoNotAvailable(datasource, version);
        }

        @Override
        public String message() {
            return "Undo script not available for datasource '" + datasource + "' at version " + version;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /// The schema version record declares a migration set but carries no blueprint artifact
    /// coordinates, so the declared scripts cannot be located. Permanent: nothing about a retry
    /// changes the recorded coordinates.
    record MigrationArtifactUnresolved(String datasource, int declaredVersion, String declaredMigration) implements SchemaError {
        public static MigrationArtifactUnresolved migrationArtifactUnresolved(String datasource,
                                                                              int declaredVersion,
                                                                              String declaredMigration) {
            return new MigrationArtifactUnresolved(datasource, declaredVersion, declaredMigration);
        }

        @Override
        public String message() {
            return "Datasource '" + datasource
                 + "' declares schema migrations up to version " + declaredVersion
                 + " (last: '" + declaredMigration
                 + "') but its schema version record carries no blueprint artifact coordinates"
                 + " — the declared migrations were NOT applied";
        }
    }

    /// The blueprint artifact resolved from the recorded coordinates contains no migration scripts
    /// for a datasource that declared them, meaning the resolved artifact is not the one the deploy
    /// declared against. Permanent: a retry resolves the same coordinates.
    record MigrationSetUnavailable(String datasource, String artifactCoords, int declaredVersion) implements SchemaError {
        public static MigrationSetUnavailable migrationSetUnavailable(String datasource,
                                                                      String artifactCoords,
                                                                      int declaredVersion) {
            return new MigrationSetUnavailable(datasource, artifactCoords, declaredVersion);
        }

        @Override
        public String message() {
            return "Datasource '" + datasource
                 + "' declares schema migrations up to version " + declaredVersion
                 + " but blueprint artifact '" + artifactCoords
                 + "' contains no migration scripts for it"
                 + " — the declared migrations were NOT applied";
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

    /// Datasource names are cluster-global (`BlueprintArtifactParser` derives them from the
    /// migration script path, so every blueprint using the default `schema/V001__*.sql` layout
    /// claims `"database"` and resolves it against the same node-global config section). Two
    /// blueprints migrating one physical database interleave unrelated version sequences, so the
    /// publish that would become the second migrator is rejected outright. Sharing a datasource
    /// for reads and writes stays legal — only duplicate *migration ownership* is refused.
    ///
    /// Surfaces as HTTP 409 on the publish endpoint: the request is well-formed and the conflict is
    /// with existing cluster state, not with the payload.
    record DatasourceOwnershipConflict(String datasource, BlueprintId currentOwner, BlueprintId rejected) implements SchemaError, HttpStatusAware {
        public static DatasourceOwnershipConflict datasourceOwnershipConflict(String datasource,
                                                                              BlueprintId currentOwner,
                                                                              BlueprintId rejected) {
            return new DatasourceOwnershipConflict(datasource, currentOwner, rejected);
        }

        @Override
        public String message() {
            return "Blueprint '" + rejected.asString()
                 + "' rejected — datasource '" + datasource
                 + "' is already migrated by blueprint '" + currentOwner.asString()
                 + "'. Declare the migrations in one blueprint only, or give this blueprint its own"
                 + " datasource section.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// Migration-time single-migrator gate on the PHYSICAL database, the companion to
    /// [DatasourceOwnershipConflict]. The publish-time check compares datasource NAMES, so two
    /// distinct node config sections (`[database.a]`, `[database.b]`) resolving to the SAME physical
    /// database slip past it: each name is unclaimed. Since `aether_schema_history` is unqualified —
    /// one per physical database — both blueprints would then interleave unrelated version sequences
    /// in a single shared history. The claim is therefore recorded IN the database being migrated
    /// ([SchemaHistoryEvolution#OWNER_TABLE]) and read before any migration is applied, so a refused
    /// claim writes nothing at all.
    ///
    /// Compared on `ArtifactBase` (`group:artifact`, version stripped), exactly like the publish-time
    /// gate: republishing `my-app:1.0.1` over rows written by `my-app:1.0.0` is the same owner
    /// advancing its own schema, not a conflict.
    ///
    /// Surfaces as HTTP 409: the request is well-formed and the conflict is with existing database
    /// state, not with the payload.
    record PhysicalDatasourceOwnershipConflict(String datasource, String currentOwnerBase, String rejectedBase) implements SchemaError, HttpStatusAware {
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

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }
}
