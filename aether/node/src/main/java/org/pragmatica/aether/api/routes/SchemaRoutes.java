// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.aether.api.routes.SchemaRouteError.InvalidVersionParameter.invalidVersionParameter;
import static org.pragmatica.aether.api.routes.SchemaRouteError.SchemaNotFailed.schemaNotFailed;
import static org.pragmatica.aether.api.routes.SchemaRouteError.SchemaRecordNotFound.schemaRecordNotFound;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.lang.Result.success;


public final class SchemaRoutes implements RouteSource {
    /// Query parameter names, reused as the `parameterName` carried by a 400 so the response points
    /// at the same spelling the caller typed.
    private static final String TARGET_VERSION_PARAMETER = "targetVersion";
    private static final String VERSION_PARAMETER = "version";
    /// Applied when the parameter is ABSENT. Present-but-unparseable is a 400 instead — see
    /// [SchemaRouteError.InvalidVersionParameter].
    private static final int DEFAULT_UNDO_VERSION = 0;
    private static final int DEFAULT_BASELINE_VERSION = 1;

    /// Statuses that hold slice activation (#542 / [SchemaRouteError]). Mirrors
    /// `ClusterDeploymentState.Active.BLOCKING_SCHEMA_STATUSES` — duplicated rather than shared
    /// because that field is `private` to a nested FSM state and this route lives in a different
    /// module (`aether-node` vs `aether-deployment`); widening its visibility across a module
    /// boundary for one three-value set was judged a larger change than repeating it here (#760).
    private static final Set<SchemaStatus> BLOCKING_STATUSES = Set.of(SchemaStatus.PENDING,
                                                                      SchemaStatus.MIGRATING,
                                                                      SchemaStatus.FAILED);

    private final Supplier<ManageableNode> nodeSupplier;

    private SchemaRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static SchemaRoutes schemaRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new SchemaRoutes(nodeSupplier);
    }

    /// `heldSlices` names every slice this record currently blocks (#760) — visible on the
    /// management API without reaching for DEBUG logs. Empty whenever the status is not blocking,
    /// even if some slice happens to share the owning blueprint.
    record SchemaStatusResponse(String datasource,
                                int currentVersion,
                                String lastMigration,
                                String status,
                                String owningBlueprint,
                                List<String> heldSlices) {
        static SchemaStatusResponse schemaStatusResponse(SchemaVersionValue v, List<String> heldSlices) {
            return new SchemaStatusResponse(v.datasourceName(),
                                            v.currentVersion(),
                                            v.lastMigration(),
                                            v.status().name(),
                                            v.owningBlueprint().asString(),
                                            heldSlices);
        }
    }

    record SchemaStatusListResponse(List<SchemaStatusResponse> datasources) {}

    record SchemaMigrateResponse(boolean success, String message) {
        static SchemaMigrateResponse schemaMigrateResponse(boolean success, String message) {
            return new SchemaMigrateResponse(success, message);
        }
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<SchemaStatusListResponse> route(ManagementRoute.SCHEMA_STATUS_ALL).toJson(this::allSchemaStatuses),
                         ManagementRoutes.<SchemaStatusResponse> route(ManagementRoute.SCHEMA_STATUS_ONE)
                                         .withPath(aString())
                                         .to(this::singleSchemaStatus)
                                         .asJson(),
                         ManagementRoutes.<SchemaStatusResponse> route(ManagementRoute.SCHEMA_HISTORY)
                                         .withPath(aString())
                                         .to(this::schemaHistory)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse> route(ManagementRoute.SCHEMA_MIGRATE)
                                         .withPath(aString())
                                         .to(this::triggerMigration)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse> route(ManagementRoute.SCHEMA_UNDO)
                                         .withPath(aString())
                                         .withQuery(QueryParameter.aString("targetVersion"))
                                         .to(this::undoMigration)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse> route(ManagementRoute.SCHEMA_BASELINE)
                                         .withPath(aString())
                                         .withQuery(QueryParameter.aString("version"))
                                         .to(this::baselineDatasource)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse> route(ManagementRoute.SCHEMA_RETRY)
                                         .withPath(aString())
                                         .to(this::retryMigration)
                                         .asJson());
    }

    private SchemaStatusListResponse allSchemaStatuses() {
        var entries = new ArrayList<SchemaStatusResponse>();

        nodeSupplier.get()
                    .kvStore()
                    .forEach(SchemaVersionKey.class,
                             SchemaVersionValue.class,
                             (_, value) -> entries.add(SchemaStatusResponse.schemaStatusResponse(value,
                                                                                                 heldSlices(value))));

        return new SchemaStatusListResponse(entries);
    }

    private Promise<SchemaStatusResponse> singleSchemaStatus(String datasource) {
        return lookupSchemaVersion(datasource).map(this::toSchemaStatusResponse);
    }

    private Promise<SchemaStatusResponse> schemaHistory(String datasource) {
        return lookupSchemaVersion(datasource).map(this::toSchemaStatusResponse);
    }

    private SchemaStatusResponse toSchemaStatusResponse(SchemaVersionValue value) {
        return SchemaStatusResponse.schemaStatusResponse(value, heldSlices(value));
    }

    /// Scans `SliceTargetKey`/`SliceTargetValue` for slices owned (base-stripped, matching
    /// `ClusterDeploymentState`'s ownership rule) by this record's blueprint — empty for any
    /// non-blocking status, since a COMPLETED record holds nothing regardless of ownership.
    private List<String> heldSlices(SchemaVersionValue schema) {
        if (!BLOCKING_STATUSES.contains(schema.status())) {
            return List.of();
        }

        var held = new ArrayList<String>();

        nodeSupplier.get()
                    .kvStore()
                    .forEach(SliceTargetKey.class,
                             SliceTargetValue.class,
                             (key, value) -> collectIfHeldBySchema(schema, key, value, held));

        return held;
    }

    private static void collectIfHeldBySchema(SchemaVersionValue schema,
                                              SliceTargetKey key,
                                              SliceTargetValue value,
                                              List<String> held) {
        if (value.owningBlueprint().map(owner -> owner.base()
                                                      .equals(schema.owningBlueprint().base())).or(false)) {
            held.add(key.artifactBase().asString());
        }
    }

    private Promise<SchemaMigrateResponse> triggerMigration(String datasource) {
        return lookupSchemaVersion(datasource).flatMap(current -> writeMigratingStatus(current, datasource));
    }

    private Promise<SchemaMigrateResponse> undoMigration(String datasource, Option<String> targetVersionOpt) {
        return versionParameter(targetVersionOpt, TARGET_VERSION_PARAMETER, DEFAULT_UNDO_VERSION).async()
                               .flatMap(targetVersion -> undoToVersion(datasource, targetVersion));
    }

    private Promise<SchemaMigrateResponse> undoToVersion(String datasource, int targetVersion) {
        return lookupSchemaVersion(datasource).flatMap(current -> writeUndoStatus(current, datasource, targetVersion));
    }

    /// Package-visible so the baseline contract can be exercised directly (matching
    /// `ClusterTopologyRoutes.assembleOwnershipResponse`): baselining must inherit the existing
    /// record's artifact coordinates and owning blueprint, and must fail rather than fabricate an
    /// unowned record for a datasource that has none.
    Promise<SchemaMigrateResponse> baselineDatasource(String datasource, Option<String> versionOpt) {
        return versionParameter(versionOpt, VERSION_PARAMETER, DEFAULT_BASELINE_VERSION).async()
                               .flatMap(version -> baselineAtVersion(datasource, version));
    }

    private Promise<SchemaMigrateResponse> baselineAtVersion(String datasource, int version) {
        return lookupSchemaVersion(datasource).flatMap(current -> writeBaselineStatus(current, datasource, version));
    }

    /// An absent parameter takes the documented default; a present one must parse, so a typo is a
    /// 400 naming it rather than a `NumberFormatException` unwinding into Netty's catch-all 500.
    private static Result<Integer> versionParameter(Option<String> raw, String parameterName, int fallback) {
        return raw.map(value -> parseVersion(parameterName, value))
                  .or(success(fallback));
    }

    private static Result<Integer> parseVersion(String parameterName, String value) {
        return Number.parseInt(value).mapError(_ -> invalidVersionParameter(parameterName, value));
    }

    private Promise<SchemaVersionValue> lookupSchemaVersion(String datasource) {
        var key = SchemaVersionKey.schemaVersionKey(datasource);

        return nodeSupplier.get()
                           .kvStore()
                           .get(key)
                           .filter(v -> v instanceof SchemaVersionValue)
                           .map(v -> (SchemaVersionValue) v)
                           .async(schemaRecordNotFound(datasource));
    }

    private Promise<SchemaMigrateResponse> writeMigratingStatus(SchemaVersionValue current, String datasource) {
        var updated = SchemaVersionValue.schemaVersionValue(datasource,
                                                            current.currentVersion(),
                                                            current.lastMigration(),
                                                            SchemaStatus.MIGRATING,
                                                            current.artifactCoords(),
                                                            current.owningBlueprint());

        return applySchemaUpdate(datasource, updated).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                           "Migration triggered for " + datasource));
    }

    private Promise<SchemaMigrateResponse> writeUndoStatus(SchemaVersionValue current,
                                                           String datasource,
                                                           int targetVersion) {
        var updated = SchemaVersionValue.schemaVersionValue(datasource,
                                                            targetVersion,
                                                            current.lastMigration(),
                                                            SchemaStatus.PENDING,
                                                            current.artifactCoords(),
                                                            current.owningBlueprint());

        return applySchemaUpdate(datasource, updated).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                           "Undo to version " + targetVersion
                                                                                                          + " initiated for " + datasource));
    }

    /// Baselining rewrites only the version, the marker migration name and the status. The artifact
    /// coordinates and the owning blueprint are carried over from the existing record: dropping the
    /// coordinates breaks `SchemaOrchestratorService.resolveAndParseMigrations` on any later
    /// migrate, and dropping the owner detaches the record from the blueprint whose activation gate
    /// consults it. A datasource with no record cannot be baselined — there is no owner to inherit,
    /// and inventing an unowned record would produce exactly the orphan the required-ownership
    /// component exists to make unrepresentable.
    private Promise<SchemaMigrateResponse> writeBaselineStatus(SchemaVersionValue current,
                                                               String datasource,
                                                               int version) {
        var baselined = SchemaVersionValue.schemaVersionValue(datasource,
                                                              version,
                                                              "V" + String.format("%03d", version) + "__baseline",
                                                              SchemaStatus.COMPLETED,
                                                              current.artifactCoords(),
                                                              current.owningBlueprint());

        return applySchemaUpdate(datasource, baselined).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                             "Baselined " + datasource
                                                                                                            + " at version " + version));
    }

    private Promise<List<Long>> applySchemaUpdate(String datasource, SchemaVersionValue value) {
        var key = SchemaVersionKey.schemaVersionKey(datasource);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);

        return nodeSupplier.get()
                           .apply(List.of(command));
    }

    private Promise<SchemaMigrateResponse> retryMigration(String datasource) {
        return lookupSchemaVersion(datasource).flatMap(current -> writeRetryStatus(current, datasource))
                                  .onSuccess(_ -> AuditLog.schemaManualRetry(datasource));
    }

    /// Accepts FAILED (the original contract: a permanently failed migration) and PENDING (#724: a
    /// migration that never ran — the operator has no other lever to re-trigger dispatch without a
    /// redeploy). MIGRATING and COMPLETED remain refused: MIGRATING already has a runner in flight,
    /// and re-arming a COMPLETED record would replay a migration nothing marked failed.
    private Promise<SchemaMigrateResponse> writeRetryStatus(SchemaVersionValue current, String datasource) {
        if (current.status() != SchemaStatus.FAILED && current.status() != SchemaStatus.PENDING) {
            return schemaNotFailed(datasource, current.status()).promise();
        }

        var updated = SchemaVersionValue.schemaVersionValue(datasource,
                                                            current.currentVersion(),
                                                            current.lastMigration(),
                                                            SchemaStatus.PENDING,
                                                            current.artifactCoords(),
                                                            current.owningBlueprint(),
                                                            0);

        return applySchemaUpdate(datasource, updated).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                           "Retry initiated for " + datasource));
    }
}
