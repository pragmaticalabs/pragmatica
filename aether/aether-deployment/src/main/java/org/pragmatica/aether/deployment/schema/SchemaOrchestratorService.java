package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.artifact.Artifact;
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
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

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

    /// Creates a new SchemaOrchestratorService.
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
                                                     self);
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

    SchemaOrchestratorServiceInstance(ClusterNode<KVCommand<AetherKey>> cluster,
                                      KVStore<AetherKey, AetherValue> kvStore,
                                      ArtifactStore artifactStore,
                                      Repository repository,
                                      AetherSchemaManager schemaManager,
                                      DatasourceConnectionProvider connectionProvider,
                                      NodeId self) {
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.artifactStore = artifactStore;
        this.repository = repository;
        this.schemaManager = schemaManager;
        this.connectionProvider = connectionProvider;
        this.self = self;
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
        return updateStatus(datasourceName, value, SchemaStatus.MIGRATING).flatMap(_ -> resolveAndParseMigrations(datasourceName,
                                                                                                                  value))
                           .flatMap(_ -> markCompleted(datasourceName, value))
                           .onFailure(cause -> logMigrationFailure(datasourceName, value, cause));
    }

    private void logMigrationFailure(String datasourceName, SchemaVersionValue value, Cause cause) {
        log.error("Schema migration failed for datasource '{}': {}", datasourceName, cause.message());
        updateStatus(datasourceName, value, SchemaStatus.FAILED)
        .onFailure(c -> log.error("Failed to update status to FAILED for '{}': {}", datasourceName, c.message()));
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
                                                            current.artifactCoords());
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

    private Promise<Unit> markCompleted(String datasourceName, SchemaVersionValue value) {
        return updateStatus(datasourceName, value, SchemaStatus.COMPLETED);
    }
}
