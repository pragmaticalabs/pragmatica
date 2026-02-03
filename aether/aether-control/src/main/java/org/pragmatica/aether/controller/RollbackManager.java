package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
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

    // --- Nested types ---
    /**
     * Immutable rollback state for a single artifact.
     * All state transitions return new instances.
     */
    record RollbackState(ArtifactBase artifactBase,
                         Option<Version> previousVersion,
                         Version currentVersion,
                         int rollbackCount,
                         long lastRollbackTimestamp,
                         Option<Version> lastRolledBackFrom,
                         Option<Version> lastRolledBackTo) {
        /**
         * Create initial state for first deployment (no previous version).
         */
        public static RollbackState initial(ArtifactBase artifactBase, Version currentVersion) {
            return new RollbackState(artifactBase, Option.none(), currentVersion, 0, 0, Option.none(), Option.none());
        }

        /**
         * Create state from KVStore data.
         */
        public static RollbackState fromKVStore(ArtifactBase artifactBase,
                                                Version previousVersion,
                                                Version currentVersion) {
            return new RollbackState(artifactBase,
                                     Option.some(previousVersion),
                                     currentVersion,
                                     0,
                                     0,
                                     Option.none(),
                                     Option.none());
        }

        /**
         * Create new state with version change tracked.
         * The current version becomes the previous version.
         */
        public RollbackState withVersionChange(Version newVersion) {
            return new RollbackState(artifactBase,
                                     Option.some(currentVersion),
                                     newVersion,
                                     rollbackCount,
                                     lastRollbackTimestamp,
                                     lastRolledBackFrom,
                                     lastRolledBackTo);
        }

        /**
         * Create new state after rollback completed.
         * Clears previous version (next deployment will set it again).
         */
        public RollbackState withRollbackCompleted(Version failedVersion, Version targetVersion, long timestamp) {
            return new RollbackState(artifactBase,
                                     Option.none(),
                                     targetVersion,
                                     rollbackCount + 1,
                                     timestamp,
                                     Option.some(failedVersion),
                                     Option.some(targetVersion));
        }

        /**
         * Create new state with counters reset.
         */
        public RollbackState withReset() {
            return new RollbackState(artifactBase, previousVersion, currentVersion, 0, 0, Option.none(), Option.none());
        }

        /**
         * Create new state from KVStore update (preserves rollback counters).
         */
        public RollbackState withKVStoreUpdate(Version previous, Version current) {
            return new RollbackState(artifactBase,
                                     Option.some(previous),
                                     current,
                                     rollbackCount,
                                     lastRollbackTimestamp,
                                     lastRolledBackFrom,
                                     lastRolledBackTo);
        }

        /**
         * Check if rollback is allowed and return decision if so.
         */
        public Result<RollbackDecision> canRollback(RollbackConfig config, long currentTime) {
            if (previousVersion.isEmpty()) {
                return RollbackError.General.NO_PREVIOUS_VERSION.result();
            }
            var cooldownMs = config.cooldownSeconds() * 1000L;
            if (lastRollbackTimestamp > 0 && (currentTime - lastRollbackTimestamp) < cooldownMs) {
                return RollbackError.General.COOLDOWN_ACTIVE.result();
            }
            if (rollbackCount >= config.maxRollbacks()) {
                return RollbackError.General.MAX_ROLLBACKS_EXCEEDED.result();
            }
            return Result.success(RollbackDecision.rollbackDecision(previousVersion.unwrap(),
                                                                    currentVersion,
                                                                    rollbackCount + 1));
        }

        /**
         * Convert to stats record.
         */
        public RollbackStats toStats() {
            return new RollbackStats(artifactBase,
                                     rollbackCount,
                                     lastRollbackTimestamp,
                                     lastRolledBackFrom,
                                     lastRolledBackTo);
        }
    }

    /**
     * Validated rollback decision. Created only when all preconditions are met.
     */
    record RollbackDecision(Version targetVersion,
                            Version failedVersion,
                            int rollbackNumber) {
        public static RollbackDecision rollbackDecision(Version target, Version failed, int num) {
            return new RollbackDecision(target, failed, num);
        }
    }

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

    /**
     * Create a rollback manager with local record implementation.
     */
    static RollbackManager rollbackManager(NodeId self,
                                           RollbackConfig config,
                                           ClusterNode<KVCommand<AetherKey>> cluster,
                                           KVStore<AetherKey, AetherValue> kvStore) {
        record rollbackManager(NodeId self,
                               RollbackConfig config,
                               ClusterNode<KVCommand<AetherKey>> cluster,
                               KVStore<AetherKey, AetherValue> kvStore,
                               AtomicBoolean isLeader,
                               ConcurrentHashMap<ArtifactBase, RollbackState> rollbackStates,
                               Logger log) implements RollbackManager {
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
                    case SliceTargetKey sliceTargetKey when value instanceof SliceTargetValue sliceTargetValue ->
                    trackVersionChange(sliceTargetKey.artifactBase(), sliceTargetValue);
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
                Option.option(rollbackStates.get(artifactBase))
                      .onPresent(state -> initiateRollback(event.artifact(),
                                                           state,
                                                           event.requestId()))
                      .onEmpty(() -> log.warn("[requestId={}] No previous version tracked for {}, cannot rollback",
                                              event.requestId(),
                                              event.artifact()));
            }

            @Override
            public Option<RollbackStats> getStats(ArtifactBase artifactBase) {
                return Option.option(rollbackStates.get(artifactBase))
                             .map(RollbackState::toStats);
            }

            @Override
            public void resetRollbackCount(ArtifactBase artifactBase) {
                rollbackStates.computeIfPresent(artifactBase,
                                                (_, state) -> {
                                                    log.info("Rollback count reset for {}", artifactBase);
                                                    return state.withReset();
                                                });
            }

            @SuppressWarnings("rawtypes")
            private void loadPreviousVersionsFromKvStore() {
                // Use raw forEach to avoid ClassCastException - KV store may contain LeaderKey entries
                ((Map) kvStore.snapshot()).forEach((key, value) -> {
                                                       if (key instanceof AetherKey aetherKey && value instanceof AetherValue aetherValue) {
                                                           loadPreviousVersionEntry(aetherKey, aetherValue);
                                                       }
                                                   });
                log.debug("Loaded {} previous version entries from KVStore", rollbackStates.size());
            }

            private void loadPreviousVersionEntry(AetherKey key, AetherValue value) {
                if (key instanceof PreviousVersionKey previousVersionKey &&
                value instanceof PreviousVersionValue previousVersionValue) {
                    updateLocalPreviousVersion(previousVersionKey.artifactBase(), previousVersionValue);
                }
            }

            private void updateLocalPreviousVersion(ArtifactBase artifactBase, PreviousVersionValue value) {
                rollbackStates.compute(artifactBase,
                                       (ab, existing) -> Option.option(existing)
                                                               .map(state -> state.withKVStoreUpdate(value.previousVersion(),
                                                                                                     value.currentVersion()))
                                                               .or(() -> RollbackState.fromKVStore(ab,
                                                                                                   value.previousVersion(),
                                                                                                   value.currentVersion())));
            }

            private void trackVersionChange(ArtifactBase artifactBase, SliceTargetValue sliceTargetValue) {
                if (!isLeader.get()) {
                    return;
                }
                var currentVersion = sliceTargetValue.currentVersion();
                rollbackStates.compute(artifactBase,
                                       (ab, existing) -> Option.option(existing)
                                                               .map(state -> computeVersionChange(state,
                                                                                                  artifactBase,
                                                                                                  currentVersion))
                                                               .or(() -> {
                                                                       log.debug("First deployment of {}, no previous version to track",
                                                                                 artifactBase);
                                                                       return RollbackState.initial(ab, currentVersion);
                                                                   }));
            }

            private RollbackState computeVersionChange(RollbackState state,
                                                       ArtifactBase artifactBase,
                                                       Version newVersion) {
                if (state.currentVersion()
                         .equals(newVersion)) {
                    return state;
                }
                var previousVersion = state.currentVersion();
                log.info("Version change detected for {}: {} -> {}",
                         artifactBase,
                         previousVersion,
                         newVersion);
                storePreviousVersion(artifactBase, previousVersion, newVersion);
                return state.withVersionChange(newVersion);
            }

            private void storePreviousVersion(ArtifactBase artifactBase,
                                              Version previousVersion,
                                              Version currentVersion) {
                var key = PreviousVersionKey.previousVersionKey(artifactBase);
                var value = PreviousVersionValue.previousVersionValue(artifactBase, previousVersion, currentVersion);
                var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
                cluster.apply(List.of(command))
                       .onSuccess(_ -> log.debug("Stored previous version {} for {} in KVStore",
                                                 previousVersion,
                                                 artifactBase))
                       .onFailure(cause -> log.error("Failed to store previous version for {}: {}",
                                                     artifactBase,
                                                     cause.message()));
            }

            private void initiateRollback(Artifact failedArtifact, RollbackState state, String requestId) {
                var now = System.currentTimeMillis();
                state.canRollback(config, now)
                     .onFailure(cause -> logRollbackSkipped(cause, requestId, failedArtifact))
                     .onSuccess(decision -> executeRollback(failedArtifact, decision, requestId));
            }

            private void logRollbackSkipped(Cause cause, String requestId, Artifact artifact) {
                switch (cause) {
                    case RollbackError.General.NO_PREVIOUS_VERSION ->
                    log.warn("[requestId={}] No previous version available for {}, cannot rollback", requestId, artifact);
                    case RollbackError.General.COOLDOWN_ACTIVE ->
                    log.warn("[requestId={}] Rollback cooldown active for {}. Skipping rollback.", requestId, artifact);
                    case RollbackError.General.MAX_ROLLBACKS_EXCEEDED ->
                    log.error("[requestId={}] CRITICAL: Max rollbacks ({}) exceeded for {}. Manual intervention required.",
                              requestId,
                              config.maxRollbacks(),
                              artifact);
                    default ->
                    log.warn("[requestId={}] Rollback skipped for {}: {}", requestId, artifact, cause.message());
                }
            }

            private void executeRollback(Artifact failedArtifact, RollbackDecision decision, String requestId) {
                var rollbackArtifact = Artifact.artifact(failedArtifact.base(), decision.targetVersion());
                log.warn("[requestId={}] INITIATING ROLLBACK: {} -> {} (rollback #{} of max {})",
                         requestId,
                         failedArtifact,
                         rollbackArtifact,
                         decision.rollbackNumber(),
                         config.maxRollbacks());
                updateSliceTargetForRollback(rollbackArtifact, decision, requestId);
            }

            private void updateSliceTargetForRollback(Artifact rollbackArtifact,
                                                      RollbackDecision decision,
                                                      String requestId) {
                var artifactBase = rollbackArtifact.base();
                var existingTarget = kvStore.snapshot()
                                            .entrySet()
                                            .stream()
                                            .filter(e -> e.getKey() instanceof SliceTargetKey stk &&
                stk.artifactBase()
                   .equals(artifactBase))
                                            .findFirst();
                var instanceCount = existingTarget.map(e -> ((SliceTargetValue) e.getValue()).targetInstances())
                                                  .orElse(1);
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                var value = SliceTargetValue.sliceTargetValue(decision.targetVersion(), instanceCount);
                var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
                // Capture values needed for state update
                var failedVersion = decision.failedVersion();
                var targetVersion = decision.targetVersion();
                cluster.apply(List.of(command))
                       .onSuccess(_ -> {
                                      var timestamp = System.currentTimeMillis();
                                      rollbackStates.computeIfPresent(artifactBase,
                                                                      (_, state) -> state.withRollbackCompleted(failedVersion,
                                                                                                                targetVersion,
                                                                                                                timestamp));
                                      log.info("[requestId={}] ROLLBACK INITIATED: SliceTarget updated to {} with {} instances",
                                               requestId,
                                               rollbackArtifact,
                                               instanceCount);
                                  })
                       .onFailure(cause -> log.error("[requestId={}] ROLLBACK FAILED: Could not update slice target for {}: {}",
                                                     requestId,
                                                     rollbackArtifact,
                                                     cause.message()));
            }
        }
        var manager = new rollbackManager(self,
                                          config,
                                          cluster,
                                          kvStore,
                                          new AtomicBoolean(false),
                                          new ConcurrentHashMap<>(),
                                          LoggerFactory.getLogger(RollbackManager.class));
        // Initialize state from KVStore
        manager.loadPreviousVersionsFromKvStore();
        return manager;
    }

    /**
     * Create a no-op rollback manager when rollback is disabled.
     */
    static RollbackManager disabled() {
        return Disabled.INSTANCE;
    }

    /**
     * No-op implementation when rollback is disabled.
     */
    enum Disabled implements RollbackManager {
        INSTANCE;
        private static final Logger log = LoggerFactory.getLogger(RollbackManager.class);
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
}
