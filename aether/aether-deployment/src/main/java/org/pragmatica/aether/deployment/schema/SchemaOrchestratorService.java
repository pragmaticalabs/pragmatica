package org.pragmatica.aether.deployment.schema;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.blueprint.BlueprintArtifactParser;
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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

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
                                                               AetherSchemaManager schemaManager,
                                                               NodeId self) {
        return new SchemaOrchestratorServiceInstance(cluster, kvStore, artifactStore, schemaManager, self);
    }
}

@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
class SchemaOrchestratorServiceInstance implements SchemaOrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(SchemaOrchestratorServiceInstance.class);

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final ArtifactStore artifactStore;
    private final AetherSchemaManager schemaManager;
    private final NodeId self;

    SchemaOrchestratorServiceInstance(ClusterNode<KVCommand<AetherKey>> cluster,
                                      KVStore<AetherKey, AetherValue> kvStore,
                                      ArtifactStore artifactStore,
                                      AetherSchemaManager schemaManager,
                                      NodeId self) {
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.artifactStore = artifactStore;
        this.schemaManager = schemaManager;
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
        return acquireLock(datasourceName).flatMap(_ -> updateStatus(datasourceName, value, SchemaStatus.MIGRATING))
                          .flatMap(_ -> resolveAndParseMigrations(datasourceName, value))
                          .flatMap(_ -> markCompleted(datasourceName, value))
                          .onFailure(cause -> handleMigrationFailure(datasourceName, value, cause))
                          .flatMap(_ -> releaseLock(datasourceName));
    }

    private Promise<Unit> acquireLock(String datasourceName) {
        var lockKey = SchemaMigrationLockKey.schemaMigrationLockKey(datasourceName);
        var lockValue = SchemaMigrationLockValue.schemaMigrationLockValue(datasourceName, self, LOCK_TTL_MS);
        KVCommand<AetherKey> command = new Put<>(lockKey, lockValue);
        return cluster.apply(List.of(command))
                      .mapToUnit();
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
        var artifactCoords = value.artifactCoords();
        if (artifactCoords == null || artifactCoords.isEmpty()) {
            log.warn("No artifact coordinates for datasource: {}, skipping migration", datasourceName);
            return Promise.success(unit());
        }
        return Artifact.artifact(artifactCoords)
                       .async()
                       .flatMap(artifactStore::resolve)
                       .flatMap(jarBytes -> BlueprintArtifactParser.parse(jarBytes)
                                                                   .async())
                       .flatMap(artifact -> logMigrationsFound(datasourceName, artifact));
    }

    private static Promise<Unit> logMigrationsFound(String datasourceName,
                                                    org.pragmatica.aether.slice.blueprint.BlueprintArtifact artifact) {
        var migrations = artifact.schemaMigrations()
                                 .get(datasourceName);
        if (migrations == null || migrations.isEmpty()) {
            log.warn("No migrations found for datasource '{}' in artifact", datasourceName);
        } else {
            log.info("Found {} migration scripts for datasource '{}' — "
                     + "skipping execution (SqlConnector not yet provisioned in orchestrator)",
                     migrations.size(),
                     datasourceName);
        }
        return Promise.success(unit());
    }

    private Promise<Unit> markCompleted(String datasourceName, SchemaVersionValue value) {
        return updateStatus(datasourceName, value, SchemaStatus.COMPLETED);
    }

    private void handleMigrationFailure(String datasourceName,
                                        SchemaVersionValue value,
                                        org.pragmatica.lang.Cause cause) {
        log.error("Schema migration failed for datasource '{}': {}", datasourceName, cause.message());
        updateStatus(datasourceName, value, SchemaStatus.FAILED).flatMap(_ -> releaseLock(datasourceName))
                    .onFailure(c -> log.error("Failed to update status/release lock for '{}': {}",
                                              datasourceName,
                                              c.message()));
    }
}
