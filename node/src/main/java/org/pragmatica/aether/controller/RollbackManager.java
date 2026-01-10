package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages automatic rollback on persistent slice failures.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Listen for AllInstancesFailed events</li>
 *   <li>Track previous versions when blueprints change</li>
 *   <li>Initiate rollback by updating blueprint to previous version</li>
 *   <li>Enforce cooldown period to prevent rollback loops</li>
 *   <li>Track rollback count per artifact</li>
 * </ul>
 *
 * <p>Only the leader node performs rollbacks to avoid conflicts.
 */
public interface RollbackManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event);

    /**
     * Get rollback statistics for an artifact.
     */
    Option<RollbackStats> getStats(ArtifactBase artifactBase);

    /**
     * Reset rollback count for an artifact (e.g., after manual intervention).
     */
    void resetRollbackCount(ArtifactBase artifactBase);

    /**
     * Rollback error types.
     */
    sealed interface RollbackError extends Cause {
        enum General implements RollbackError {
            NOT_LEADER("Rollback skipped: not leader"),
            DISABLED("Rollback is disabled in configuration"),
            NO_PREVIOUS_VERSION("No previous version available for rollback"),
            COOLDOWN_ACTIVE("Rollback skipped: cooldown period active"),
            MAX_ROLLBACKS_EXCEEDED("Rollback skipped: maximum rollbacks exceeded, manual intervention required");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        record RollbackFailed(Artifact artifact, Cause cause) implements RollbackError {
            @Override
            public String message() {
                return "Rollback failed for " + artifact + ": " + cause.message();
            }
        }
    }

    /**
     * Statistics for rollback tracking per artifact.
     */
    record RollbackStats(ArtifactBase artifactBase,
                         int rollbackCount,
                         long lastRollbackTimestamp,
                         Option<Version> lastRolledBackFrom,
                         Option<Version> lastRolledBackTo) {}

    static RollbackManager rollbackManager(NodeId self,
                                           RollbackConfig config,
                                           ClusterNode<KVCommand<AetherKey>> cluster,
                                           KVStore<AetherKey, AetherValue> kvStore) {
        return new RollbackManagerImpl(self, config, cluster, kvStore);
    }

    /**
     * Create a no-op rollback manager when rollback is disabled.
     */
    static RollbackManager disabled() {
        return new NoOpRollbackManager();
    }
}

class RollbackManagerImpl implements RollbackManager {
    private static final Logger log = LoggerFactory.getLogger(RollbackManagerImpl.class);

    private final NodeId self;
    private final RollbackConfig config;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final Map<ArtifactBase, RollbackState> rollbackStates = new ConcurrentHashMap<>();

    RollbackManagerImpl(NodeId self,
                        RollbackConfig config,
                        ClusterNode<KVCommand<AetherKey>> cluster,
                        KVStore<AetherKey, AetherValue> kvStore) {
        this.self = self;
        this.config = config;
        this.cluster = cluster;
        this.kvStore = kvStore;
        loadPreviousVersionsFromKvStore();
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {
        var wasLeader = isLeader.getAndSet(leaderChange.localNodeIsLeader());
        if (leaderChange.localNodeIsLeader() && !wasLeader) {
            log.info("Node {} became leader, RollbackManager activated", self);
            loadPreviousVersionsFromKvStore();
        } else if (!leaderChange.localNodeIsLeader() && wasLeader) {
            log.info("Node {} is no longer leader, RollbackManager deactivated", self);
        }
    }

    @Override
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
        var key = valuePut.cause()
                          .key();
        var value = valuePut.cause()
                            .value();
        switch (key) {
            case BlueprintKey blueprintKey when value instanceof BlueprintValue _ ->
            trackVersionChange(blueprintKey.artifact());
            case PreviousVersionKey previousVersionKey when value instanceof PreviousVersionValue previousVersionValue ->
            updateLocalPreviousVersion(previousVersionKey.artifactBase(), previousVersionValue);
            default -> {}
        }
    }

    @Override
    public void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event) {
        if (!config.enabled()) {
            log.debug("Rollback disabled, ignoring AllInstancesFailed for {}", event.artifact());
            return;
        }
        if (!config.triggerOnAllInstancesFailed()) {
            log.debug("Rollback on AllInstancesFailed disabled, ignoring event for {}", event.artifact());
            return;
        }
        if (!isLeader.get()) {
            log.debug("Not leader, skipping rollback decision for {}", event.artifact());
            return;
        }
        var artifactBase = event.artifact()
                                .base();
        var state = rollbackStates.get(artifactBase);
        if (state == null) {
            log.warn("[requestId={}] No previous version tracked for {}, cannot rollback",
                     event.requestId(),
                     event.artifact());
            return;
        }
        initiateRollback(event.artifact(), state, event.requestId());
    }

    @Override
    public Option<RollbackStats> getStats(ArtifactBase artifactBase) {
        return Option.option(rollbackStates.get(artifactBase))
                     .map(state -> new RollbackStats(artifactBase,
                                                     state.rollbackCount,
                                                     state.lastRollbackTimestamp,
                                                     state.lastRolledBackFrom,
                                                     state.lastRolledBackTo));
    }

    @Override
    public void resetRollbackCount(ArtifactBase artifactBase) {
        var state = rollbackStates.get(artifactBase);
        if (state != null) {
            state.rollbackCount = 0;
            state.lastRollbackTimestamp = 0;
            state.lastRolledBackFrom = Option.none();
            state.lastRolledBackTo = Option.none();
            log.info("Rollback count reset for {}", artifactBase);
        }
    }

    private void loadPreviousVersionsFromKvStore() {
        kvStore.snapshot()
               .forEach(this::loadPreviousVersionEntry);
        log.debug("Loaded {} previous version entries from KVStore", rollbackStates.size());
    }

    private void loadPreviousVersionEntry(AetherKey key, AetherValue value) {
        if (key instanceof PreviousVersionKey previousVersionKey &&
        value instanceof PreviousVersionValue previousVersionValue) {
            updateLocalPreviousVersion(previousVersionKey.artifactBase(), previousVersionValue);
        }
    }

    private void updateLocalPreviousVersion(ArtifactBase artifactBase, PreviousVersionValue value) {
        rollbackStates.compute(artifactBase, (_, existing) -> computePreviousVersionState(existing, value));
    }

    private RollbackState computePreviousVersionState(RollbackState existing, PreviousVersionValue value) {
        if (existing == null) {
            return new RollbackState(Option.some(value.previousVersion()),
                                     value.currentVersion());
        }
        existing.previousVersion = Option.some(value.previousVersion());
        existing.currentVersion = value.currentVersion();
        return existing;
    }

    private void trackVersionChange(Artifact artifact) {
        if (!isLeader.get()) {
            return;
        }
        var artifactBase = artifact.base();
        var currentVersion = artifact.version();
        rollbackStates.compute(artifactBase,
                               (_, existing) -> computeVersionChangeState(existing,
                                                                          artifact,
                                                                          artifactBase,
                                                                          currentVersion));
    }

    private RollbackState computeVersionChangeState(RollbackState existing,
                                                    Artifact artifact,
                                                    ArtifactBase artifactBase,
                                                    Version currentVersion) {
        if (existing == null) {
            // First deployment, no previous version yet
            log.debug("First deployment of {}, no previous version to track", artifact);
            return new RollbackState(Option.none(), currentVersion);
        }
        if (!existing.currentVersion.equals(currentVersion)) {
            // Version changed, store previous version in KVStore
            var previousVersion = existing.currentVersion;
            log.info("Version change detected for {}: {} -> {}",
                     artifactBase,
                     previousVersion,
                     currentVersion);
            storePreviousVersion(artifactBase, previousVersion, currentVersion);
            existing.previousVersion = Option.some(previousVersion);
            existing.currentVersion = currentVersion;
        }
        return existing;
    }

    private void storePreviousVersion(ArtifactBase artifactBase, Version previousVersion, Version currentVersion) {
        var key = PreviousVersionKey.previousVersionKey(artifactBase);
        var value = PreviousVersionValue.previousVersionValue(artifactBase, previousVersion, currentVersion);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        cluster.apply(List.of(command))
               .onSuccess(_ -> log.debug("Stored previous version {} for {} in KVStore", previousVersion, artifactBase))
               .onFailure(cause -> log.error("Failed to store previous version for {}: {}",
                                             artifactBase,
                                             cause.message()));
    }

    private void initiateRollback(Artifact failedArtifact, RollbackState state, String requestId) {
        // Check if previous version exists
        if (state.previousVersion.isEmpty()) {
            log.warn("[requestId={}] No previous version available for {}, cannot rollback", requestId, failedArtifact);
            return;
        }
        var previousVersion = state.previousVersion.unwrap();
        // Check cooldown
        var now = System.currentTimeMillis();
        var cooldownMs = config.cooldownSeconds() * 1000L;
        if (state.lastRollbackTimestamp > 0 && (now - state.lastRollbackTimestamp) < cooldownMs) {
            var remainingSeconds = (cooldownMs - (now - state.lastRollbackTimestamp)) / 1000;
            log.warn("[requestId={}] Rollback cooldown active for {}, {} seconds remaining. Skipping rollback.",
                     requestId,
                     failedArtifact,
                     remainingSeconds);
            return;
        }
        // Check max rollbacks
        if (state.rollbackCount >= config.maxRollbacks()) {
            log.error("[requestId={}] CRITICAL: Max rollbacks ({}) exceeded for {}. Manual intervention required.",
                      requestId,
                      config.maxRollbacks(),
                      failedArtifact);
            return;
        }
        // Perform rollback
        var rollbackArtifact = Artifact.artifact(failedArtifact.base(), previousVersion);
        log.warn("[requestId={}] INITIATING ROLLBACK: {} -> {} (rollback #{} of max {})",
                 requestId,
                 failedArtifact,
                 rollbackArtifact,
                 state.rollbackCount + 1,
                 config.maxRollbacks());
        updateBlueprintForRollback(rollbackArtifact, state, failedArtifact.version(), previousVersion, requestId);
    }

    private void updateBlueprintForRollback(Artifact rollbackArtifact,
                                            RollbackState state,
                                            Version failedVersion,
                                            Version previousVersion,
                                            String requestId) {
        // Get current instance count from existing blueprint
        var existingBlueprint = kvStore.snapshot()
                                       .entrySet()
                                       .stream()
                                       .filter(e -> e.getKey() instanceof BlueprintKey bk &&
        bk.artifact()
          .base()
          .equals(rollbackArtifact.base()))
                                       .findFirst();
        var instanceCount = existingBlueprint.map(e -> ((BlueprintValue) e.getValue()).instanceCount())
                                             .orElse(1L);
        var key = new BlueprintKey(rollbackArtifact);
        var value = new BlueprintValue(instanceCount);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        cluster.apply(List.of(command))
               .onSuccess(_ -> {
                              state.rollbackCount++;
                              state.lastRollbackTimestamp = System.currentTimeMillis();
                              state.lastRolledBackFrom = Option.some(failedVersion);
                              state.lastRolledBackTo = Option.some(previousVersion);
                              // Update current version to the rollback version
        state.currentVersion = previousVersion;
                              // Previous version becomes the one before that (if any)
        // For simplicity, we clear it - next deployment will set it again
        state.previousVersion = Option.none();
                              log.info("[requestId={}] ROLLBACK INITIATED: Blueprint updated to {} with {} instances",
                                       requestId,
                                       rollbackArtifact,
                                       instanceCount);
                          })
               .onFailure(cause -> log.error("[requestId={}] ROLLBACK FAILED: Could not update blueprint for {}: {}",
                                             requestId,
                                             rollbackArtifact,
                                             cause.message()));
    }

    /**
     * Mutable state for rollback tracking per artifact.
     */
    private static class RollbackState {
        Option<Version> previousVersion;
        Version currentVersion;
        int rollbackCount;
        long lastRollbackTimestamp;
        Option<Version> lastRolledBackFrom;
        Option<Version> lastRolledBackTo;

        RollbackState(Option<Version> previousVersion, Version currentVersion) {
            this.previousVersion = previousVersion;
            this.currentVersion = currentVersion;
            this.rollbackCount = 0;
            this.lastRollbackTimestamp = 0;
            this.lastRolledBackFrom = Option.none();
            this.lastRolledBackTo = Option.none();
        }
    }
}

/**
 * No-op implementation when rollback is disabled.
 */
class NoOpRollbackManager implements RollbackManager {
    private static final Logger log = LoggerFactory.getLogger(NoOpRollbackManager.class);

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {}

    @Override
    public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {}

    @Override
    public void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event) {
        log.debug("Rollback disabled, ignoring AllInstancesFailed for {}", event.artifact());
    }

    @Override
    public Option<RollbackStats> getStats(ArtifactBase artifactBase) {
        return Option.none();
    }

    @Override
    public void resetRollbackCount(ArtifactBase artifactBase) {}
}
