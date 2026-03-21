package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ABTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ABTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.KSUID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages A/B test deployment operations across the cluster.
///
///
/// A/B tests deploy multiple variant versions alongside a baseline and route
/// traffic deterministically using configurable split rules. Unlike canary
/// deployments, A/B tests have no automatic evaluation loop and run until
/// manually concluded with a declared winning variant.
///
///
/// A/B test state is stored in the KV-Store for persistence and visibility.
/// Only the leader node can create, conclude, or rollback tests.
public interface ABTestManager {
    /// Create a new A/B test deployment.
    Promise<ABTestDeployment> createTest(ArtifactBase artifactBase,
                                         Map<String, Version> variantVersions,
                                         SplitRule splitRule);

    /// Conclude the test by promoting a winning variant.
    Promise<ABTestDeployment> concludeTest(String testId, String winningVariant);

    /// Rollback the test to baseline.
    Promise<ABTestDeployment> rollbackTest(String testId);

    /// Get A/B test by ID.
    Option<ABTestDeployment> getTest(String testId);

    /// Get active A/B test for an artifact.
    Option<ABTestDeployment> getActiveTest(ArtifactBase artifactBase);

    /// List all active A/B tests.
    List<ABTestDeployment> activeTests();

    /// List all A/B tests.
    List<ABTestDeployment> allTests();

    /// Get metrics for an A/B test.
    ABTestMetrics getMetrics(String testId);

    /// Handle leader change (restore state on promotion).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onLeaderChange(LeaderChange leaderChange);

    /// Handle deployment failure for auto-rollback.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentFailed(DeploymentEvent.DeploymentFailed event);

    /// Default KV operation timeout.
    TimeSpan DEFAULT_KV_OPERATION_TIMEOUT = TimeSpan.timeSpan(30)
                                                   .seconds();

    /// Default terminal retention (1 hour).
    long DEFAULT_TERMINAL_RETENTION_MS = TimeUnit.HOURS.toMillis(1);

    /// Factory method following JBCT naming convention.
    static ABTestManager abTestManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       InvocationMetricsCollector metricsCollector) {
        return abTestManager(clusterNode,
                             kvStore,
                             metricsCollector,
                             DEFAULT_KV_OPERATION_TIMEOUT,
                             DEFAULT_TERMINAL_RETENTION_MS);
    }

    /// Factory method with custom settings.
    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-NAM-01"})
    static ABTestManager abTestManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       InvocationMetricsCollector metricsCollector,
                                       TimeSpan kvOperationTimeout,
                                       long terminalRetentionMs) {
        record abTestManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                             KVStore<AetherKey, AetherValue> kvStore,
                             InvocationMetricsCollector metricsCollector,
                             TimeSpan kvOperationTimeout,
                             long terminalRetentionMs,
                             Map<String, ABTestDeployment> tests) implements ABTestManager {
            private static final Logger log = LoggerFactory.getLogger(ABTestManager.class);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("A/B test manager active (leader)");
                    restoreState();
                } else {
                    log.info("A/B test manager passive (follower)");
                }
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {
                var artifactBase = event.artifact()
                                        .base();
                getActiveTest(artifactBase).filter(test -> containsVersion(test,
                                                                           event.artifact()
                                                                                .version()))
                             .filter(ABTestDeployment::isActive)
                             .onPresent(test -> triggerAutoRollback(test, event));
            }

            private boolean containsVersion(ABTestDeployment test, Version version) {
                return test.variantVersions()
                           .containsValue(version);
            }

            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void triggerAutoRollback(ABTestDeployment test, DeploymentEvent.DeploymentFailed event) {
                log.warn("Auto-rollback triggered for A/B test {} — variant failed on node {}: {}",
                         test.testId(),
                         event.nodeId(),
                         event.errorMessage());
                rollbackTest(test.testId())
                .onFailure(cause -> log.error("Auto-rollback failed for A/B test {}: {}", test.testId(), cause.message()));
            }

            // --- Core operations ---
            @Override
            public Promise<ABTestDeployment> createTest(ArtifactBase artifactBase,
                                                        Map<String, Version> variantVersions,
                                                        SplitRule splitRule) {
                return requireLeader().flatMap(_ -> checkNoActiveTest(artifactBase))
                                    .flatMap(_ -> findCurrentVersion(artifactBase))
                                    .flatMap(baseline -> createAndDeployTest(artifactBase,
                                                                             baseline,
                                                                             variantVersions,
                                                                             splitRule));
            }

            @Override
            public Promise<ABTestDeployment> concludeTest(String testId, String winningVariant) {
                return requireLeader().flatMap(_ -> findTest(testId))
                                    .flatMap(test -> validateAndConclude(test, winningVariant));
            }

            @Override
            public Promise<ABTestDeployment> rollbackTest(String testId) {
                return requireLeader().flatMap(_ -> findTest(testId))
                                    .flatMap(this::validateAndRollback);
            }

            @Override
            public Option<ABTestDeployment> getTest(String testId) {
                return Option.option(tests.get(testId));
            }

            @Override
            public Option<ABTestDeployment> getActiveTest(ArtifactBase artifactBase) {
                return Option.from(tests.values()
                                        .stream()
                                        .filter(t -> t.artifactBase()
                                                      .equals(artifactBase) && t.isActive())
                                        .findFirst());
            }

            @Override
            public List<ABTestDeployment> activeTests() {
                return tests.values()
                            .stream()
                            .filter(ABTestDeployment::isActive)
                            .toList();
            }

            @Override
            public List<ABTestDeployment> allTests() {
                return List.copyOf(tests.values());
            }

            @Override
            public ABTestMetrics getMetrics(String testId) {
                return Option.option(tests.get(testId))
                             .map(this::collectMetrics)
                             .or(ABTestMetrics.abTestMetrics(testId,
                                                             Map.of()));
            }

            // --- Private helpers ---
            private Promise<Unit> requireLeader() {
                if (!clusterNode.leaderManager()
                                .isLeader()) {
                    return ABTestDeploymentError.NotLeader.INSTANCE.promise();
                }
                return Promise.success(Unit.unit());
            }

            private Promise<Unit> checkNoActiveTest(ArtifactBase artifactBase) {
                return getActiveTest(artifactBase).isPresent()
                       ? ABTestDeploymentError.TestAlreadyExists.testAlreadyExists(artifactBase)
                                              .promise()
                       : Promise.success(Unit.unit());
            }

            private Promise<ABTestDeployment> findTest(String testId) {
                return Option.option(tests.get(testId))
                             .toResult(ABTestDeploymentError.TestNotFound.testNotFound(testId))
                             .async();
            }

            private Promise<Version> findCurrentVersion(ArtifactBase artifactBase) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                return kvStore.get(key)
                              .map(value -> ((SliceTargetValue) value).currentVersion())
                              .toResult(ABTestDeploymentError.InitialDeployment.initialDeployment(artifactBase))
                              .async();
            }

            private Promise<ABTestDeployment> createAndDeployTest(ArtifactBase artifactBase,
                                                                  Version baseline,
                                                                  Map<String, Version> variantVersions,
                                                                  SplitRule splitRule) {
                var testId = KSUID.ksuid()
                                  .encoded();
                var test = ABTestDeployment.abTestDeployment(testId, artifactBase, baseline, variantVersions, splitRule);
                log.info("Starting A/B test {} for {} with {} variants (baseline: {})",
                         testId,
                         artifactBase,
                         variantVersions.size(),
                         baseline);
                tests.put(testId, test);
                return persistAndTransition(test, ABTestState.DEPLOYING_VARIANTS).flatMap(this::deployVariants);
            }

            @SuppressWarnings("unchecked")
            private Promise<ABTestDeployment> deployVariants(ABTestDeployment test) {
                var commands = test.variantVersions()
                                   .values()
                                   .stream()
                                   .map(version -> buildDeployCommand(test.artifactBase(),
                                                                      version))
                                   .toList();
                log.info("Deploying {} variant versions for A/B test {}", commands.size(), test.testId());
                return clusterNode.<Unit> apply(commands)
                                  .timeout(kvOperationTimeout)
                                  .flatMap(_ -> activateTest(test));
            }

            @SuppressWarnings("unchecked")
            private KVCommand<AetherKey> buildDeployCommand(ArtifactBase artifactBase, Version version) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                var value = new SliceTargetValue(version, 1, 1, Option.none(), "CORE_ONLY", System.currentTimeMillis());
                return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
            }

            private Promise<ABTestDeployment> activateTest(ABTestDeployment test) {
                var withRouting = test.withRouting(VersionRouting.ALL_NEW);
                tests.put(test.testId(), withRouting);
                return persistAndTransition(withRouting, ABTestState.ACTIVE);
            }

            private Promise<ABTestDeployment> validateAndConclude(ABTestDeployment test, String winningVariant) {
                if (test.state() != ABTestState.ACTIVE) {
                    return ABTestDeploymentError.InvalidTestState.invalidTestState(test.state(),
                                                                                   ABTestState.CONCLUDING)
                                                .promise();
                }
                if (!test.variantVersions()
                         .containsKey(winningVariant)) {
                    return ABTestDeploymentError.VariantNotFound.variantNotFound(test.testId(),
                                                                                 winningVariant)
                                                .promise();
                }
                log.info("Concluding A/B test {} — winner: {}", test.testId(), winningVariant);
                return test.transitionTo(ABTestState.CONCLUDING)
                           .async()
                           .flatMap(this::cacheAndPersistTest)
                           .flatMap(concluded -> promoteWinner(concluded, winningVariant));
            }

            @SuppressWarnings("unchecked")
            private Promise<ABTestDeployment> promoteWinner(ABTestDeployment test, String winningVariant) {
                var winnerVersion = test.variantVersions()
                                        .get(winningVariant);
                var key = SliceTargetKey.sliceTargetKey(test.artifactBase());
                var value = new SliceTargetValue(winnerVersion,
                                                 1,
                                                 1,
                                                 Option.none(),
                                                 "CORE_ONLY",
                                                 System.currentTimeMillis());
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .flatMap(_ -> persistAndTransition(test, ABTestState.COMPLETED));
            }

            private Promise<ABTestDeployment> validateAndRollback(ABTestDeployment test) {
                if (test.isTerminal()) {
                    return ABTestDeploymentError.InvalidTestState.invalidTestState(test.state(),
                                                                                   ABTestState.ROLLING_BACK)
                                                .promise();
                }
                log.info("Rolling back A/B test {}", test.testId());
                var withOldRouting = test.withRouting(VersionRouting.ALL_OLD);
                return withOldRouting.transitionTo(ABTestState.ROLLING_BACK)
                                     .async()
                                     .flatMap(this::cacheAndPersistTest)
                                     .flatMap(this::restoreBaseline);
            }

            @SuppressWarnings("unchecked")
            private Promise<ABTestDeployment> restoreBaseline(ABTestDeployment test) {
                log.info("Restoring baseline {} for A/B test {}", test.baselineVersion(), test.testId());
                var key = SliceTargetKey.sliceTargetKey(test.artifactBase());
                var value = new SliceTargetValue(test.baselineVersion(),
                                                 1,
                                                 1,
                                                 Option.none(),
                                                 "CORE_ONLY",
                                                 System.currentTimeMillis());
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .flatMap(_ -> persistAndTransition(test, ABTestState.ROLLED_BACK));
            }

            // --- Persistence ---
            private Promise<ABTestDeployment> persistAndTransition(ABTestDeployment test, ABTestState newState) {
                return test.transitionTo(newState)
                           .async()
                           .flatMap(this::cacheAndPersistTest);
            }

            @SuppressWarnings("unchecked")
            private Promise<ABTestDeployment> cacheAndPersistTest(ABTestDeployment test) {
                tests.put(test.testId(), test);
                if (test.isTerminal()) {
                    pruneTerminalTests();
                }
                var key = new ABTestKey(test.testId());
                var value = buildTestValue(test);
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .map(_ -> test);
            }

            private ABTestValue buildTestValue(ABTestDeployment test) {
                var variantsJson = serializeVariantVersions(test.variantVersions());
                var splitRuleJson = serializeSplitRule(test.splitRule());
                return new ABTestValue(test.testId(),
                                       test.artifactBase(),
                                       test.baselineVersion(),
                                       variantsJson,
                                       test.state()
                                           .name(),
                                       splitRuleJson,
                                       test.routing()
                                           .newWeight(),
                                       test.routing()
                                           .oldWeight(),
                                       test.blueprintId()
                                           .or(""),
                                       test.createdAt(),
                                       System.currentTimeMillis());
            }

            // --- State restoration ---
            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void restoreState() {
                int beforeCount = tests.size();
                kvStore.forEach(ABTestKey.class, ABTestValue.class, (key, value) -> restoreTest(value));
                int restoredCount = tests.size() - beforeCount;
                if (restoredCount > 0) {
                    log.info("Restored {} A/B tests from KV-Store", restoredCount);
                }
            }

            @SuppressWarnings({"JBCT-VO-02", "JBCT-RET-01"}) // Side-effect helper — void inherent
            private void restoreTest(ABTestValue atv) {
                var state = ABTestState.valueOf(atv.state());
                var routing = new VersionRouting(atv.newWeight(), atv.oldWeight());
                var variantVersions = deserializeVariantVersions(atv.variantVersionsJson());
                var splitRule = deserializeSplitRule(atv.splitRuleJson());
                var blueprintId = atv.blueprintId()
                                     .isEmpty()
                                  ? Option.<String>none()
                                  : Option.some(atv.blueprintId());
                var test = new ABTestDeployment(atv.testId(),
                                                atv.artifactBase(),
                                                atv.baselineVersion(),
                                                variantVersions,
                                                state,
                                                splitRule,
                                                routing,
                                                blueprintId,
                                                List.of(atv.artifactBase()),
                                                atv.createdAt(),
                                                atv.updatedAt());
                tests.put(test.testId(), test);
            }

            // --- Metrics collection ---
            private ABTestMetrics collectMetrics(ABTestDeployment test) {
                var snapshots = metricsCollector.snapshot();
                var metricsMap = new HashMap<String, ABTestMetrics.VariantMetrics>();
                test.variantVersions()
                    .forEach((variant, version) -> metricsMap.put(variant,
                                                                  collectVariantMetrics(snapshots,
                                                                                        test.artifactBase(),
                                                                                        variant,
                                                                                        version)));
                return ABTestMetrics.abTestMetrics(test.testId(), metricsMap);
            }

            private ABTestMetrics.VariantMetrics collectVariantMetrics(List<InvocationMetricsCollector.MethodSnapshot> snapshots,
                                                                       ArtifactBase artifactBase,
                                                                       String variant,
                                                                       Version version) {
                var artifact = artifactBase.withVersion(version);
                var accumulated = snapshots.stream()
                                           .filter(s -> s.artifact()
                                                         .equals(artifact))
                                           .reduce(new long[] {0, 0, 0, 0},
                                                   ABTestManager::accumulateSnapshot,
                                                   ABTestManager::combineAccumulators);
                long requests = accumulated[0];
                long errors = accumulated[1];
                long totalLatency = accumulated[2];
                long maxP99 = accumulated[3];
                double errorRate = requests > 0
                                   ? (double) errors / requests
                                   : 0.0;
                long avgLatency = requests > 0
                                  ? totalLatency / requests
                                  : 0;
                return ABTestMetrics.VariantMetrics.variantMetrics(variant,
                                                                   version,
                                                                   requests,
                                                                   errors,
                                                                   errorRate,
                                                                   maxP99,
                                                                   avgLatency);
            }

            // --- Housekeeping ---
            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void pruneTerminalTests() {
                var cutoff = System.currentTimeMillis() - terminalRetentionMs;
                var pruned = tests.entrySet()
                                  .removeIf(entry -> entry.getValue()
                                                          .isTerminal() && entry.getValue()
                                                                                .updatedAt() < cutoff);
                if (pruned) {
                    log.debug("Pruned terminal A/B tests older than retention period");
                }
            }

            // --- Serialization helpers ---
            private static String serializeVariantVersions(Map<String, Version> variants) {
                return variants.entrySet()
                               .stream()
                               .map(e -> e.getKey() + "=" + e.getValue())
                               .collect(Collectors.joining(","));
            }

            private static String serializeSplitRule(SplitRule rule) {
                return switch (rule) {
                    case SplitRule.HeaderHashSplit hhs -> "header-hash:" + hhs.headerName() + ":" + hhs.variantCount();
                    case SplitRule.CookieHashSplit chs -> "cookie-hash:" + chs.cookieName() + ":" + chs.variantCount();
                    case SplitRule.HeaderMatchSplit hms -> "header-match:" + hms.headerName() + ":" + hms.defaultVariant();
                    case SplitRule.PercentageSplit ps -> "percentage:" + ps.weights()
                                                                           .stream()
                                                                           .map(w -> w.variant() + "=" + w.weight())
                                                                           .collect(Collectors.joining(";"));
                };
            }
        }
        return new abTestManager(clusterNode,
                                 kvStore,
                                 metricsCollector,
                                 kvOperationTimeout,
                                 terminalRetentionMs,
                                 new ConcurrentHashMap<>());
    }

    /// Accumulates a single snapshot into running totals.
    private static long[] accumulateSnapshot(long[] acc, InvocationMetricsCollector.MethodSnapshot snapshot) {
        var metrics = snapshot.metrics();
        long p99Ms = metrics.estimatePercentileNs(99) / 1_000_000;
        return new long[] {acc[0] + metrics.count(),
        acc[1] + metrics.failureCount(),
        acc[2] + metrics.totalDurationNs() / 1_000_000,
        Math.max(acc[3], p99Ms)};
    }

    /// Combines two accumulators (for parallel stream reduction).
    private static long[] combineAccumulators(long[] a, long[] b) {
        return new long[] {a[0] + b[0],
        a[1] + b[1],
        a[2] + b[2],
        Math.max(a[3], b[3])};
    }

    /// Deserializes variant versions from serialized format.
    @SuppressWarnings("JBCT-VO-02")
    private static Map<String, Version> deserializeVariantVersions(String json) {
        // Serializer guarantees non-null (writes "" for empty)
        if (json.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<String, Version>();
        for (var entry : json.split(",")) {
            var eqIndex = entry.indexOf('=');
            if (eqIndex > 0) {
                var name = entry.substring(0, eqIndex);
                var versionStr = entry.substring(eqIndex + 1);
                Version.version(versionStr)
                       .onSuccess(v -> result.put(name, v));
            }
        }
        return Map.copyOf(result);
    }

    /// Deserializes a split rule from serialized format.
    @SuppressWarnings("JBCT-VO-02")
    private static SplitRule deserializeSplitRule(String json) {
        // Serializer guarantees non-null (writes "" for empty)
        if (json.isEmpty()) {
            return SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);
        }
        if (json.startsWith("header-hash:")) {
            return parseHeaderHashSplit(json);
        }
        if (json.startsWith("cookie-hash:")) {
            return parseCookieHashSplit(json);
        }
        if (json.startsWith("percentage:")) {
            return parsePercentageSplit(json);
        }
        return SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);
    }

    private static SplitRule parseHeaderHashSplit(String json) {
        var parts = json.substring("header-hash:".length())
                        .split(":", 2);
        return parts.length == 2
               ? SplitRule.HeaderHashSplit.headerHashSplit(parts[0], Integer.parseInt(parts[1]))
               : SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);
    }

    private static SplitRule parseCookieHashSplit(String json) {
        var parts = json.substring("cookie-hash:".length())
                        .split(":", 2);
        return parts.length == 2
               ? SplitRule.CookieHashSplit.cookieHashSplit(parts[0], Integer.parseInt(parts[1]))
               : SplitRule.CookieHashSplit.cookieHashSplit("session-id", 2);
    }

    private static SplitRule parsePercentageSplit(String json) {
        var weightStr = json.substring("percentage:".length());
        var weights = java.util.Arrays.stream(weightStr.split(";"))
                          .map(ABTestManager::parseVariantWeight)
                          .toList();
        return SplitRule.PercentageSplit.percentageSplit(weights);
    }

    private static SplitRule.PercentageSplit.VariantWeight parseVariantWeight(String entry) {
        var eqIndex = entry.indexOf('=');
        return eqIndex > 0
               ? SplitRule.PercentageSplit.VariantWeight.variantWeight(entry.substring(0, eqIndex),
                                                                       Integer.parseInt(entry.substring(eqIndex + 1)))
               : SplitRule.PercentageSplit.VariantWeight.variantWeight(entry, 1);
    }
}
