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

    /// #543/#832 review round 1 SHOULD-FIX 4: surfaces as HTTP 422 — `classifyFailure` (below)
    /// already marks this PERMANENT, meaning the SAME migration script fails identically on retry
    /// (a syntax error, a constraint violation, content the artifact's author controls), not an
    /// infrastructure fault a retry could ride out. Recovery: publish a corrected blueprint revision.
    record MigrationFailed(String datasource, int version, String detail) implements SchemaError, HttpStatusAware {
        public static MigrationFailed migrationFailed(String datasource, int version, String detail) {
            return new MigrationFailed(datasource, version, detail);
        }

        @Override
        public String message() {
            return "Migration failed for datasource '" + datasource + "' at version " + version + ": " + detail;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /// #543/#832 review round 1 SHOULD-FIX 4: surfaces as HTTP 503 — an infrastructure fault named
    /// as such, not a request problem. `classifyFailure` (below) already marks this TRANSIENT: the
    /// same request against the same artifact succeeds once the datasource is reachable again.
    /// Recovery: no action on the request itself — retry once connectivity/the datasource recovers.
    record DatasourceUnreachable(String datasource, String detail) implements SchemaError, HttpStatusAware {
        public static DatasourceUnreachable datasourceUnreachable(String datasource, String detail) {
            return new DatasourceUnreachable(datasource, detail);
        }

        @Override
        public String message() {
            return "Datasource unreachable: '" + datasource + "' — " + detail;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /// #543/#832 review round 1 SHOULD-FIX 4: surfaces as HTTP 409 — a conflict with an in-flight
    /// operation (a concurrent attempt already holds [SchemaOrchestratorServiceInstance]'s in-process
    /// fence, or the cluster-wide KV migration lock), not a malformed request. Previously constructed
    /// ONLY conceptually — the fence's own short-circuit built an ad hoc, un-typed `Cause` instead of
    /// this variant, so a lock conflict answered 500 despite this exact type already existing (and
    /// already being classified `TRANSIENT` by `classifyFailure` below, which was consequently dead
    /// code on that path). Recovery: no operator action — the fence/lock releases when the in-flight
    /// attempt finishes; retry the request once it does.
    record LockAcquisitionFailed(String datasource) implements SchemaError, HttpStatusAware {
        public static LockAcquisitionFailed lockAcquisitionFailed(String datasource) {
            return new LockAcquisitionFailed(datasource);
        }

        @Override
        public String message() {
            return "Failed to acquire migration lock for datasource '" + datasource + "'";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
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
    ///
    /// #543/#832 review round 1 SHOULD-FIX 4: surfaces as HTTP 422 — `classifyFailure` (below)
    /// already marks this PERMANENT; the fix is republishing a blueprint carrying the coordinates,
    /// not retrying the same request. Recovery: publish a blueprint revision recording the schema
    /// version's artifact coordinates, or re-run baseline against the datasource's actual state.
    record MigrationArtifactUnresolved(String datasource, int declaredVersion, String declaredMigration) implements SchemaError, HttpStatusAware {
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

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /// The blueprint artifact resolved from the recorded coordinates contains no migration scripts
    /// for a datasource that declared them, meaning the resolved artifact is not the one the deploy
    /// declared against. Permanent: a retry resolves the same coordinates.
    ///
    /// #543/#832 review round 1 SHOULD-FIX 4: surfaces as HTTP 422 — `classifyFailure` (below)
    /// already marks this PERMANENT; the resolved artifact itself, not cluster state, is what's
    /// wrong. Recovery: publish a blueprint revision whose artifact actually carries the declared
    /// migration scripts.
    record MigrationSetUnavailable(String datasource, String artifactCoords, int declaredVersion) implements SchemaError, HttpStatusAware {
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

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /// #543/#832 review round 1 SHOULD-FIX 4: surfaces as HTTP 422 — a malformed migration script
    /// filename is a content problem in the published artifact, not a cluster-state conflict or an
    /// infrastructure fault; the same artifact fails identically on retry. Recovery: publish a
    /// blueprint revision with a correctly named script (`V<n>__*.sql` / `U<n>__*.sql`).
    record InvalidMigrationFormat(String filename, String detail) implements SchemaError, HttpStatusAware {
        public static InvalidMigrationFormat invalidMigrationFormat(String filename, String detail) {
            return new InvalidMigrationFormat(filename, detail);
        }

        @Override
        public String message() {
            return "Invalid migration filename '" + filename + "': " + detail;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
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

    /// #543/#832 review round 1 BLOCKING 3: `SchemaOrchestratorService.provisionAndRun` bounds the
    /// undo manager call with `SchemaPolicy#migrationTimeout` (the same bound migrate's own
    /// `resolveAndParseMigrations` uses) — this is what a caller sees when that bound is hit. 504,
    /// not 500: the request was accepted and dispatched, the manager simply never reported completion
    /// within the bound. Recovery: the datasource's schema version record is left `FAILED` (never
    /// silently unchanged, never `COMPLETED`) — a fresh undo call, `aether schema retry`, or a
    /// redeploy can re-attempt it once whatever wedged the connector or the down-script is cleared.
    record UndoTimedOut(String datasource, int targetVersion) implements SchemaError, HttpStatusAware {
        public static UndoTimedOut undoTimedOut(String datasource, int targetVersion) {
            return new UndoTimedOut(datasource, targetVersion);
        }

        @Override
        public String message() {
            return "Undo timed out for datasource '" + datasource + "' targeting version " + targetVersion;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
    }

    /// See [UndoTimedOut] — the baseline analogue, same bound, same recovery path.
    record BaselineTimedOut(String datasource, int version) implements SchemaError, HttpStatusAware {
        public static BaselineTimedOut baselineTimedOut(String datasource, int version) {
            return new BaselineTimedOut(datasource, version);
        }

        @Override
        public String message() {
            return "Baseline timed out for datasource '" + datasource + "' at version " + version;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
    }
}
