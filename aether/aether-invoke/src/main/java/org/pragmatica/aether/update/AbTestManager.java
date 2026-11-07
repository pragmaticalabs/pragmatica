// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.blueprint.SliceSpec;
import org.pragmatica.aether.slice.kvstore.AbTestIndex;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence.SliceTargetFenceError;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface AbTestManager {
    Promise<Unit> activate();

    /// #1533 — re-read the A/B tests from the KV store when a restore has landed after [#activate] (a
    /// fresh cluster activates before its restore). A no-op unless active; idempotent.
    default Unit reloadRestoredState() {
        return Unit.unit();
    }

    Promise<Unit> deactivate();
    boolean isActive();

    Promise<AbTestDeployment> createTest(ArtifactBase artifactBase,
                                         Map<String, Version> variantVersions,
                                         SplitRule splitRule);

    Promise<AbTestDeployment> concludeTest(String testId, String winningVariant);
    Promise<AbTestDeployment> rollbackTest(String testId);
    Option<AbTestDeployment> getTest(String testId);
    Option<AbTestDeployment> getActiveTest(ArtifactBase artifactBase);
    List<AbTestDeployment> activeTests();
    List<AbTestDeployment> allTests();
    AbTestMetrics getMetrics(String testId);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentFailed(DeploymentEvent.DeploymentFailed event);

    TimeSpan DEFAULT_KV_OPERATION_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    long DEFAULT_TERMINAL_RETENTION_MS = TimeUnit.HOURS.toMillis(1);

    static AbTestManager abTestManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       InvocationMetricsCollector metricsCollector) {
        return abTestManager(clusterNode,
                             kvStore,
                             metricsCollector,
                             DEFAULT_KV_OPERATION_TIMEOUT,
                             DEFAULT_TERMINAL_RETENTION_MS);
    }

    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-NAM-01"})
    static AbTestManager abTestManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       InvocationMetricsCollector metricsCollector,
                                       TimeSpan kvOperationTimeout,
                                       long terminalRetentionMs) {
        record abTestManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                             KVStore<AetherKey, AetherValue> kvStore,
                             InvocationMetricsCollector metricsCollector,
                             TimeSpan kvOperationTimeout,
                             long terminalRetentionMs,
                             Map<String, AbTestDeployment> tests,
                             AtomicBoolean active) implements AbTestManager {
            private static final Logger log = LoggerFactory.getLogger(AbTestManager.class);
            /// The canary runs at the slice floor (#1721; owner ruling 2026-11-06, v2713 item 7). The variant IS the slice's own
            /// target (same key, new version). While the test runs its canary ([AetherValue.AbTestValue#runsCanary]) the requirement
            /// combiner and the autoscaler hold the slice, so the variant is never scaled to the full combined target. Deliberately
            /// NOT applied to `minInstances`, which belongs to the operator (#982).
            private static final int VARIANT_INSTANCES = SliceSpec.MIN_INSTANCES;

            @Override
            public Promise<Unit> activate() {
                log.info("A/B test manager active (STRATEGIES task group assigned)");
                active.set(true);
                restoreState();

                return Promise.success(Unit.unit());
            }

            @Override
            public Unit reloadRestoredState() {
                if (active.get()) {
                    restoreState();
                }

                return Unit.unit();
            }

            @Override
            public Promise<Unit> deactivate() {
                log.info("A/B test manager passive (STRATEGIES task group unassigned)");
                active.set(false);

                return Promise.success(Unit.unit());
            }

            @Override
            public boolean isActive() {
                return active.get();
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {
                var artifactBase = event.artifact().base();

                getActiveTest(artifactBase).filter(test -> containsVersion(test,
                                                                           event.artifact().version()))
                             .filter(AbTestDeployment::isActive)
                             .onPresent(test -> triggerAutoRollback(test, event));
            }

            private boolean containsVersion(AbTestDeployment test, Version version) {
                return test.variantVersions()
                           .containsValue(version);
            }

            @SuppressWarnings("JBCT-RET-01")
            private void triggerAutoRollback(AbTestDeployment test, DeploymentEvent.DeploymentFailed event) {
                log.warn("Auto-rollback triggered for A/B test {} — variant failed on node {}: {}",
                         test.testId(),
                         event.nodeId(),
                         event.errorMessage());
                rollbackTest(test.testId()).onFailure(cause -> log.error("Auto-rollback failed for A/B test {}: {}",
                                                                         test.testId(),
                                                                         cause.message()));
            }

            @Override
            public Promise<AbTestDeployment> createTest(ArtifactBase artifactBase,
                                                        Map<String, Version> variantVersions,
                                                        SplitRule splitRule) {
                return requireActive().flatMap(_ -> checkNoActiveTest(artifactBase))
                                    .flatMap(_ -> findCurrentVersion(artifactBase))
                                    .flatMap(baseline -> createAndDeployTest(artifactBase,
                                                                             baseline,
                                                                             variantVersions,
                                                                             splitRule));
            }

            @Override
            public Promise<AbTestDeployment> concludeTest(String testId, String winningVariant) {
                return requireActive().flatMap(_ -> findTest(testId))
                                    .flatMap(test -> validateAndConclude(test, winningVariant));
            }

            @Override
            public Promise<AbTestDeployment> rollbackTest(String testId) {
                return requireActive().flatMap(_ -> findTest(testId))
                                    .flatMap(this::validateAndRollback);
            }

            @Override
            public Option<AbTestDeployment> getTest(String testId) {
                return Option.option(tests.get(testId));
            }

            @Override
            public Option<AbTestDeployment> getActiveTest(ArtifactBase artifactBase) {
                return Option.from(tests.values()
                                        .stream()
                                        .filter(t -> t.artifactBase()
                                                      .equals(artifactBase) && t.isActive())
                                        .findFirst());
            }

            @Override
            public List<AbTestDeployment> activeTests() {
                return tests.values()
                            .stream()
                            .filter(AbTestDeployment::isActive)
                            .toList();
            }

            @Override
            public List<AbTestDeployment> allTests() {
                return List.copyOf(tests.values());
            }

            @Override
            public AbTestMetrics getMetrics(String testId) {
                return Option.option(tests.get(testId))
                             .map(this::collectMetrics)
                             .or(AbTestMetrics.abTestMetrics(testId,
                                                             Map.of()));
            }

            private Promise<Unit> requireActive() {
                if (!active.get()) {
                    return AbTestDeploymentError.NotLeader.INSTANCE.promise();
                }

                return Promise.success(Unit.unit());
            }

            private Promise<Unit> checkNoActiveTest(ArtifactBase artifactBase) {
                return getActiveTest(artifactBase).isPresent()
                       ? AbTestDeploymentError.TestAlreadyExists.testAlreadyExists(artifactBase).promise()
                       : Promise.success(Unit.unit());
            }

            private Promise<AbTestDeployment> findTest(String testId) {
                return Option.option(tests.get(testId))
                             .toResult(AbTestDeploymentError.TestNotFound.testNotFound(testId))
                             .async();
            }

            private Promise<Version> findCurrentVersion(ArtifactBase artifactBase) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);

                return kvStore.get(key)
                              .map(value -> ((SliceTargetValue) value).currentVersion())
                              .toResult(AbTestDeploymentError.InitialDeployment.initialDeployment(artifactBase))
                              .async();
            }

            private Promise<AbTestDeployment> createAndDeployTest(ArtifactBase artifactBase,
                                                                  Version baseline,
                                                                  Map<String, Version> variantVersions,
                                                                  SplitRule splitRule) {
                var testId = IdGenerator.generate();
                var test = AbTestDeployment.abTestDeployment(testId, artifactBase, baseline, variantVersions, splitRule);

                log.info("Starting A/B test {} for {} with {} variants (baseline: {})",
                         testId,
                         artifactBase,
                         variantVersions.size(),
                         baseline);
                tests.put(testId, test);

                return persistAndTransition(test, AbTestState.DEPLOYING_VARIANTS).flatMap(this::deployVariants);
            }

            /// #2662: the variants are ONE fenced write of the slice's target. The puts this replaces all wrote the one target key, one per
            /// variant in iteration order, so the last variant's version is what stayed committed; a transaction may name a key once, so
            /// that last version is written directly. The write expects the target read and witnesses its owning blueprint; a target that
            /// is no longer committed (its blueprint deleted since the test was created) is refused, never re-created.
            private Promise<AbTestDeployment> deployVariants(AbTestDeployment test) {
                var versions = List.copyOf(test.variantVersions().values());

                log.info("Deploying {} variant versions for A/B test {}", versions.size(), test.testId());

                return lastVariant(versions).fold(Promise::unitPromise,
                                                  last -> writeVariant(test, last))
                                  .flatMap(_ -> activateTest(test));
            }

            /// The version the per-variant puts left committed: the last in iteration order; none when there are no variants (the
            /// unfenced writer then wrote nothing).
            private static Option<Version> lastVariant(List<Version> versions) {
                return versions.isEmpty()
                       ? Option.none()
                       : Option.some(versions.getLast());
            }

            private Promise<Unit> writeVariant(AbTestDeployment test, Version version) {
                return SliceTargetFence.submit("ab-test-variants " + test.testId(),
                                               clusterNode::apply,
                                               () -> targetTransaction(test.artifactBase(),
                                                                       current -> variantTarget(current, version)))
                                       .timeout(kvOperationTimeout);
            }

            /// The one read both A/B lifecycle writers derive from, and the reason they can.
            ///
            /// A/B lifecycle writes must not evaporate the operator's per-slice bounds (#424 review),
            /// the slice's ownership (#698), its placement (#937) or its availability floor (#982).
            /// [#variantTarget] and [#concludedTarget] therefore TRANSFORM this observed value rather
            /// than rebuilding one from parts, so every component neither of them deliberately
            /// replaces survives by construction. Between them they replace `currentVersion` and
            /// `targetInstances` only; `minInstances` is replaced by neither.
            ///
            /// What that carries, `placement` included: #937 recorded it being reset to `CORE_ONLY`
            /// here, and the reset is not merely stored — `ClusterDeploymentState` feeds
            /// `effectivePlacement()` into the allocation engine, so an operator's deliberately
            /// placed workload was relocated on the first A/B write. `updatedAt` is re-derived by the
            /// `with*` chain, which is the point of a write.
            ///
            /// The enumerate-the-preserved-fields shape this method used to have is what let
            /// `placement` go missing while the list read as exhaustive. It is deliberately not
            /// restored: a `with*` chain cannot omit a field it does not mention.
            ///
            /// `owningBlueprint` matters beyond bookkeeping: `ClusterDeploymentState` resolves a
            /// slice's `schemaRequired` from its owner, and an erased owner silently takes the
            /// historical default `true`, flipping a `schema_required = false` slice on the next A/B
            /// write.
            ///
            /// #2662: the read is the committed target, re-read on every build of the fenced write, and
            /// the write expects exactly the value read and witnesses its owning blueprint.
            private Result<LeaderTransaction<AetherKey, AetherValue>> targetTransaction(ArtifactBase artifactBase,
                                                                                        Fn1<SliceTargetValue, SliceTargetValue> derive) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);

                return SliceTargetFence.presentTarget(kvStore, key)
                                       .flatMap(read -> SliceTargetFence.targetWrite(kvStore,
                                                                                     key,
                                                                                     Option.some(read),
                                                                                     derive.apply(read)))
                                       .flatMap(write -> SliceTargetFence.transaction(kvStore,
                                                                                      List.of(write),
                                                                                      List.of()));
            }

            /// The canary write: the variant runs at [#VARIANT_INSTANCES], and the operator's floor (#982),
            /// placement (#937) and owner (#698) are carried untouched.
            ///
            /// This is the one write of `targetInstances` besides the requirement combiner's (rc6 §3.1,
            /// INV-02), and it does not race the combiner: the test is committed as `DEPLOYING_VARIANTS`
            /// before this write, and the combiner does not combine a slice whose test runs a canary
            /// (`CombinerOutcome.CanaryRunning`). Lowering the floor was never needed to place the
            /// canary: the variant is a DIFFERENT artifact version, allocated from zero instances.
            private static SliceTargetValue variantTarget(SliceTargetValue current, Version version) {
                return current.withVersion(version)
                              .withInstances(VARIANT_INSTANCES);
            }

            /// The conclusion writes: promote a winner, or restore the baseline on rollback. The concluded
            /// version takes over the slice at its floor's worth of capacity, at least
            /// [#VARIANT_INSTANCES] (#1721): the allocation engine drives to `targetInstances`, and a
            /// winner parked at the canary count under its own floor would stay there. The test's
            /// terminal put then re-runs the requirement combiner, which restores the count every
            /// committed requirement (an operator floor, the autoscaler's `REACTIVE`) asks for.
            ///
            /// #2662: an A/B test runs over a committed target ([#findCurrentVersion]), so no writer
            /// here creates one: a target that is gone was removed with its blueprint.
            private static SliceTargetValue concludedTarget(SliceTargetValue current, Version version) {
                return current.withVersion(version)
                              .withInstances(Math.max(current.effectiveMinInstances(),
                                                      VARIANT_INSTANCES));
            }

            private Promise<AbTestDeployment> activateTest(AbTestDeployment test) {
                var withRouting = test.withRouting(VersionRouting.ALL_NEW);

                tests.put(test.testId(), withRouting);

                return persistAndTransition(withRouting, AbTestState.ACTIVE);
            }

            private Promise<AbTestDeployment> validateAndConclude(AbTestDeployment test, String winningVariant) {
                if (test.state() != AbTestState.ACTIVE) {
                    return AbTestDeploymentError.InvalidTestState.invalidTestState(test.state(),
                                                                                   AbTestState.CONCLUDING)
                                                                 .promise();
                }

                if (!test.variantVersions().containsKey(winningVariant)) {
                    return AbTestDeploymentError.VariantNotFound.variantNotFound(test.testId(),
                                                                                 winningVariant)
                                                                .promise();
                }

                log.info("Concluding A/B test {} — winner: {}", test.testId(), winningVariant);

                return test.transitionTo(AbTestState.CONCLUDING)
                           .async()
                           .flatMap(this::cacheAndPersistTest)
                           .flatMap(concluded -> promoteWinner(concluded, winningVariant));
            }

            /// #2662: a fenced write; a target that is no longer committed is refused, and the test stays `CONCLUDING` (it can be rolled back).
            private Promise<AbTestDeployment> promoteWinner(AbTestDeployment test, String winningVariant) {
                var winnerVersion = test.variantVersions().get(winningVariant);

                return SliceTargetFence.submit("ab-test-conclude " + test.testId(),
                                               clusterNode::apply,
                                               () -> targetTransaction(test.artifactBase(),
                                                                       current -> concludedTarget(current, winnerVersion)))
                                       .timeout(kvOperationTimeout)
                                       .flatMap(_ -> persistAndTransition(test, AbTestState.COMPLETED));
            }

            private Promise<AbTestDeployment> validateAndRollback(AbTestDeployment test) {
                if (test.isTerminal()) {
                    return AbTestDeploymentError.InvalidTestState.invalidTestState(test.state(),
                                                                                   AbTestState.ROLLING_BACK)
                                                                 .promise();
                }

                log.info("Rolling back A/B test {}", test.testId());
                var withOldRouting = test.withRouting(VersionRouting.ALL_OLD);

                return withOldRouting.transitionTo(AbTestState.ROLLING_BACK)
                                     .async()
                                     .flatMap(this::cacheAndPersistTest)
                                     .flatMap(this::restoreBaseline);
            }

            /// #2662: a fenced write. A target that is not committed, or whose owning blueprint is not, is not restored: writing it would
            /// re-create (or keep alive) the target of a deleted blueprint, and there is nothing left to roll back, so the test still ends.
            /// This holds whether the absence is seen by the first build or by a rebuild after a refused submission.
            private Promise<AbTestDeployment> restoreBaseline(AbTestDeployment test) {
                log.info("Restoring baseline {} for A/B test {}", test.baselineVersion(), test.testId());

                return SliceTargetFence.submit("ab-test-rollback " + test.testId(),
                                               clusterNode::apply,
                                               () -> targetTransaction(test.artifactBase(),
                                                                       current -> concludedTarget(current,
                                                                                                  test.baselineVersion())))
                                       .timeout(kvOperationTimeout)
                                       .replaceResult(result -> result.fold(cause -> nothingToRestore(test, cause),
                                                                            Result::success))
                                       .flatMap(_ -> persistAndTransition(test, AbTestState.ROLLED_BACK));
            }

            /// FER (degrade forward): a rollback whose target, or the target's owning blueprint, is no longer committed has nothing to
            /// restore, so it ends the test without writing. Guarantee: the test reaches `ROLLED_BACK` and no target of a deleted blueprint
            /// is written. Mechanism: only [SliceTargetFenceError.TargetAbsent] and [SliceTargetFenceError.OrphanTarget] are absorbed;
            /// every other failure (contention, no leader, a failed apply, the timeout) still fails the rollback.
            private Result<Unit> nothingToRestore(AbTestDeployment test, Cause cause) {
                return switch (cause) {
                    case SliceTargetFenceError.TargetAbsent _, SliceTargetFenceError.OrphanTarget _ -> skipRestore(test,
                                                                                                                   cause);
                    default -> cause.result();
                };
            }

            private Result<Unit> skipRestore(AbTestDeployment test, Cause cause) {
                log.info("A/B test {} rollback does not restore the baseline of {}: {}",
                         test.testId(),
                         test.artifactBase(),
                         cause.message());

                return Result.unitResult();
            }

            private Promise<AbTestDeployment> persistAndTransition(AbTestDeployment test, AbTestState newState) {
                return test.transitionTo(newState)
                           .async()
                           .flatMap(this::cacheAndPersistTest);
            }

            /// #2713: the record is written in ONE transaction with the base's A/B index ([AbTestIndex#testWrite]), so the requirement
            /// combiner's witness on the index refuses a combination read before this write. A refused submission (a concurrent write
            /// moved the index or the record) is rebuilt from a fresh read.
            private Promise<AbTestDeployment> cacheAndPersistTest(AbTestDeployment test) {
                tests.put(test.testId(), test);
                if (test.isTerminal()) {
                    pruneTerminalTests();
                }

                var value = buildTestValue(test);

                return SliceTargetFence.submit("ab-test " + test.testId(),
                                               clusterNode::apply,
                                               () -> AbTestIndex.testWrite(kvStore, value))
                                       .timeout(kvOperationTimeout)
                                       .map(_ -> test);
            }

            private AbTestValue buildTestValue(AbTestDeployment test) {
                var variantsJson = serializeVariantVersions(test.variantVersions());
                var splitRuleJson = serializeSplitRule(test.splitRule());

                return new AbTestValue(test.testId(),
                                       test.artifactBase(),
                                       test.baselineVersion(),
                                       variantsJson,
                                       test.state().name(),
                                       splitRuleJson,
                                       test.routing().newWeight(),
                                       test.routing().oldWeight(),
                                       test.blueprintId().or(""),
                                       test.createdAt(),
                                       System.currentTimeMillis());
            }

            @SuppressWarnings("JBCT-RET-01")
            private void restoreState() {
                int beforeCount = tests.size();

                kvStore.forEach(AbTestKey.class, AbTestValue.class, (key, value) -> restoreTest(value));
                int restoredCount = tests.size() - beforeCount;

                if (restoredCount > 0) {
                    log.info("Restored {} A/B tests from KV-Store", restoredCount);
                }
            }

            @SuppressWarnings("JBCT-RET-01")
            private void restoreTest(AbTestValue atv) {
                var state = AbTestState.valueOf(atv.state());
                var routing = new VersionRouting(atv.newWeight(), atv.oldWeight());
                var variantVersions = deserializeVariantVersions(atv.variantVersionsJson());
                var splitRule = deserializeSplitRule(atv.splitRuleJson());
                var blueprintId = atv.blueprintId().isEmpty()
                                  ? Option.<String> none()
                                  : Option.some(atv.blueprintId());
                var test = new AbTestDeployment(atv.testId(),
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

            private AbTestMetrics collectMetrics(AbTestDeployment test) {
                var snapshots = metricsCollector.snapshot();
                var metricsMap = new HashMap<String, AbTestMetrics.VariantMetrics>();

                test.variantVersions()
                    .forEach((variant, version) -> metricsMap.put(variant,
                                                                  collectVariantMetrics(snapshots,
                                                                                        test.artifactBase(),
                                                                                        variant,
                                                                                        version)));

                return AbTestMetrics.abTestMetrics(test.testId(), metricsMap);
            }

            private AbTestMetrics.VariantMetrics collectVariantMetrics(List<InvocationMetricsCollector.MethodSnapshot> snapshots,
                                                                       ArtifactBase artifactBase,
                                                                       String variant,
                                                                       Version version) {
                var artifact = artifactBase.withVersion(version);
                var accumulated = snapshots.stream()
                                           .filter(s -> s.artifact()
                                                         .equals(artifact))
                                           .reduce(new long[]{0, 0, 0, 0},
                                                   AbTestManager::accumulateSnapshot,
                                                   AbTestManager::combineAccumulators);
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

                return AbTestMetrics.VariantMetrics.variantMetrics(variant,
                                                                   version,
                                                                   requests,
                                                                   errors,
                                                                   errorRate,
                                                                   maxP99,
                                                                   avgLatency);
            }

            @SuppressWarnings("JBCT-RET-01")
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
                    case SplitRule.PercentageSplit ps -> "percentage:" + ps.weights().stream().map(w -> w.variant()
                                                                                                       + "=" + w.weight()).collect(Collectors.joining(";"));
                };
            }
        }

        return new abTestManager(clusterNode,
                                 kvStore,
                                 metricsCollector,
                                 kvOperationTimeout,
                                 terminalRetentionMs,
                                 new ConcurrentHashMap<>(),
                                 new AtomicBoolean(false));
    }

    private static long[] accumulateSnapshot(long[] acc, InvocationMetricsCollector.MethodSnapshot snapshot) {
        var metrics = snapshot.metrics();
        long p99Ms = metrics.estimatePercentileNs(99) / 1_000_000;

        return new long[]{acc[0] + metrics.count(), acc[1] + metrics.failureCount(), acc[2] + metrics.totalDurationNs() / 1_000_000, Math.max(acc[3],
                                                                                                                                              p99Ms)};
    }

    private static long[] combineAccumulators(long[] a, long[] b) {
        return new long[]{a[0] + b[0], a[1] + b[1], a[2] + b[2], Math.max(a[3], b[3])};
    }

    private static Map<String, Version> deserializeVariantVersions(String json) {
        if (json.isEmpty()) {
            return Map.of();
        }

        var result = new HashMap<String, Version>();

        for (var entry : json.split(",")) {
            var eqIndex = entry.indexOf('=');

            if (eqIndex > 0) {
                var name = entry.substring(0, eqIndex);
                var versionStr = entry.substring(eqIndex + 1);

                Version.version(versionStr).onSuccess(v -> result.put(name, v));
            }
        }

        return Map.copyOf(result);
    }

    private static SplitRule deserializeSplitRule(String json) {
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
        var parts = json.substring("header-hash:".length()).split(":", 2);

        return parts.length == 2
               ? SplitRule.HeaderHashSplit.headerHashSplit(parts[0], Integer.parseInt(parts[1]))
               : SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2);
    }

    private static SplitRule parseCookieHashSplit(String json) {
        var parts = json.substring("cookie-hash:".length()).split(":", 2);

        return parts.length == 2
               ? SplitRule.CookieHashSplit.cookieHashSplit(parts[0], Integer.parseInt(parts[1]))
               : SplitRule.CookieHashSplit.cookieHashSplit("session-id", 2);
    }

    private static SplitRule parsePercentageSplit(String json) {
        var weightStr = json.substring("percentage:".length());
        var weights = java.util.Arrays.stream(weightStr.split(";")).map(AbTestManager::parseVariantWeight).toList();

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
