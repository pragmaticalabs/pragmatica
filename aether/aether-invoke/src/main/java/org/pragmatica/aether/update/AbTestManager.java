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
            /// The canary runs at the slice floor. The variant IS the slice's own target (same key, new
            /// version), so the runtime floor of [SliceSpec#MIN_INSTANCES] applies to it at every phase
            /// (#1721, #1495). Deliberately NOT applied to `minInstances`, which belongs to the
            /// operator (#982).
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
                                       .timeout(kvOperationTimeout)
                                       .fold(result -> result.fold(cause -> abandonStart(test, cause),
                                                                   _ -> Promise.unitPromise()));
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

            /// The canary write. The variant runs at the slice floor, and the operator's floor is
            /// carried untouched.
            ///
            /// #982: this write used to set `minInstances = 1` as well, which silently replaced an
            /// operator's configured floor for the rest of the slice's life — the conclusion writes
            /// below re-applied the same pin rather than restoring it, so nothing ever put it back.
            /// The autoscaler's scale-down ratchet masked it until #936 removed the ratchet.
            ///
            /// Lowering the floor was never needed to place the canary: the variant is a DIFFERENT
            /// artifact version, so `ClusterDeploymentState.handleSliceTargetChange` allocates it
            /// from zero instances and `SliceAllocationEngine.issueAdjustmentCommands` scales UP to
            /// `targetInstances`. `minInstances` is only ever read as a cap on scale-DOWN removals
            /// (`issueScaleDownCommands`) and as a scale-down gate (`DecisionTreeController`), and
            /// teardown bypasses both by issuing unload commands directly. A preserved floor
            /// therefore cannot hold the canary above the slice floor, nor pin a variant that has to
            /// be removed.
            private static SliceTargetValue variantTarget(SliceTargetValue current, Version version) {
                return current.withVersion(version)
                              .withInstances(VARIANT_INSTANCES);
            }

            /// The conclusion writes — promote a winner, or restore the baseline on rollback.
            ///
            /// The concluded version takes over the slice, so it must not be left running at the
            /// canary's instance count while the operator's floor says otherwise: the allocation
            /// engine drives to `targetInstances`, and nothing climbs from below the floor on its
            /// own, so `target = 3, min = 5` is a slice permanently parked under its own declared
            /// minimum. `effectiveMinInstances()` is the operator's floor clamped to at least one,
            /// but an operator floor below the slice floor (`min = 1`) must not park the winner under it
            /// (#1721), so the count is clamped to at least [#VARIANT_INSTANCES].
            ///
            /// This restores the floor's worth of capacity, not necessarily the pre-test
            /// `targetInstances` — a slice scaled to 8 above a floor of 5 concludes at 5 and climbs
            /// again on load. Recovering the exact pre-test count would mean persisting it in
            /// `AbTestValue` for the lifetime of the test; the floor is the operator's stated
            /// guarantee and is already in the record, so it is the honest thing to restore here.
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
                           .flatMap(concluding -> promoteWinner(concluding, winningVariant));
            }

            /// #2662: a fenced write; a target that is no longer committed is refused. #2964: the fenced write is decided BEFORE the test's
            /// state is persisted: `CONCLUDING` exists only in memory, on the value this method holds, so a refusal leaves the persisted test
            /// `ACTIVE` and the same request, repeated, runs the same idempotent write (it derives from the committed target and sets the
            /// winner's version) and completes. The test is persisted `COMPLETED` only after the write is committed.
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
                                     .flatMap(this::restoreBaseline);
            }

            /// #2662: a fenced write. A target that is not committed, or whose owning blueprint is not, is not restored: writing it would
            /// re-create (or keep alive) the target of a deleted blueprint, and there is nothing left to roll back, so the test still ends.
            /// This holds whether the absence is seen by the first build or by a rebuild after a refused submission.
            /// #2964: as in [#promoteWinner], `ROLLING_BACK` exists only in memory; the test is persisted `ROLLED_BACK` after the write, so a
            /// refused write leaves the persisted test as it was and a repeated rollback runs the same idempotent write.
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

            /// #2964: the start persists `DEPLOYING_VARIANTS` BEFORE the fenced write, so that a crash between the two leaves a test an operator
            /// can roll back (rather than a changed target no test owns). A fence refusal ([SliceTargetFenceError]: the target was NOT written)
            /// therefore compensates: the test's record is removed and the test forgotten, and the plain refusal (which may say "retry the
            /// request") is returned; the repeated start finds no active test and creates exactly one. Any other failure (a timeout or a failed
            /// apply: the write may have committed) is not compensated, and the test stays `DEPLOYING_VARIANTS` for the operator to roll back.
            private Promise<Unit> abandonStart(AbTestDeployment test, Cause cause) {
                return switch (cause) {
                    case SliceTargetFenceError fenceError -> removeTestRecord(test, fenceError);
                    default -> cause.promise();
                };
            }

            /// If the record cannot be removed either (a double fault: the fence refused and consensus then failed), the test stays persisted
            /// `DEPLOYING_VARIANTS` and the refusal is re-labelled without the retry hint, because a repeated start is then refused as a
            /// duplicate: the operator must roll the test back first.
            private Promise<Unit> removeTestRecord(AbTestDeployment test, SliceTargetFenceError refusal) {
                return clusterNode.<Unit> apply(List.of(removeCommand(test)))
                                  .timeout(kvOperationTimeout)
                                  .fold(removed -> removed.fold(_ -> stayDeploying(refusal),
                                                                _ -> forget(test, refusal)));
            }

            private Promise<Unit> forget(AbTestDeployment test, SliceTargetFenceError refusal) {
                tests.remove(test.testId());

                return refusal.promise();
            }

            private static Promise<Unit> stayDeploying(SliceTargetFenceError refusal) {
                return switch (refusal) {
                    case SliceTargetFenceError.Contended contended -> new SliceTargetFenceError.ContendedAfterStateChange(contended.write(),
                                                                                                                          contended.submissions(),
                                                                                                                          AbTestState.DEPLOYING_VARIANTS.name()).promise();
                    default -> refusal.promise();
                };
            }

            @SuppressWarnings("unchecked")
            private static KVCommand<AetherKey> removeCommand(AbTestDeployment test) {
                return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(new AbTestKey(test.testId()));
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

            @SuppressWarnings("unchecked")
            private Promise<AbTestDeployment> cacheAndPersistTest(AbTestDeployment test) {
                tests.put(test.testId(), test);
                if (test.isTerminal()) {
                    pruneTerminalTests();
                }

                var key = new AbTestKey(test.testId());
                var value = buildTestValue(test);
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);

                return clusterNode.<Unit> apply(List.of(command))
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
