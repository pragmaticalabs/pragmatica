// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.aether.api.routes.SchemaRouteError.InvalidVersionParameter.invalidVersionParameter;
import static org.pragmatica.aether.api.routes.SchemaRouteError.SchemaAlreadyPending.schemaAlreadyPending;
import static org.pragmatica.aether.api.routes.SchemaRouteError.SchemaAlreadyServing.schemaAlreadyServing;
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

    /// Scans `SliceNodeKey`/`SliceNodeValue` — the LIVE per-node runtime state, not the
    /// ownership-only `SliceTargetKey`/`SliceTargetValue` this used to scan — and delegates to the
    /// SAME predicate ([ClusterDeploymentState#blocksSliceActivation]) the activation gate itself
    /// uses (#760 review BLOCKING 1). The prior version matched ownership alone with no state
    /// check at all, so a slice already ACTIVE (which passed the gate and has no transition path
    /// back through it) was reported as held whenever its record was re-armed to MIGRATING —
    /// exactly the false positive this rewrite eliminates. Deduped by artifact base: several node
    /// instances of the same artifact each contribute a `SliceNodeKey`, and the reported shape is
    /// one entry per held artifact, not one per instance.
    private List<String> heldSlices(SchemaVersionValue schema) {
        if (!ClusterDeploymentState.BLOCKING_SCHEMA_STATUSES.contains(schema.status())) {
            return List.of();
        }

        var held = new LinkedHashSet<String>();
        var kvStore = nodeSupplier.get().kvStore();

        kvStore.forEach(SliceNodeKey.class,
                        SliceNodeValue.class,
                        (key, value) -> collectIfHeldBySchema(schema, key, value, kvStore, held));

        return List.copyOf(held);
    }

    private void collectIfHeldBySchema(SchemaVersionValue schema,
                                       SliceNodeKey key,
                                       SliceNodeValue value,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       Set<String> held) {
        if (ClusterDeploymentState.blocksSliceActivation(value.state(), sliceOwner(key.artifact()), schema, kvStore)) {
            held.add(key.artifact().base().asString());
        }
    }

    /// `SliceNodeValue` carries no ownership — that lives on `SliceTargetKey`/`SliceTargetValue`
    /// (the same desired/ownership record `ClusterDeploymentState.Active.blueprints` mirrors), so
    /// live state and ownership are joined here per slice, one KV read per artifact.
    private Option<BlueprintId> sliceOwner(Artifact artifact) {
        var targetKey = SliceTargetKey.sliceTargetKey(artifact.base());

        return nodeSupplier.get()
                           .kvStore()
                           .get(targetKey)
                           .filter(v -> v instanceof SliceTargetValue)
                           .map(v -> (SliceTargetValue) v)
                           .flatMap(SliceTargetValue::owningBlueprint);
    }

    /// #760 review BLOCKING 1: `/migrate` writing MIGRATING has no orchestrator effect by itself —
    /// only a PENDING record's Put drives `SchemaOrchestratorService.migrateIfNeeded`
    /// (`ClusterDeploymentState.processSchemaVersionPut`'s MIGRATING branch only logs at DEBUG).
    /// Re-arming a COMPLETED record whose owning blueprint has live ACTIVE slices therefore has no
    /// functional benefit and one real hazard: MIGRATING stays a blocking status, so the next slice
    /// instance to reach LOADED (a scale-up, a rolling redeploy, a rejoining node) is held there
    /// with no automatic path back — nothing but another manual write ever resolves a directly-set
    /// MIGRATING record. Refused (409) rather than allowed, since the only way out of that
    /// self-inflicted hold is an operator noticing a stuck LOADED slice and clearing the record
    /// they themselves re-armed. A COMPLETED record with zero live ACTIVE slices (nothing yet
    /// deployed, or a prior deploy never reached ACTIVE) has nothing to protect and is unaffected.
    ///
    /// #760/#724 review round 2 item l: a PENDING record hit the same hazard through the `else`
    /// fallthrough — the guard above special-cased only COMPLETED, so `/migrate` on PENDING silently
    /// rewrote it to MIGRATING with no dispatch effect and the same missing clearing path. Refused
    /// (409, [SchemaRouteError.SchemaAlreadyPending]) alongside COMPLETED-with-active-slices; a
    /// PENDING record already dispatches on its own and gains nothing from being re-armed.
    private Promise<SchemaMigrateResponse> triggerMigration(String datasource) {
        return lookupSchemaVersion(datasource).flatMap(current -> guardReactivation(current, datasource));
    }

    private Promise<SchemaMigrateResponse> guardReactivation(SchemaVersionValue current, String datasource) {
        return switch (current.status()) {
            case COMPLETED -> refuseIfActiveSlicesPresent(current, datasource);
            case PENDING -> schemaAlreadyPending(datasource).promise();
            default -> writeMigratingStatus(current, datasource);
        };
    }

    private Promise<SchemaMigrateResponse> refuseIfActiveSlicesPresent(SchemaVersionValue current, String datasource) {
        var activeCount = activeSliceCount(current.owningBlueprint());

        return activeCount == 0
               ? writeMigratingStatus(current, datasource)
               : schemaAlreadyServing(datasource, activeCount).promise();
    }

    /// #760 review round 2 item b: a slice mid-activation (ACTIVATING/ROUTING) is already occupying
    /// the datasource it will serve from once it reaches ACTIVE — refusing to count it would let a
    /// re-arm race a slice that is seconds from going live. Counting ACTIVATING/ROUTING/ACTIVE alike
    /// is deliberately over-inclusive rather than under: a false-positive refusal costs the operator a
    /// retry, a false-negative would strand the very migration this guard exists to protect.
    ///
    /// This count is a snapshot read against a live KV store with no lock spanning it and the
    /// subsequent status Put — a slice can transition into a serving state, or a brand-new one can be
    /// targeted, in the window between this read and `writeMigratingStatus`'s write. That window is a
    /// known, accepted race: closing it would need the count and the Put to share a single
    /// consensus-replicated transaction, which this guard does not attempt. Documented here rather
    /// than fixed, since the guard's purpose is to catch the common case (a schema re-armed onto an
    /// already-serving version), not to provide a linearizable barrier.
    private int activeSliceCount(BlueprintId owner) {
        var count = new AtomicInteger();

        nodeSupplier.get()
                    .kvStore()
                    .forEach(SliceNodeKey.class,
                             SliceNodeValue.class,
                             (key, value) -> countIfActiveAndOwnedBy(owner, key, value, count));

        return count.get();
    }

    private static final Set<SliceState> SERVING_STATES = Set.of(SliceState.ACTIVATING,
                                                                 SliceState.ROUTING,
                                                                 SliceState.ACTIVE);

    private void countIfActiveAndOwnedBy(BlueprintId owner,
                                         SliceNodeKey key,
                                         SliceNodeValue value,
                                         AtomicInteger count) {
        if (SERVING_STATES.contains(value.state()) && sliceOwner(key.artifact()).map(actualOwner -> actualOwner.base()
                                                                                                               .equals(owner.base()))
                                                                .or(false)) {
            count.incrementAndGet();
        }
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
