package org.pragmatica.aether.deployment.schema;

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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Distributed coordination layer for schema migrations.
/// Sits between KV-Store notifications and the AetherSchemaManager engine,
/// handling lock acquisition, artifact resolution, and status updates via consensus.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public interface SchemaOrchestratorService {
    /// Trigger migration for a datasource if its status is PENDING.
    Promise<Unit> migrateIfNeeded(String datasourceName);

    /// Undo migrations back to a target version.
    Promise<Unit> undoTo(String datasourceName, int targetVersion);

    /// Set a baseline version for a datasource.
    Promise<Unit> baseline(String datasourceName, int version);

    /// Lock TTL for schema migrations (5 minutes).
    long LOCK_TTL_MS = 5 * 60 * 1000L;

    /// Maximum number of automatic retries for transient failures.
    int MAX_RETRIES = 3;

    /// Base backoff delay in milliseconds (5s, 15s, 45s with 3x multiplier).
    long BACKOFF_BASE_MS = 5000;

    /// Creates a new SchemaOrchestratorService.
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

    /// Backward-compatible factory without MessageRouter (events not emitted).
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

    @Override
    public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
        log.info("Undo to version {} requested for datasource: {} (not yet implemented)", targetVersion, datasourceName);
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> baseline(String datasourceName, int version) {
        log.info("Baseline version {} requested for datasource: {} (not yet implemented)", version, datasourceName);
        return Promise.success(unit());
    }

    private static boolean isSchemaVersionValue(AetherValue value) {
        return value instanceof SchemaVersionValue;
    }

    private Promise<Unit> executeMigrationFlow(String datasourceName, SchemaVersionValue value) {
        return acquireLock(datasourceName).flatMap(_ -> runMigration(datasourceName, value))
                          .flatMap(_ -> releaseLock(datasourceName))
                          .onFailure(_ -> releaseLockSilently(datasourceName))
                          .onResultRun(() -> inFlightMigrations.remove(datasourceName));
    }

    private Promise<Unit> runMigration(String datasourceName, SchemaVersionValue value) {
        var startTime = System.currentTimeMillis();
        emitMigrationStarted(datasourceName, value);
        return updateStatus(datasourceName, value, SchemaStatus.MIGRATING).flatMap(_ -> resolveAndParseMigrations(datasourceName,
                                                                                                                  value))
                           .flatMap(_ -> markCompleted(datasourceName, value, startTime))
                           .onFailure(cause -> handleMigrationFailure(datasourceName, value, cause));
    }

    private void emitMigrationStarted(String datasourceName, SchemaVersionValue value) {
        var artifactCoords = Option.option(value.artifactCoords())
                                   .or("");
        AuditLog.schemaMigrationStarted(datasourceName, artifactCoords, self.id());
        router.onPresent(r -> r.route(MigrationStarted.migrationStarted(datasourceName, artifactCoords, self)));
    }

    private void emitMigrationCompleted(String datasourceName,
                                        SchemaVersionValue value,
                                        int appliedCount,
                                        int currentVersion,
                                        long durationMs) {
        var artifactCoords = Option.option(value.artifactCoords())
                                   .or("");
        AuditLog.schemaMigrationCompleted(datasourceName, artifactCoords, appliedCount, currentVersion, durationMs);
        router.onPresent(r -> r.route(MigrationCompleted.migrationCompleted(datasourceName,
                                                                            artifactCoords,
                                                                            appliedCount,
                                                                            currentVersion,
                                                                            durationMs,
                                                                            self)));
    }

    private void handleMigrationFailure(String datasourceName, SchemaVersionValue value, Cause cause) {
        var classification = classifyFailure(cause);
        var attemptNumber = value.attemptCount() + 1;
        var artifactCoords = Option.option(value.artifactCoords())
                                   .or("");
        if (classification == FailureClassification.TRANSIENT && attemptNumber < MAX_RETRIES) {
            scheduleRetry(datasourceName, value, cause, classification, attemptNumber, artifactCoords);
        } else {
            emitPermanentFailure(datasourceName, value, cause, classification, attemptNumber, artifactCoords);
        }
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
        updateStatusWithAttempt(datasourceName, value, SchemaStatus.PENDING, attemptNumber)
        .onFailure(c -> log.error("Failed to update retry status for '{}': {}", datasourceName, c.message()));
        SharedScheduler.schedule(() -> migrateIfNeeded(datasourceName), timeSpan(nextRetryMs).millis());
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
        updateStatus(datasourceName, value, SchemaStatus.FAILED)
        .onFailure(c -> log.error("Failed to update status to FAILED for '{}': {}", datasourceName, c.message()));
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
        releaseLock(datasourceName)
        .onFailure(c -> log.error("Failed to release lock for '{}': {}", datasourceName, c.message()));
    }

    private static final Cause LOCK_HELD = Causes.cause("Schema migration lock held — skipping duplicate");
    private final java.util.Set<String> inFlightMigrations = ConcurrentHashMap.newKeySet();

    private Promise<Unit> acquireLock(String datasourceName) {
        // Local deduplication — prevent concurrent migration for the same datasource on this node
        if (!inFlightMigrations.add(datasourceName)) {
            return LOCK_HELD.promise();
        }
        // Distributed lock — prevent concurrent migration across nodes
        var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(datasourceName);
        if (isLockHeld(lockKey)) {
            inFlightMigrations.remove(datasourceName);
            return LOCK_HELD.promise();
        }
        var lockValue = SchemaMigrationLockValue.schemaMigrationLockValue(datasourceName, self, LOCK_TTL_MS);
        KVCommand<AetherKey> command = new Put<>(lockKey, lockValue);
        return cluster.apply(List.of(command))
                      .mapToUnit();
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
                                                            attemptCount);
        KVCommand<AetherKey> command = new Put<>(key, updated);
        return cluster.apply(List.of(command))
                      .mapToUnit();
    }

    private Promise<Unit> resolveAndParseMigrations(String datasourceName, SchemaVersionValue value) {
        return Option.option(value.artifactCoords())
                     .filter(s -> !s.isEmpty())
                     .map(coords -> resolveArtifactAndLog(datasourceName, coords))
                     .or(logNoArtifactCoords(datasourceName));
    }

    private Promise<Unit> resolveArtifactAndLog(String datasourceName, String artifactCoords) {
        return Artifact.artifact(artifactCoords)
                       .async()
                       .flatMap(this::resolveArtifactBytes)
                       .flatMap(jarBytes -> BlueprintArtifactParser.parse(jarBytes)
                                                                   .async())
                       .flatMap(artifact -> executeMigrationsFromArtifact(datasourceName, artifact));
    }

    private Promise<byte[]> resolveArtifactBytes(Artifact artifact) {
        // Try blueprint classifier first (migration scripts are packaged in the blueprint JAR)
        return repository.locate(artifact, "blueprint")
                         .flatMap(SchemaOrchestratorServiceInstance::readLocationBytes)
                         .orElse(() -> repository.locate(artifact)
                                                 .flatMap(SchemaOrchestratorServiceInstance::readLocationBytes))
                         .orElse(() -> artifactStore.resolve(artifact));
    }

    @SuppressWarnings("JBCT-EX-01") // Infrastructure I/O: URL stream reading
    private static Promise<byte[]> readLocationBytes(Location location) {
        return Promise.lift(Causes::fromThrowable, () -> readStreamBytes(location));
    }

    @SuppressWarnings("JBCT-EX-01") // Adapter boundary: called within Promise.lift
    private static byte[] readStreamBytes(Location location) throws Exception {
        try (var stream = location.url()
                                  .openStream()) {
            return stream.readAllBytes();
        }
    }

    private Promise<Unit> logNoArtifactCoords(String datasourceName) {
        log.warn("No artifact coordinates for datasource: {}, skipping migration", datasourceName);
        return Promise.success(unit());
    }

    private Promise<Unit> executeMigrationsFromArtifact(String datasourceName, BlueprintArtifact artifact) {
        return Option.option(artifact.schemaMigrations()
                                     .get(datasourceName))
                     .filter(list -> !list.isEmpty())
                     .map(scripts -> provisionAndMigrate(datasourceName, scripts))
                     .or(logNoMigrationsInArtifact(datasourceName));
    }

    private Promise<Unit> provisionAndMigrate(String datasourceName, List<MigrationEntry> scripts) {
        log.info("Executing {} migration scripts for datasource '{}'", scripts.size(), datasourceName);
        return provisionConnector(datasourceName)
        .flatMap(connector -> schemaManager.migrate(datasourceName,
                                                    scripts,
                                                    connector,
                                                    self.id())
                                           .onSuccess(result -> logMigrationSuccess(datasourceName, result))
                                           .mapToUnit()
                                           .onResultRun(() -> releaseConnectorSilently(datasourceName)));
    }

    /// Provision connector, or skip gracefully if no config exists.
    /// Config absence = no database configured = skip (preserves pre-migration-engine behavior).
    /// Config present but connection fails = real error, propagate.
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

    private static Promise<Unit> logNoMigrationsInArtifact(String datasourceName) {
        log.info("No migrations for datasource '{}' in artifact", datasourceName);
        return Promise.success(unit());
    }

    private Promise<Unit> markCompleted(String datasourceName, SchemaVersionValue value, long startTime) {
        var durationMs = System.currentTimeMillis() - startTime;
        emitMigrationCompleted(datasourceName, value, 0, value.currentVersion(), durationMs);
        return updateStatus(datasourceName, value, SchemaStatus.COMPLETED);
    }
}
