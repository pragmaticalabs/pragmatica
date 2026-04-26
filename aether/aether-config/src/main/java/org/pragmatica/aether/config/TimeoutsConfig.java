// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Centralized timeout configuration for all Aether subsystems.
/// All duration fields use TimeSpan; TOML values use human-readable strings ("5s", "500ms").
public record TimeoutsConfig(InvocationTimeouts invocation,
                             ForwardingTimeouts forwarding,
                             DeploymentTimeouts deployment,
                             RollingUpdateTimeouts rollingUpdate,
                             ClusterTimeouts cluster,
                             ConsensusTimeouts consensus,
                             ElectionTimeouts election,
                             SwimTimeouts swim,
                             ObservabilityTimeouts observability,
                             DhtTimeouts dht,
                             WorkerTimeouts worker,
                             SecurityTimeouts security,
                             RepositoryTimeouts repository,
                             ScalingTimeouts scaling) {
    public static TimeoutsConfig timeoutsConfig() {
        return new TimeoutsConfig(InvocationTimeouts.invocationTimeouts(),
                                  ForwardingTimeouts.forwardingTimeouts(),
                                  DeploymentTimeouts.deploymentTimeouts(),
                                  RollingUpdateTimeouts.rollingUpdateTimeouts(),
                                  ClusterTimeouts.clusterTimeouts(),
                                  ConsensusTimeouts.consensusTimeouts(),
                                  ElectionTimeouts.electionTimeouts(),
                                  SwimTimeouts.swimTimeouts(),
                                  ObservabilityTimeouts.observabilityTimeouts(),
                                  DhtTimeouts.dhtTimeouts(),
                                  WorkerTimeouts.workerTimeouts(),
                                  SecurityTimeouts.securityTimeouts(),
                                  RepositoryTimeouts.repositoryTimeouts(),
                                  ScalingTimeouts.scalingTimeouts());
    }

    /// User-visible RPC timeout layer.
    ///
    /// Theme M / M4 — layered-timeout invariant: `InvocationTimeouts.timeout` (default 15 s) is the
    /// **inner**, user-visible deadline for an in-flight invocation. It is intentionally shorter
    /// than the consensus-level retention window in [`ConsensusTimeouts`] (e.g. `cleanupInterval`
    /// 60 s) — by design, an invocation may fail at 15 s with a timeout error while the underlying
    /// consensus operation is still negotiating in the background. This trade-off favors fast user
    /// feedback over waiting for an answer that may eventually arrive but no longer has anyone to
    /// receive it. Increase this value only if your callers can tolerate matching latency budgets.
    public record InvocationTimeouts(TimeSpan timeout,
                                     TimeSpan invokerTimeout,
                                     TimeSpan retryBaseDelay,
                                     int maxRetries) {
        public static InvocationTimeouts invocationTimeouts() {
            return new InvocationTimeouts(timeSpan(15).seconds(), timeSpan(20).seconds(), timeSpan(100).millis(), 3);
        }
    }

    public record ForwardingTimeouts(TimeSpan retryDelay,
                                     int maxRetries,
                                     TimeSpan appTimeout,
                                     TimeSpan managementTimeout) {
        public static ForwardingTimeouts forwardingTimeouts() {
            return new ForwardingTimeouts(timeSpan(200).millis(), 3, timeSpan(5).seconds(), timeSpan(5).seconds());
        }
    }

    public record DeploymentTimeouts(TimeSpan loading,
                                     TimeSpan activating,
                                     TimeSpan deactivating,
                                     TimeSpan unloading,
                                     TimeSpan activationChain,
                                     TimeSpan transitionRetryDelay,
                                     TimeSpan reconciliationInterval,
                                     int maxLifecycleRetries) {
        public static DeploymentTimeouts deploymentTimeouts() {
            return new DeploymentTimeouts(timeSpan(2).minutes(),
                                          timeSpan(1).minutes(),
                                          timeSpan(30).seconds(),
                                          timeSpan(2).minutes(),
                                          timeSpan(90).seconds(),
                                          timeSpan(2).seconds(),
                                          timeSpan(30).seconds(),
                                          60);
        }
    }

    public record RollingUpdateTimeouts(TimeSpan kvOperation,
                                        TimeSpan terminalRetention,
                                        TimeSpan cleanupGracePeriod,
                                        TimeSpan rollbackCooldown) {
        public static RollingUpdateTimeouts rollingUpdateTimeouts() {
            return new RollingUpdateTimeouts(timeSpan(30).seconds(),
                                             timeSpan(1).hours(),
                                             timeSpan(5).minutes(),
                                             timeSpan(5).minutes());
        }
    }

    public record ClusterTimeouts(TimeSpan hello,
                                  TimeSpan reconciliationInterval,
                                  TimeSpan pingInterval,
                                  TimeSpan channelProtection) {
        public static ClusterTimeouts clusterTimeouts() {
            return new ClusterTimeouts(timeSpan(5).seconds(),
                                       timeSpan(5).seconds(),
                                       timeSpan(1).seconds(),
                                       timeSpan(15).seconds());
        }
    }

    /// Consensus-level (Rabia) retention and retry windows.
    ///
    /// Theme M / M4 — layered-timeout invariant: this is the **outer** layer wrapping
    /// [`InvocationTimeouts`]. `cleanupInterval` (default 60 s) governs how long consensus retains
    /// state for an in-flight proposal, well past the user-visible `InvocationTimeouts.timeout`
    /// (default 15 s). The intent is for the user-facing call to fail fast at 15 s with a clear
    /// timeout error, while consensus continues negotiating in the background — eventually
    /// committing or being garbage-collected at the 60 s mark. This is BY DESIGN: surfacing fast
    /// feedback to the caller takes precedence over waiting indefinitely for a slow quorum. Tune
    /// these values together if you change either.
    public record ConsensusTimeouts(TimeSpan syncRetryInterval,
                                    TimeSpan cleanupInterval,
                                    TimeSpan proposalTimeout,
                                    TimeSpan phaseStallCheck,
                                    TimeSpan gitPersistence) {
        public static ConsensusTimeouts consensusTimeouts() {
            return new ConsensusTimeouts(timeSpan(5).seconds(),
                                         timeSpan(60).seconds(),
                                         timeSpan(3).seconds(),
                                         timeSpan(500).millis(),
                                         timeSpan(30).seconds());
        }
    }

    public record ElectionTimeouts(TimeSpan baseDelay, TimeSpan perRankDelay, TimeSpan retryDelay) {
        public static ElectionTimeouts electionTimeouts() {
            return new ElectionTimeouts(timeSpan(2).seconds(), timeSpan(1).seconds(), timeSpan(500).millis());
        }
    }

    public record SwimTimeouts(TimeSpan period, TimeSpan probeTimeout, TimeSpan suspectTimeout) {
        public static SwimTimeouts swimTimeouts() {
            return new SwimTimeouts(timeSpan(1).seconds(), timeSpan(500).millis(), timeSpan(5).seconds());
        }
    }

    public record ObservabilityTimeouts(TimeSpan dashboardBroadcast,
                                        TimeSpan metricsSlidingWindow,
                                        TimeSpan eventLoopProbe,
                                        TimeSpan samplerRecalculation,
                                        TimeSpan invocationCleanup,
                                        int traceStoreCapacity,
                                        int alertHistorySize) {
        public static ObservabilityTimeouts observabilityTimeouts() {
            return new ObservabilityTimeouts(timeSpan(1).seconds(),
                                             timeSpan(2).hours(),
                                             timeSpan(100).millis(),
                                             timeSpan(5).seconds(),
                                             timeSpan(60).seconds(),
                                             50_000,
                                             100);
        }
    }

    public record DhtTimeouts(TimeSpan operation, TimeSpan antiEntropyInterval) {
        public static DhtTimeouts dhtTimeouts() {
            return new DhtTimeouts(timeSpan(10).seconds(), timeSpan(30).seconds());
        }
    }

    public record WorkerTimeouts(TimeSpan heartbeatInterval, TimeSpan heartbeatTimeout, TimeSpan metricsAggregation) {
        public static WorkerTimeouts workerTimeouts() {
            return new WorkerTimeouts(timeSpan(500).millis(), timeSpan(2).seconds(), timeSpan(5).seconds());
        }
    }

    public record SecurityTimeouts(TimeSpan websocketAuth, TimeSpan dnsQuery, TimeSpan certRenewalRetry) {
        public static SecurityTimeouts securityTimeouts() {
            return new SecurityTimeouts(timeSpan(5).seconds(), timeSpan(10).seconds(), timeSpan(1).hours());
        }
    }

    public record RepositoryTimeouts(TimeSpan httpTimeout, TimeSpan locateTimeout) {
        public static RepositoryTimeouts repositoryTimeouts() {
            return new RepositoryTimeouts(timeSpan(30).seconds(), timeSpan(10).seconds());
        }
    }

    public record ScalingTimeouts(TimeSpan evaluationInterval,
                                  TimeSpan warmupPeriod,
                                  TimeSpan sliceCooldown,
                                  TimeSpan communityCooldown,
                                  TimeSpan autoHealRetry,
                                  TimeSpan autoHealStartupCooldown) {
        public static ScalingTimeouts scalingTimeouts() {
            return new ScalingTimeouts(timeSpan(1).seconds(),
                                       timeSpan(30).seconds(),
                                       timeSpan(10).seconds(),
                                       timeSpan(60).seconds(),
                                       timeSpan(10).seconds(),
                                       timeSpan(15).seconds());
        }
    }
}
