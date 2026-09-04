// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.AuditLog;
import org.pragmatica.aether.deployment.schema.SchemaEvent.ManualRetryRequested;
import org.pragmatica.aether.deployment.schema.SchemaEvent.MigrationCompleted;
import org.pragmatica.aether.deployment.schema.SchemaEvent.MigrationFailed;
import org.pragmatica.aether.deployment.schema.SchemaEvent.MigrationRetrying;
import org.pragmatica.aether.deployment.schema.SchemaEvent.MigrationStarted;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.BlueprintArtifact;
import org.pragmatica.aether.slice.blueprint.BlueprintArtifactParser;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public interface SchemaOrchestratorService {
    Promise<Unit> migrateIfNeeded(String datasourceName);
    Promise<Unit> undoTo(String datasourceName, int targetVersion);
    Promise<Unit> baseline(String datasourceName, int version);
    long LOCK_TTL_MS = 5 * 60 * 1000L;
    int MAX_RETRIES = 3;
    long BACKOFF_BASE_MS = 5000;

    static SchemaOrchestratorService schemaOrchestratorService(ClusterNode<KVCommand<AetherKey>> cluster,
                                                               KVStore<AetherKey, AetherValue> kvStore,
                                                               ArtifactStore artifactStore,
                                                               Repository repository,
                                                               AetherSchemaManager schemaManager,
                                                               DatasourceConnectionProvider connectionProvider,
                                                               NodeId self,
                                                               MessageRouter router) {
        return new SchemaOrchestratorServiceInstance(cluster,
                                                     kvStore,
                                                     artifactStore,
                                                     repository,
                                                     schemaManager,
                                                     connectionProvider,
                                                     self,
                                                     router);
    }

    static SchemaOrchestratorService schemaOrchestratorService(ClusterNode<KVCommand<AetherKey>> cluster,
                                                               KVStore<AetherKey, AetherValue> kvStore,
                                                               ArtifactStore artifactStore,
                                                               Repository repository,
                                                               AetherSchemaManager schemaManager,
                                                               DatasourceConnectionProvider connectionProvider,
                                                               NodeId self) {
        return new SchemaOrchestratorServiceInstance(cluster,
                                                     kvStore,
                                                     artifactStore,
                                                     repository,
                                                     schemaManager,
                                                     connectionProvider,
                                                     self,
                                                     Option.none());
    }
}

@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
class SchemaOrchestratorServiceInstance implements SchemaOrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(SchemaOrchestratorServiceInstance.class);

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final ArtifactStore artifactStore;
    private final Repository repository;
    private final AetherSchemaManager schemaManager;
    private final DatasourceConnectionProvider connectionProvider;
    private final NodeId self;
    private final Option<MessageRouter> router;

    SchemaOrchestratorServiceInstance(ClusterNode<KVCommand<AetherKey>> cluster,
                                      KVStore<AetherKey, AetherValue> kvStore,
                                      ArtifactStore artifactStore,
                                      Repository repository,
                                      AetherSchemaManager schemaManager,
                                      DatasourceConnectionProvider connectionProvider,
                                      NodeId self,
                                      MessageRouter router) {
        this(cluster, kvStore, artifactStore, repository, schemaManager, connectionProvider, self, Option.option(router));
    }

    SchemaOrchestratorServiceInstance(ClusterNode<KVCommand<AetherKey>> cluster,
                                      KVStore<AetherKey, AetherValue> kvStore,
                                      ArtifactStore artifactStore,
                                      Repository repository,
                                      AetherSchemaManager schemaManager,
                                      DatasourceConnectionProvider connectionProvider,
                                      NodeId self,
                                      Option<MessageRouter> router) {
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.artifactStore = artifactStore;
        this.repository = repository;
        this.schemaManager = schemaManager;
        this.connectionProvider = connectionProvider;
        this.self = self;
        this.router = router;
    }

    @Override
    public Promise<Unit> migrateIfNeeded(String datasourceName) {
        var versionKey = SchemaVersionKey.schemaVersionKey(datasourceName);

        return kvStore.get(versionKey)
                      .filter(SchemaOrchestratorServiceInstance::isSchemaVersionValue)
                      .map(SchemaVersionValue.class::cast)
                      .filter(v -> v.status() == SchemaStatus.PENDING)
                      .map(value -> executeMigrationFlow(datasourceName, value))
                      .or(Promise.success(unit()));
    }

    /// #543 ("schema undo is unreachable"): this used to just write a bare KV status — PENDING —
    /// and report success, never calling [AetherSchemaManager#undo]. That was actively wrong, not
    /// merely inert: [#migrateIfNeeded]'s own reconciler tick picks up ANY PENDING record and
    /// dispatches [#executeMigrationFlow], which calls `schemaManager.migrate(...)` FORWARD from
    /// the full script list — so an "undone" datasource was migrated straight back past the target
    /// version on the very next tick, silently undoing the undo it claimed to have performed.
    @Override
    public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
        return executeUndoOrBaseline(datasourceName,
                                     targetVersion,
                                     "undo",
                                     (connector, scripts, owner) -> schemaManager.undo(datasourceName,
                                                                                       targetVersion,
                                                                                       scripts,
                                                                                       connector,
                                                                                       self.id(),
                                                                                       owner));
    }

    /// #543: the prior version wrote a fabricated COMPLETED status directly, with a synthetic
    /// `lastMigration` marker built from the caller's raw request parameter — no call to
    /// [AetherSchemaManager#baseline], no real database interaction of any kind. The 409/422
    /// conflicts baseline can genuinely raise ([SchemaError.BaselineConflict],
    /// [SchemaError.ChecksumMismatch]) were unreachable because nothing ever asked the manager.
    @Override
    public Promise<Unit> baseline(String datasourceName, int version) {
        return executeUndoOrBaseline(datasourceName,
                                     version,
                                     "baseline",
                                     (connector, scripts, owner) -> schemaManager.baseline(datasourceName,
                                                                                           version,
                                                                                           scripts,
                                                                                           connector,
                                                                                           self.id(),
                                                                                           owner));
    }

    /// Shared undo/baseline execution. Calls the real manager operation SYNCHRONOUSLY, before any
    /// KV write — the antithesis of "write a status, let a later reconciler tick do the work" that
    /// [#undoTo]'s header describes. The final write is always `SchemaStatus.COMPLETED`, never
    /// `PENDING`: [#migrateIfNeeded]'s own `status == PENDING` filter is what then keeps the
    /// reconciler from ever revisiting this record on its own [mechanism: the reconciler's
    /// PENDING-only guard structurally excludes a COMPLETED record — #543 condition 1, no
    /// additional guard needed beyond writing the correct terminal status].
    ///
    /// Shares [#executeMigrationFlow]'s single-flight fence (`inFlightMigrations`, keyed by
    /// `datasourceName`) so a `/migrate` dispatch and an `/undo` or `/baseline` call against the
    /// SAME datasource can never run concurrently on this node [mechanism: `ConcurrentHashMap
    /// .putIfAbsent` — #543 condition 2's in-process half]. That fence is per-JVM, not
    /// KV-transactional — it does nothing for a second cluster node reading and writing the same
    /// schema row at the same time. The route layer closes that cross-node gap by binding both
    /// operations to the leader ([SchemaRouteError.SchemaNotLeader]) rather than by widening this
    /// fence into a distributed lock.
    private Promise<Unit> executeUndoOrBaseline(String datasourceName,
                                                int requestedVersion,
                                                String operation,
                                                Fn3<Promise<AetherSchemaManager.SchemaResult>, SqlConnector, List<MigrationEntry>, BlueprintId> terminalOp) {
        var versionKey = SchemaVersionKey.schemaVersionKey(datasourceName);

        return kvStore.get(versionKey)
                      .filter(SchemaOrchestratorServiceInstance::isSchemaVersionValue)
                      .map(SchemaVersionValue.class::cast)
                      .map(value -> runUndoOrBaseline(datasourceName, requestedVersion, operation, value, terminalOp))
                      .or(() -> schemaRecordVanished(datasourceName, operation));
    }

    /// The route layer (`SchemaRoutes.undoToVersion`/`baselineAtVersion`) already confirms the
    /// record exists — the same [SchemaRouteError.SchemaRecordNotFound] 404 check `/status` and
    /// `/history` use — before ever dispatching here, so this covers a narrow, disclosed race (the
    /// record deleted between that check and this call) rather than a path any route drives today;
    /// no route deletes a schema version record. A bare [Cause] (500) is deliberate here, unlike
    /// every genuine failure below: this one has no semantic status of its own to carry.
    private static Promise<Unit> schemaRecordVanished(String datasourceName, String operation) {
        return Causes.cause("Schema version record for '" + datasourceName
                           + "' disappeared between the route's existence check and " + operation
                           + " dispatch").promise();
    }

    private Promise<Unit> runUndoOrBaseline(String datasourceName,
                                            int requestedVersion,
                                            String operation,
                                            SchemaVersionValue value,
                                            Fn3<Promise<AetherSchemaManager.SchemaResult>, SqlConnector, List<MigrationEntry>, BlueprintId> terminalOp) {
        var attemptToken = new Object();

        return acquireLock(datasourceName, attemptToken).flatMap(_ -> resolveMigrationScripts(datasourceName, value).flatMap(scripts -> provisionAndRun(datasourceName,
                                                                                                                                                        scripts,
                                                                                                                                                        value.owningBlueprint(),
                                                                                                                                                        terminalOp).mapError(cause -> handleUndoOrBaselineTimeout(datasourceName,
                                                                                                                                                                                                                  requestedVersion,
                                                                                                                                                                                                                  operation,
                                                                                                                                                                                                                  value,
                                                                                                                                                                                                                  cause)))
                                                                                             .flatMap(result -> recordOutcome(datasourceName,
                                                                                                                              operation,
                                                                                                                              value,
                                                                                                                              result))
                                                                                             .flatMap(_ -> releaseLock(datasourceName))
                                                                                             .replaceResult(result -> finalizeAttempt(datasourceName,
                                                                                                                                      attemptToken,
                                                                                                                                      result)));
    }

    /// #543/#832 review round 1 BLOCKING 3: `provisionAndRun`'s manager call had no bound, unlike
    /// migrate's own [#runMigration] (`resolveAndParseMigrations(...).timeout(...)`, same placement
    /// rationale — as close to the low-level `schemaManager.undo`/`.baseline` call as this call site
    /// allows, before any further `.flatMap`/`.mapError` is chained onto it, per `Promise.timeout()`'s
    /// own placement warning). A wedged connection or a runaway down/baseline script previously held
    /// [#inFlightMigrations]'s fence AND the KV migration lock forever: the KV lock's own TTL expires,
    /// but nothing releases the in-process fence, because [#finalizeAttempt] — the only release site —
    /// never runs while the promise it is chained onto never settles.
    private Promise<AetherSchemaManager.SchemaResult> provisionAndRun(String datasourceName,
                                                                      List<MigrationEntry> scripts,
                                                                      BlueprintId owner,
                                                                      Fn3<Promise<AetherSchemaManager.SchemaResult>, SqlConnector, List<MigrationEntry>, BlueprintId> terminalOp) {
        return provisionConnector(datasourceName).flatMap(connector -> terminalOp.apply(connector, scripts, owner)
                                                                                 .timeout(schemaManager.policy()
                                                                                                       .migrationTimeout())
                                                                                 .onResultRun(() -> releaseConnectorSilently(datasourceName)));
    }

    /// Converts a manager-call timeout into a named, `HttpStatusAware` terminal failure. Every OTHER
    /// failure reaching this point (a genuine [SchemaError] the manager itself raised, or a
    /// [#provisionConnector] failure) passes through unchanged — those already carry their own status
    /// and their own KV disposition is the caller's concern, not a timeout-specific one.
    ///
    /// On timeout, [#finalizeAttempt] already releases both the fence and the KV lock
    /// unconditionally once THIS failed promise settles — no additional release code is needed here,
    /// only making the promise settle at all (via [#provisionAndRun]'s new timeout bound) was missing.
    /// What IS missing without this method: nothing else records that the attempt failed, so the
    /// existing KV record would sit at whatever status it held before the call (typically `COMPLETED`
    /// from a PRIOR operation) with no sign this one ever ran or failed. Writing `FAILED` here —
    /// fire-and-forget via `updateStatus`, the same DEPENDENT-vs-INDEPENDENT split
    /// [#handleMigrationFailure] uses for its own status write, since the chain's own failure must not
    /// wait on this write to become observable to the caller — gives the operator a named cause
    /// (`UndoTimedOut`/`BaselineTimedOut`, 504 through the route) instead of a bare 500, and a `FAILED`
    /// record that a retried undo/baseline or a redeploy can move past.
    private Cause handleUndoOrBaselineTimeout(String datasourceName,
                                              int requestedVersion,
                                              String operation,
                                              SchemaVersionValue value,
                                              Cause cause) {
        if (! (cause instanceof CoreError.Timeout)) {
            return cause;
        }

        log.error("Schema {} for '{}' targeting version {} timed out — releasing lock and marking FAILED",
                  operation,
                  datasourceName,
                  requestedVersion);
        updateStatus(datasourceName, value, SchemaStatus.FAILED).onFailure(c -> log.error("Failed to record FAILED status for '{}' after {} timeout: {}",
                                                                                          datasourceName,
                                                                                          operation,
                                                                                          c.message()));

        return "undo".equals(operation)
               ? SchemaError.UndoTimedOut.undoTimedOut(datasourceName, requestedVersion)
               : SchemaError.BaselineTimedOut.baselineTimedOut(datasourceName, requestedVersion);
    }

    /// #543 condition 3: writes `result.currentVersion()` — the value [AetherSchemaManager]
    /// actually reports for what it just did to the database — never `value.currentVersion()`
    /// (the pre-existing record, unchanged by this operation) and never a version re-derived
    /// independently from the request. A future change to how the manager computes the
    /// post-operation version therefore cannot silently desync this KV record from the schema
    /// history table it is supposed to describe. `attemptCount` resets to 0: a completed undo or
    /// baseline is a fresh terminal state, not a retry of anything.
    private Promise<Unit> recordOutcome(String datasourceName,
                                        String operation,
                                        SchemaVersionValue value,
                                        AetherSchemaManager.SchemaResult result) {
        log.info("Schema {} for '{}' complete: {} script(s) applied, now at version {}",
                 operation,
                 datasourceName,
                 result.appliedCount(),
                 result.currentVersion());
        var key = SchemaVersionKey.schemaVersionKey(datasourceName);
        var updated = SchemaVersionValue.schemaVersionValue(datasourceName,
                                                            result.currentVersion(),
                                                            lastMigrationFor(operation, result),
                                                            SchemaStatus.COMPLETED,
                                                            value.artifactCoords(),
                                                            value.owningBlueprint(),
                                                            0);
        KVCommand<AetherKey> command = new Put<>(key, updated);

        return cluster.apply(List.of(command))
                      .mapToUnit();
    }

    /// A synthetic marker, not a claim about which real script file ran: `appliedCount` can be
    /// greater than one (undo or baseline can step through several scripts to reach the target),
    /// so there is no single "the applied filename" to report honestly without duplicating
    /// [AetherSchemaManager]'s own script-selection logic here. `"V<version>__baseline"` keeps the
    /// convention [SchemaRoutes] used inline before this fix (pinned by
    /// `SchemaRoutesBaselineTest`), now built from the REAL applied version instead of the
    /// caller's raw request parameter; `"U<version>__undo"` is the analogous marker for undo, new
    /// with this fix since undo previously never reached a real completion at all.
    private static String lastMigrationFor(String operation, AetherSchemaManager.SchemaResult result) {
        var version = String.format("%03d", result.currentVersion());

        return "baseline".equals(operation)
               ? "V" + version + "__baseline"
               : "U" + version + "__undo";
    }

    /// The route layer's existence check guarantees a schema version RECORD, but not one that
    /// still declares a resolvable migration set. Unlike [#resolveAndParseMigrations] (forward
    /// migrate's resolution chain, where [#noDeclaredMigrations] treats an empty set as legitimate
    /// — a background reconciliation pass finding nothing pending is fine), an absent-coordinates
    /// or empty-scripts outcome is a genuine, reportable failure here: undo and baseline are
    /// operator requests to perform one specific action against one specific artifact, so "there
    /// is nothing to act on" is always an error, never a quiet no-op.
    private Promise<List<MigrationEntry>> resolveMigrationScripts(String datasourceName, SchemaVersionValue value) {
        return Option.option(value.artifactCoords())
                     .filter(Verify.Is::present)
                     .map(coords -> resolveDeclaredScripts(datasourceName, value, coords))
                     .or(() -> SchemaError.MigrationArtifactUnresolved.migrationArtifactUnresolved(datasourceName,
                                                                                                   value.currentVersion(),
                                                                                                   declaredMigration(value))
                                                                      .promise());
    }

    private Promise<List<MigrationEntry>> resolveDeclaredScripts(String datasourceName,
                                                                 SchemaVersionValue value,
                                                                 String artifactCoords) {
        return Artifact.artifact(artifactCoords)
                       .async()
                       .flatMap(this::resolveArtifactBytes)
                       .flatMap(jarBytes -> BlueprintArtifactParser.parse(jarBytes).async())
                       .flatMap(artifact -> scriptsFor(datasourceName, value, artifact));
    }

    private static Promise<List<MigrationEntry>> scriptsFor(String datasourceName,
                                                            SchemaVersionValue value,
                                                            BlueprintArtifact artifact) {
        return Option.option(artifact.schemaMigrations().get(datasourceName))
                     .filter(list -> !list.isEmpty())
                     .map(Promise::success)
                     .or(() -> SchemaError.MigrationSetUnavailable.migrationSetUnavailable(datasourceName,
                                                                                           value.artifactCoords(),
                                                                                           value.currentVersion())
                                                                  .promise());
    }

    private static boolean isSchemaVersionValue(AetherValue value) {
        return value instanceof SchemaVersionValue;
    }

    // #760/#724 review BLOCKING 2: cleanup runs via `replaceResult` (DEPENDENT), not
    // `onFailure`/`onResultRun` (INDEPENDENT — dispatched onto AsyncExecutor, not ordered before
    // this promise's result reaches a caller). The in-flight fence must be cleared, and a failed
    // attempt's lock released, before a caller reacting to this promise's outcome (a manual
    // retry route, in particular) can observe it.
    //
    // #760/#724 review round 2 item c: `finalizeAttempt` is nested INSIDE `acquireLock`'s success
    // branch, not chained after the whole flow. A second dispatch that arrives while a first
    // attempt is genuinely in flight has its own `acquireLock` fail fast (the fence add returns
    // `false`) — with `replaceResult` wrapping the ENTIRE chain, that failure used to still run
    // `finalizeAttempt`, unconditionally removing the FIRST attempt's fence entry and, because the
    // result was a failure, releasing the FIRST attempt's still-held KV lock out from under it.
    // Nesting means `finalizeAttempt` runs only for the attempt whose OWN `acquireLock` succeeded —
    // no owner token needed, the call-site scoping is the ownership proof.
    private Promise<Unit> executeMigrationFlow(String datasourceName, SchemaVersionValue value) {
        var attemptToken = new Object();

        return acquireLock(datasourceName, attemptToken).flatMap(_ -> runMigration(datasourceName, value).flatMap(_ -> releaseLock(datasourceName))
                                                                                  .replaceResult(result -> finalizeAttempt(datasourceName,
                                                                                                                           attemptToken,
                                                                                                                           result)));
    }

    private Result<Unit> finalizeAttempt(String datasourceName, Object attemptToken, Result<Unit> result) {
        inFlightMigrations.remove(datasourceName, attemptToken);
        if (result.isFailure()) {
            releaseLockSilently(datasourceName);
        }

        return result;
    }

    // #760/#724 review BLOCKING 2: uses `mapError` (DEPENDENT), not `onFailure` (INDEPENDENT —
    // dispatched onto AsyncExecutor, explicitly NOT ordered before the promise's resolution
    // reaches `.await()` or any downstream caller). `handleMigrationFailure` registers the retry
    // (`scheduledRetries.put`); that registration must complete before this failure becomes
    // observable, or a caller that reacts immediately (a manual retry) can run
    // `cancelScheduledRetry` before the put lands, orphaning an uncancelled timer that later
    // fires a redundant, uncoordinated migration attempt.
    // #760/#724 review round 2 item c: `.timeout()` is placed directly on `resolveAndParseMigrations`'s
    // returned promise, before any further `.flatMap` is chained onto it — per `Promise.timeout()`'s
    // own placement warning, a forced failure only protects transformations chained AFTER the call,
    // so it must sit as close to the low-level operation (`schemaManager.migrate`, reached inside
    // `resolveAndParseMigrations`) as this call site allows. Without a bound, a wedged connection or a
    // runaway script holds the migration lock and the in-flight fence indefinitely, and no external
    // caller (manual retry, rebuild recovery) can ever get back in.
    private Promise<Unit> runMigration(String datasourceName, SchemaVersionValue value) {
        var startTime = System.currentTimeMillis();

        emitMigrationStarted(datasourceName, value);

        return updateStatus(datasourceName, value, SchemaStatus.MIGRATING).flatMap(_ -> resolveAndParseMigrations(datasourceName,
                                                                                                                  value).timeout(schemaManager.policy()
                                                                                                                                              .migrationTimeout()))
                           .flatMap(_ -> markCompleted(datasourceName, value, startTime))
                           .mapError(cause -> handleMigrationFailure(datasourceName, value, cause));
    }

    private void emitMigrationStarted(String datasourceName, SchemaVersionValue value) {
        var artifactCoords = Option.option(value.artifactCoords()).or("");

        AuditLog.schemaMigrationStarted(datasourceName, artifactCoords, self.id());
        router.onPresent(r -> r.route(MigrationStarted.migrationStarted(datasourceName, artifactCoords, self)));
    }

    private void emitMigrationCompleted(String datasourceName,
                                        SchemaVersionValue value,
                                        int appliedCount,
                                        int currentVersion,
                                        long durationMs) {
        var artifactCoords = Option.option(value.artifactCoords()).or("");

        AuditLog.schemaMigrationCompleted(datasourceName, artifactCoords, appliedCount, currentVersion, durationMs);
        router.onPresent(r -> r.route(MigrationCompleted.migrationCompleted(datasourceName,
                                                                            artifactCoords,
                                                                            appliedCount,
                                                                            currentVersion,
                                                                            durationMs,
                                                                            self)));
    }

    private Cause handleMigrationFailure(String datasourceName, SchemaVersionValue value, Cause cause) {
        var classification = classifyFailure(cause);
        var attemptNumber = value.attemptCount() + 1;
        var artifactCoords = Option.option(value.artifactCoords()).or("");

        if (classification == FailureClassification.TRANSIENT && attemptNumber < MAX_RETRIES) {
            scheduleRetry(datasourceName, value, cause, classification, attemptNumber, artifactCoords);
        } else {
            emitPermanentFailure(datasourceName, value, cause, classification, attemptNumber, artifactCoords);
        }

        return cause;
    }

    private void scheduleRetry(String datasourceName,
                               SchemaVersionValue value,
                               Cause cause,
                               FailureClassification classification,
                               int attemptNumber,
                               String artifactCoords) {
        var nextRetryMs = calculateBackoff(attemptNumber);
        var explanation = SchemaExplanationBuilder.buildFailedExplanation(datasourceName,
                                                                          artifactCoords,
                                                                          classification,
                                                                          cause.message(),
                                                                          List.of(),
                                                                          attemptNumber,
                                                                          MAX_RETRIES,
                                                                          nextRetryMs);

        log.warn("Schema migration failed (transient) for '{}': {} — retrying in {}s (attempt {}/{})",
                 datasourceName,
                 cause.message(),
                 nextRetryMs / 1000,
                 attemptNumber,
                 MAX_RETRIES);
        var retryExplanation = SchemaExplanationBuilder.buildRetryingExplanation(datasourceName,
                                                                                 artifactCoords,
                                                                                 attemptNumber,
                                                                                 nextRetryMs);

        AuditLog.schemaMigrationRetrying(datasourceName, artifactCoords, attemptNumber, nextRetryMs);
        router.onPresent(r -> r.route(MigrationRetrying.migrationRetrying(datasourceName,
                                                                          artifactCoords,
                                                                          attemptNumber,
                                                                          nextRetryMs,
                                                                          retryExplanation)));
        updateStatusWithAttempt(datasourceName, value, SchemaStatus.PENDING, attemptNumber).onFailure(c -> log.error("Failed to update retry status for '{}': {}",
                                                                                                                     datasourceName,
                                                                                                                     c.message()));
        var future = SharedScheduler.schedule(() -> migrateIfNeeded(datasourceName), timeSpan(nextRetryMs).millis());

        scheduledRetries.put(datasourceName, future);
    }

    private void emitPermanentFailure(String datasourceName,
                                      SchemaVersionValue value,
                                      Cause cause,
                                      FailureClassification classification,
                                      int attemptNumber,
                                      String artifactCoords) {
        var explanation = SchemaExplanationBuilder.buildFailedExplanation(datasourceName,
                                                                          artifactCoords,
                                                                          classification,
                                                                          cause.message(),
                                                                          List.of(),
                                                                          attemptNumber,
                                                                          MAX_RETRIES,
                                                                          0);

        log.error("Schema migration failed (permanent) for '{}': {}", datasourceName, explanation);
        AuditLog.schemaMigrationFailed(datasourceName, artifactCoords, classification.name(), cause.message());
        router.onPresent(r -> r.route(MigrationFailed.migrationFailed(datasourceName,
                                                                      artifactCoords,
                                                                      classification,
                                                                      cause.message(),
                                                                      List.of(),
                                                                      attemptNumber,
                                                                      MAX_RETRIES,
                                                                      explanation)));
        updateStatus(datasourceName, value, SchemaStatus.FAILED).onFailure(c -> log.error("Failed to update status to FAILED for '{}': {}",
                                                                                          datasourceName,
                                                                                          c.message()));
    }

    static FailureClassification classifyFailure(Cause cause) {
        if (cause instanceof SchemaError.DatasourceUnreachable) {
            return FailureClassification.TRANSIENT;
        }

        if (cause instanceof SchemaError.LockAcquisitionFailed) {
            return FailureClassification.TRANSIENT;
        }

        if (cause instanceof SchemaError.MigrationFailed) {
            return FailureClassification.PERMANENT;
        }

        if (cause instanceof SchemaError.ChecksumMismatch) {
            return FailureClassification.PERMANENT;
        }

        if (cause instanceof SchemaError.MigrationArtifactUnresolved) {
            return FailureClassification.PERMANENT;
        }

        if (cause instanceof SchemaError.MigrationSetUnavailable) {
            return FailureClassification.PERMANENT;
        }
        // #760/#724 review round 2 item c: a timed-out attempt (Promise.timeout() on
        // resolveAndParseMigrations, bounded by SchemaPolicy#migrationTimeout) is classified explicitly
        // rather than left to the UNKNOWN fallthrough below — both already skip retry and reach
        // emitPermanentFailure/SchemaStatus.FAILED, but naming it PERMANENT here makes the timeout path
        // directly unit-testable and self-documenting instead of relying on fallthrough behavior.
        if (cause instanceof CoreError.Timeout) {
            return FailureClassification.PERMANENT;
        }

        return FailureClassification.UNKNOWN;
    }

    static long calculateBackoff(int attemptNumber) {
        var multiplier = 1L;

        for (var i = 0; i < attemptNumber; i++) {
            multiplier *= 3;
        }

        return BACKOFF_BASE_MS * multiplier;
    }

    private void releaseLockSilently(String datasourceName) {
        releaseLock(datasourceName).onFailure(c -> log.error("Failed to release lock for '{}': {}",
                                                             datasourceName,
                                                             c.message()));
    }

    private final ConcurrentHashMap<String, Object> inFlightMigrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledRetries = new ConcurrentHashMap<>();

    /// Single-flight fence across BOTH dispatch paths (the backoff timer in [#scheduleRetry] and any
    /// external re-dispatch — manual retry route, rebuild recovery). `inFlightMigrations` alone only
    /// covers the window an attempt is actually running; once a failed attempt releases it, a stale,
    /// already-scheduled backoff timer and a fresh external dispatch can both later acquire it in
    /// sequence, redundantly re-running the same migration scripts (#760/#724 review, BLOCKING 2).
    /// Cancelling only AFTER the fence is claimed — not on every call — matters: `scheduleRetry`'s own
    /// KV status write can re-enter here while attempt N is still fenced, and that re-entrant call must
    /// fail fast on the fence without ever touching the retry it is itself part of.
    ///
    /// #760/#724 review round 3 BLOCKING 1: `inFlightMigrations` maps to a per-attempt token, not a
    /// bare presence marker, so every release (`finalizeAttempt`, the `isLockHeld` short-circuit below,
    /// and [#releaseFenceOnLockFailure]) is a `remove(key, token)` compare-and-remove — an attempt can
    /// only ever clear the ONE fence entry it itself claimed, never a later attempt's. That matters here
    /// specifically because the lock Put below is now bounded by a timeout: a lock write that fails or
    /// never settles must still release the fence, and doing so unconditionally (bare `remove(key)`)
    /// would risk tearing down a fresh attempt's fence if that cleanup ever ran late.
    private Promise<Unit> acquireLock(String datasourceName, Object attemptToken) {
        if (inFlightMigrations.putIfAbsent(datasourceName, attemptToken) != null) {
            return SchemaError.LockAcquisitionFailed.lockAcquisitionFailed(datasourceName).promise();
        }

        cancelScheduledRetry(datasourceName);
        var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(datasourceName);

        if (isLockHeld(lockKey)) {
            inFlightMigrations.remove(datasourceName, attemptToken);

            return SchemaError.LockAcquisitionFailed.lockAcquisitionFailed(datasourceName).promise();
        }

        var lockValue = SchemaMigrationLockValue.schemaMigrationLockValue(datasourceName, self, LOCK_TTL_MS);
        KVCommand<AetherKey> command = new Put<>(lockKey, lockValue);
        // #760/#724 review round 3 BLOCKING 1: bounded the same way as the migration itself
        // (schemaManager.policy().migrationTimeout(), read once per attempt) — before this, a lock Put
        // that failed or never settled left `inFlightMigrations` claimed forever, since `finalizeAttempt`
        // (the only release site) is nested inside THIS call's success branch and never runs on its
        // failure. `mapError` is used, not `onFailure`/`onResultRun`, for the same DEPENDENT-ordering
        // reason as `handleMigrationFailure` above: an immediate external retry reacting to this
        // failure must see the fence already released, not race an async cleanup callback.
        return cluster.apply(List.of(command))
                      .timeout(schemaManager.policy().migrationTimeout())
                      .mapToUnit()
                      .mapError(cause -> releaseFenceOnLockFailure(datasourceName, attemptToken, cause));
    }

    private Cause releaseFenceOnLockFailure(String datasourceName, Object attemptToken, Cause cause) {
        inFlightMigrations.remove(datasourceName, attemptToken);

        return cause;
    }

    private void cancelScheduledRetry(String datasourceName) {
        Option.option(scheduledRetries.remove(datasourceName)).onPresent(SchemaOrchestratorServiceInstance::cancelFuture);
    }

    private static void cancelFuture(ScheduledFuture<?> future) {
        future.cancel(false);
    }

    private boolean isLockHeld(SchemaMigrationLockKey lockKey) {
        return kvStore.get(lockKey)
                      .filter(SchemaMigrationLockValue.class::isInstance)
                      .map(SchemaMigrationLockValue.class::cast)
                      .filter(lock -> !lock.isExpired())
                      .isPresent();
    }

    private Promise<Unit> releaseLock(String datasourceName) {
        var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(datasourceName);
        KVCommand<AetherKey> command = new Remove<>(lockKey);

        return cluster.apply(List.of(command))
                      .mapToUnit();
    }

    private Promise<Unit> updateStatus(String datasourceName, SchemaVersionValue current, SchemaStatus newStatus) {
        var key = SchemaVersionKey.schemaVersionKey(datasourceName);
        var updated = SchemaVersionValue.schemaVersionValue(datasourceName,
                                                            current.currentVersion(),
                                                            current.lastMigration(),
                                                            newStatus,
                                                            current.artifactCoords(),
                                                            current.owningBlueprint(),
                                                            current.attemptCount());
        KVCommand<AetherKey> command = new Put<>(key, updated);

        return cluster.apply(List.of(command))
                      .mapToUnit();
    }

    private Promise<Unit> updateStatusWithAttempt(String datasourceName,
                                                  SchemaVersionValue current,
                                                  SchemaStatus newStatus,
                                                  int attemptCount) {
        var key = SchemaVersionKey.schemaVersionKey(datasourceName);
        var updated = SchemaVersionValue.schemaVersionValue(datasourceName,
                                                            current.currentVersion(),
                                                            current.lastMigration(),
                                                            newStatus,
                                                            current.artifactCoords(),
                                                            current.owningBlueprint(),
                                                            attemptCount);
        KVCommand<AetherKey> command = new Put<>(key, updated);

        return cluster.apply(List.of(command))
                      .mapToUnit();
    }

    private Promise<Unit> resolveAndParseMigrations(String datasourceName, SchemaVersionValue value) {
        return Option.option(value.artifactCoords())
                     .filter(Verify.Is::present)
                     .map(coords -> resolveDeclaredMigrations(datasourceName, value, coords))
                     .or(() -> unresolvedArtifactCoords(datasourceName, value));
    }

    private Promise<Unit> resolveDeclaredMigrations(String datasourceName,
                                                    SchemaVersionValue value,
                                                    String artifactCoords) {
        return Artifact.artifact(artifactCoords)
                       .async()
                       .flatMap(this::resolveArtifactBytes)
                       .flatMap(jarBytes -> BlueprintArtifactParser.parse(jarBytes).async())
                       .flatMap(artifact -> executeMigrationsFromArtifact(datasourceName, value, artifact));
    }

    private Promise<byte[]> resolveArtifactBytes(Artifact artifact) {
        return repository.locate(artifact, "blueprint")
                         .flatMap(SchemaOrchestratorServiceInstance::readLocationBytes)
                         .orElse(() -> repository.locate(artifact)
                                                 .flatMap(SchemaOrchestratorServiceInstance::readLocationBytes))
                         .orElse(() -> artifactStore.resolve(artifact));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Promise<byte[]> readLocationBytes(Location location) {
        return Promise.lift(Causes::fromThrowable, () -> readStreamBytes(location));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static byte[] readStreamBytes(Location location) throws Exception {
        try (var stream = location.url().openStream()) {
            return stream.readAllBytes();
        }
    }

    /// A schema version record exists only because the deploying blueprint artifact declared
    /// migrations for this datasource (`BlueprintService.buildSchemaMigrationCommands`), and every
    /// writer of the record carries the coordinates forward. Absent coordinates on a record that
    /// declares a migration set therefore mean the declared scripts can no longer be located — not
    /// "this datasource has no migrations", which is expressed by the *absence* of the record and
    /// short-circuits in `migrateIfNeeded`.
    private static Promise<Unit> unresolvedArtifactCoords(String datasourceName, SchemaVersionValue value) {
        return declaresMigrations(value)
               ? SchemaError.MigrationArtifactUnresolved.migrationArtifactUnresolved(datasourceName,
                                                                                     value.currentVersion(),
                                                                                     declaredMigration(value))
                                                        .promise()
               : noDeclaredMigrations(datasourceName);
    }

    /// The recorded coordinates resolved and parsed, but the artifact holds no scripts for a
    /// datasource that declared them — the resolved artifact is not the one the deploy declared
    /// against (republished coordinates, or a fallback that located a different jar).
    private static Promise<Unit> unavailableMigrationSet(String datasourceName, SchemaVersionValue value) {
        return declaresMigrations(value)
               ? SchemaError.MigrationSetUnavailable.migrationSetUnavailable(datasourceName,
                                                                             value.artifactCoords(),
                                                                             value.currentVersion())
                                                    .promise()
               : noDeclaredMigrations(datasourceName);
    }

    private static Promise<Unit> noDeclaredMigrations(String datasourceName) {
        log.debug("Datasource '{}' declares no schema migrations — nothing to apply", datasourceName);

        return Promise.success(unit());
    }

    /// The migration set recorded at deploy time: `currentVersion` is the highest `V<n>__` script
    /// found in the artifact and `lastMigration` its filename, so either being populated means the
    /// deploy expected scripts to run.
    private static boolean declaresMigrations(SchemaVersionValue value) {
        return value.currentVersion() > 0 || Verify.Is.present(value.lastMigration());
    }

    private static String declaredMigration(SchemaVersionValue value) {
        return Option.option(value.lastMigration()).or("");
    }

    private Promise<Unit> executeMigrationsFromArtifact(String datasourceName,
                                                        SchemaVersionValue value,
                                                        BlueprintArtifact artifact) {
        return Option.option(artifact.schemaMigrations().get(datasourceName))
                     .filter(list -> !list.isEmpty())
                     .map(scripts -> provisionAndMigrate(datasourceName,
                                                         scripts,
                                                         value.owningBlueprint()))
                     .or(() -> unavailableMigrationSet(datasourceName, value));
    }

    /// The schema version record's `owningBlueprint` is the blueprint whose artifact declared these
    /// scripts, so it is the identity claimed against the physical database's `aether_schema_owner`
    /// row — a second blueprint whose node config section resolves to the SAME database is refused
    /// there, where the publish-time name comparison could not see it.
    private Promise<Unit> provisionAndMigrate(String datasourceName, List<MigrationEntry> scripts, BlueprintId owner) {
        log.info("Executing {} migration scripts for datasource '{}'", scripts.size(), datasourceName);

        return provisionConnector(datasourceName).flatMap(connector -> schemaManager.migrate(datasourceName,
                                                                                             scripts,
                                                                                             connector,
                                                                                             self.id(),
                                                                                             owner)
                                                                                    .onSuccess(result -> logMigrationSuccess(datasourceName,
                                                                                                                             result))
                                                                                    .mapToUnit()
                                                                                    .onResultRun(() -> releaseConnectorSilently(datasourceName)));
    }

    private Promise<SqlConnector> provisionConnector(String datasourceName) {
        return connectionProvider.connector(datasourceName)
                                 .onFailure(cause -> log.info("No database config for '{}': {} — skipping migration",
                                                              datasourceName,
                                                              cause.message()));
    }

    private static void logMigrationSuccess(String datasourceName, AetherSchemaManager.SchemaResult result) {
        log.info("Schema migration for '{}': {} scripts applied, now at version {}",
                 datasourceName,
                 result.appliedCount(),
                 result.currentVersion());
    }

    private void releaseConnectorSilently(String datasourceName) {
        connectionProvider.release(datasourceName)
                          .onFailure(c -> log.warn("Failed to release connector for '{}': {}",
                                                   datasourceName,
                                                   c.message()));
    }

    private Promise<Unit> markCompleted(String datasourceName, SchemaVersionValue value, long startTime) {
        var durationMs = System.currentTimeMillis() - startTime;

        emitMigrationCompleted(datasourceName, value, 0, value.currentVersion(), durationMs);

        return updateStatus(datasourceName, value, SchemaStatus.COMPLETED);
    }
}
