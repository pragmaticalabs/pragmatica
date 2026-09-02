// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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

    public record InvocationTimeouts(TimeSpan timeout,
                                     TimeSpan invokerTimeout,
                                     TimeSpan retryBaseDelay,
                                     int maxRetries) {
        public static InvocationTimeouts invocationTimeouts() {
            return new InvocationTimeouts(timeSpan(15).seconds(), timeSpan(20).seconds(), timeSpan(100).millis(), 3);
        }
    }

    /// `requestBudget` / `managementRequestBudget` are the per-request deadline budgets minted when
    /// a client request enters the app HTTP server or a management request is forwarded off-node.
    /// The layers that consume it — each taking `min(own default, remaining)` — are: forward hops
    /// (share of remaining per attempt, wire-propagated so a receiver drops an abandoned request),
    /// entity owner-forwards, and remote stream read/publish ack waits, including the shared
    /// forward-publish retry ladder. The invocation-layer timeouts above ([InvocationTimeouts]:
    /// east-west slice invocation, 15s/20s) do NOT yet consume the budget — capping them needs
    /// budget propagation on `InvokeRequest`, recorded as follow-up work. Deadlock of stacked
    /// constants is what the budget removes: 02w measured 5s hops × 30s entity forwards × harness
    /// sweeps burning minutes per operation against a 30s client. Defaults sized under the
    /// integration harness's 30s curl cap (owner ruling 2026-08-24: 10s app / 10s management).
    public record ForwardingTimeouts(TimeSpan retryDelay,
                                     int maxRetries,
                                     TimeSpan appTimeout,
                                     TimeSpan managementTimeout,
                                     TimeSpan requestBudget,
                                     TimeSpan managementRequestBudget) {
        public static ForwardingTimeouts forwardingTimeouts() {
            return new ForwardingTimeouts(timeSpan(200).millis(),
                                          3,
                                          timeSpan(5).seconds(),
                                          timeSpan(5).seconds(),
                                          timeSpan(10).seconds(),
                                          timeSpan(10).seconds());
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

    /// `coreAbsence` and `communityAbsence` are the two halves of ONE mechanism (#590) and their
    /// ordering is a correctness invariant, not a tuning preference.
    ///
    /// The LEADER broadcasts a `ClusterSyncPing` to the whole cluster every `pingInterval` and every
    /// live node answers with a pong, so that one exchange carries liveness in BOTH directions. A node
    /// that has seen no term-accepted ping for `coreAbsence` fences itself locally; the core stops
    /// counting a member it has had no pong from for `communityAbsence` and re-places its community's
    /// slices. (The signal is the leader's broadcast, NOT `SpokesmanPingLoop`'s governor-targeted ping:
    /// that loop only activates once a spokesman role is assigned, and nothing currently assigns one.)
    ///
    /// **`coreAbsence` MUST be strictly less than `communityAbsence`** — checked by `ConfigValidator`
    /// via [ClusterTimeouts#absenceWindowsOrdered]. If the core re-placed a community's work before
    /// that community stopped serving it, both would be live on the same slices at once. The gap
    /// between them is the safety margin for that hand-off.
    ///
    /// The defaults are multiples of the 1s `pingInterval`: 10s to fence, 20s to re-place. `coreAbsence`
    /// has a second floor to clear — pings originate from the leader, so a leader election is a
    /// legitimate ping gap, and a value below worst-case election time dissolves healthy communities
    /// during a routine election.
    public record ClusterTimeouts(TimeSpan hello,
                                  TimeSpan reconciliationInterval,
                                  TimeSpan pingInterval,
                                  TimeSpan channelProtection,
                                  TimeSpan coreAbsence,
                                  TimeSpan communityAbsence) {
        public static ClusterTimeouts clusterTimeouts() {
            return new ClusterTimeouts(timeSpan(5).seconds(),
                                       timeSpan(5).seconds(),
                                       timeSpan(1).seconds(),
                                       timeSpan(15).seconds(),
                                       timeSpan(10).seconds(),
                                       timeSpan(20).seconds());
        }

        /// The #590 ordering invariant as a predicate, so the one comparison that matters lives beside
        /// the two fields it relates. Reported as a validation error by `ConfigValidator` rather than
        /// enforced by a throwing factory: an operator-supplied value that is merely wrong belongs in
        /// the collected-errors report with every other config problem, not as an exception that hides
        /// the rest.
        public boolean absenceWindowsOrdered() {
            return coreAbsence.nanos() < communityAbsence.nanos();
        }
    }

    public record ConsensusTimeouts(TimeSpan syncRetryInterval,
                                    TimeSpan cleanupInterval,
                                    TimeSpan proposalTimeout,
                                    TimeSpan phaseStallCheck,
                                    TimeSpan gitPersistence) {
        public static ConsensusTimeouts consensusTimeouts() {
            return new ConsensusTimeouts(timeSpan(5).seconds(),
                                         timeSpan(60).seconds(),
                                         timeSpan(8).seconds(),
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
            return new SwimTimeouts(timeSpan(1).seconds(), timeSpan(500).millis(), timeSpan(10).seconds());
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
            // operation=30s aligns with `DHTConfig.DEFAULT_TIMEOUT` (raised
            // from 10s to give the `/api/blueprints/deploy` chain — which
            // hits `dht.get` via BuiltinRepository — enough headroom during
            // cluster bootstrap + parallel-suite-load. See DHTConfig javadoc.
            return new DhtTimeouts(timeSpan(30).seconds(), timeSpan(30).seconds());
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
