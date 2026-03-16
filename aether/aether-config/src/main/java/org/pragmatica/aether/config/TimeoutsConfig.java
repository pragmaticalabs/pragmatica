package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Centralized timeout configuration for all Aether subsystems.
/// All duration fields use TimeSpan; TOML values use human-readable strings ("5s", "500ms").
public record TimeoutsConfig(
    InvocationTimeouts invocation,
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
        return new TimeoutsConfig(
            InvocationTimeouts.invocationTimeouts(),
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

    // --- Nested records ---

    public record InvocationTimeouts(
        TimeSpan timeout,
        TimeSpan invokerTimeout,
        TimeSpan retryBaseDelay,
        int maxRetries) {
        public static InvocationTimeouts invocationTimeouts() {
            return new InvocationTimeouts(
                timeSpan(25).seconds(),
                timeSpan(30).seconds(),
                timeSpan(100).millis(),
                3);
        }
    }

    public record ForwardingTimeouts(
        TimeSpan retryDelay,
        int maxRetries) {
        public static ForwardingTimeouts forwardingTimeouts() {
            return new ForwardingTimeouts(timeSpan(200).millis(), 3);
        }
    }

    public record DeploymentTimeouts(
        TimeSpan loading,
        TimeSpan activating,
        TimeSpan deactivating,
        TimeSpan unloading,
        TimeSpan activationChain,
        TimeSpan transitionRetryDelay,
        int maxLifecycleRetries) {
        public static DeploymentTimeouts deploymentTimeouts() {
            return new DeploymentTimeouts(
                timeSpan(2).minutes(),
                timeSpan(1).minutes(),
                timeSpan(30).seconds(),
                timeSpan(2).minutes(),
                timeSpan(2).minutes(),
                timeSpan(2).seconds(),
                60);
        }
    }

    public record RollingUpdateTimeouts(
        TimeSpan kvOperation,
        TimeSpan terminalRetention,
        TimeSpan cleanupGracePeriod,
        TimeSpan rollbackCooldown) {
        public static RollingUpdateTimeouts rollingUpdateTimeouts() {
            return new RollingUpdateTimeouts(
                timeSpan(30).seconds(),
                timeSpan(1).hours(),
                timeSpan(5).minutes(),
                timeSpan(5).minutes());
        }
    }

    public record ClusterTimeouts(
        TimeSpan hello,
        TimeSpan reconciliationInterval,
        TimeSpan pingInterval,
        TimeSpan channelProtection) {
        public static ClusterTimeouts clusterTimeouts() {
            return new ClusterTimeouts(
                timeSpan(5).seconds(),
                timeSpan(5).seconds(),
                timeSpan(1).seconds(),
                timeSpan(15).seconds());
        }
    }

    public record ConsensusTimeouts(
        TimeSpan syncRetryInterval,
        TimeSpan cleanupInterval,
        TimeSpan proposalTimeout,
        TimeSpan phaseStallCheck,
        TimeSpan gitPersistence) {
        public static ConsensusTimeouts consensusTimeouts() {
            return new ConsensusTimeouts(
                timeSpan(5).seconds(),
                timeSpan(60).seconds(),
                timeSpan(3).seconds(),
                timeSpan(500).millis(),
                timeSpan(30).seconds());
        }
    }

    public record ElectionTimeouts(
        TimeSpan baseDelay,
        TimeSpan perRankDelay,
        TimeSpan retryDelay) {
        public static ElectionTimeouts electionTimeouts() {
            return new ElectionTimeouts(
                timeSpan(2).seconds(),
                timeSpan(1).seconds(),
                timeSpan(500).millis());
        }
    }

    public record SwimTimeouts(
        TimeSpan period,
        TimeSpan probeTimeout,
        TimeSpan suspectTimeout) {
        public static SwimTimeouts swimTimeouts() {
            return new SwimTimeouts(
                timeSpan(1).seconds(),
                timeSpan(500).millis(),
                timeSpan(5).seconds());
        }
    }

    public record ObservabilityTimeouts(
        TimeSpan dashboardBroadcast,
        TimeSpan metricsSlidingWindow,
        TimeSpan eventLoopProbe,
        TimeSpan samplerRecalculation,
        TimeSpan invocationCleanup,
        int traceStoreCapacity,
        int alertHistorySize) {
        public static ObservabilityTimeouts observabilityTimeouts() {
            return new ObservabilityTimeouts(
                timeSpan(1).seconds(),
                timeSpan(2).hours(),
                timeSpan(100).millis(),
                timeSpan(5).seconds(),
                timeSpan(60).seconds(),
                50_000,
                100);
        }
    }

    public record DhtTimeouts(
        TimeSpan operation,
        TimeSpan antiEntropyInterval) {
        public static DhtTimeouts dhtTimeouts() {
            return new DhtTimeouts(
                timeSpan(10).seconds(),
                timeSpan(30).seconds());
        }
    }

    public record WorkerTimeouts(
        TimeSpan heartbeatInterval,
        TimeSpan heartbeatTimeout,
        TimeSpan metricsAggregation) {
        public static WorkerTimeouts workerTimeouts() {
            return new WorkerTimeouts(
                timeSpan(500).millis(),
                timeSpan(2).seconds(),
                timeSpan(5).seconds());
        }
    }

    public record SecurityTimeouts(
        TimeSpan websocketAuth,
        TimeSpan dnsQuery,
        TimeSpan certRenewalRetry) {
        public static SecurityTimeouts securityTimeouts() {
            return new SecurityTimeouts(
                timeSpan(5).seconds(),
                timeSpan(10).seconds(),
                timeSpan(1).hours());
        }
    }

    public record RepositoryTimeouts(
        TimeSpan httpTimeout,
        TimeSpan locateTimeout) {
        public static RepositoryTimeouts repositoryTimeouts() {
            return new RepositoryTimeouts(
                timeSpan(30).seconds(),
                timeSpan(30).seconds());
        }
    }

    public record ScalingTimeouts(
        TimeSpan evaluationInterval,
        TimeSpan warmupPeriod,
        TimeSpan sliceCooldown,
        TimeSpan communityCooldown,
        TimeSpan autoHealRetry,
        TimeSpan autoHealStartupCooldown) {
        public static ScalingTimeouts scalingTimeouts() {
            return new ScalingTimeouts(
                timeSpan(1).seconds(),
                timeSpan(30).seconds(),
                timeSpan(10).seconds(),
                timeSpan(60).seconds(),
                timeSpan(10).seconds(),
                timeSpan(15).seconds());
        }
    }
}
