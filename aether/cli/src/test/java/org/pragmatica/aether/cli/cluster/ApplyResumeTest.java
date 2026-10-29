// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.cli.cluster.ApplyCommitRecovery.ClusterConfigView;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// `--resume` (cluster-apply spec 4.3): it CONTINUES the halted ledger, never replaces it, and never creates an id twice.
class ApplyResumeTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("resume-test").unwrap();
    private static final String DOCKER = """
            config_version = "1.0.0"
            [cluster]
            name = "resume-test"
            version = "1.0.0"
            [source.local]
            type = "docker"
            [source.local.core]
            count = 1
            [source.local.worker]
            count = 1
            """;

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final Consumer<Duration> originalPause = ApplyReconciler.pause;
    private final AtomicInteger executions = new AtomicInteger();

    @AfterEach
    void restore() {
        ApplyReconciler.pause = originalPause;
        ProviderResolver.dockerFactoryOverride = null;
    }

    private ClusterBootstrapConfig desired() {
        return ClusterBootstrapConfigParser.parse(DOCKER).unwrap();
    }

    /// The hash the ledger must carry for the resume to accept the config.
    private ApplyScenario scenarioWithMatchingHash() {
        ApplyReconciler.pause = _ -> {};

        var scenario = new ApplyScenario(ledger, CLUSTER);

        var base = scenario.recorder.snapshot();
        var hashed = ApplyState.applyState(base.applyId(), CLUSTER, ClusterBootstrapOrchestrator.computeConfigHash(desired()), base.storedVersion(), base.startedAt(),
                                           base.phase(), base.actions(), base.resources(), base.reservations(), base.createdResources(), base.destroyedNodeIds());

        ApplyState.save(hashed).unwrap();

        var recorder = ApplyRecorder.applyRecorder(hashed);

        recorder.beginAction(ApplyScenario.ACTION).unwrap();

        return new ApplyScenario(ledger, CLUSTER, recorder);
    }

    private Result<ApplyResult> resume(ApplyScenario scenario, java.util.function.Supplier<Option<ClusterConfigView>> view) {
        var desired = desired();

        return ApplyOrchestrator.resume(desired, desired, scenario.computes(), (plan, stored, wanted, recorder) -> {
            executions.incrementAndGet();

            return Result.success(ApplyResult.applyResult(plan, 0, 0, 0));
        }, view);
    }

    @Test
    void resume_continuesTheHaltedLedger_doesNotReplaceIt() {
        var scenario = scenarioWithMatchingHash();
        var applyId = new AtomicReference<String>();
        var rowsSeen = new AtomicInteger();

        scenario.createRecorded("n-1");

        var desired = desired();

        ApplyOrchestrator.resume(desired, desired, scenario.computes(), (plan, stored, wanted, recorder) -> {
            applyId.set(recorder.applyId());
            rowsSeen.set(recorder.snapshot().resources().size());

            return Result.success(ApplyResult.applyResult(plan, 0, 0, 0));
        }, Option::none).onFailure(cause -> fail(cause.message()));

        assertThat(applyId.get()).as("the same apply, not a fresh one").isEqualTo(scenario.recorder.applyId());
        assertThat(rowsSeen.get()).as("its rows are still there").isEqualTo(1);
    }

    @Test
    void resume_lookupFails_refusesAndCreatesNothing() {
        var scenario = scenarioWithMatchingHash();

        scenario.createWithLostReply("n-1");
        scenario.provider.onFilteredList = _ -> org.pragmatica.lang.utils.Causes.cause("HTTP 503 (test cause)").promise();

        resume(scenario, Option::none).onSuccess(_ -> fail("must refuse")).onFailure(cause -> assertThat(cause.message()).contains("Nothing was created"));

        assertThat(executions.get()).as("the executor never ran").isZero();
        assertThat(scenario.harness.provider.totalCreates()).isEqualTo(1);
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).as("the ledger is kept").isTrue();
    }

    @Test
    void resume_adoptsAnIntendedRowWhoseVmExists_createCountZero() {
        var scenario = scenarioWithMatchingHash();

        scenario.createWithLostReply("n-1");

        resume(scenario, Option::none).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.harness.provider.totalCreates()).as("no create on resume").isEqualTo(1);
        assertThat(scenario.provider.filteredListings).as("the lookup ran before the executor").isGreaterThanOrEqualTo(1);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void resume_auditAdoptsAVmTheLedgerDoesNotKnow_andSaysSo() {
        var scenario = scenarioWithMatchingHash();
        var originalOut = System.out;
        var captured = new java.io.ByteArrayOutputStream();

        scenario.createRecorded("n-1");
        // lands after the executor ran, before the audit
        var desired = desired();

        System.setOut(new java.io.PrintStream(captured, true));
        try {
            ApplyOrchestrator.resume(desired, desired, scenario.computes(), (plan, stored, wanted, recorder) -> {
                scenario.harness.provider.create("late-node", scenario.labels());

                return Result.success(ApplyResult.applyResult(plan, 0, 0, 0));
            }, Option::none).onFailure(cause -> fail(cause.message()));
        } finally {
            System.setOut(originalOut);
        }

        assertThat(captured.toString()).contains("Adopted a VM found under the apply label").contains("late-node");
    }

    @Test
    void resume_fromAborted_isRefused_nothingCreated_andTheLedgerIsKept() {
        var scenario = scenarioWithMatchingHash();

        scenario.createRecorded("n-1");
        scenario.recorder.phase(ApplyPhase.ABORTED).unwrap();

        resume(scenario, Option::none).onSuccess(_ -> fail("aborted: rollback only"))
                                      .onFailure(cause -> assertThat(cause.message()).contains("Refused").contains("--rollback"));

        assertThat(executions.get()).isZero();
        assertThat(scenario.provider.filteredListings).as("refused before the provider was looked at").isZero();
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).isTrue();
    }

    @Test
    void committed_resumeFinalises_createsAndTerminatesNothing() {
        var scenario = scenarioWithMatchingHash();

        scenario.createRecorded("n-1");
        scenario.recorder.phase(ApplyPhase.COMMITTED).unwrap();

        resume(scenario, Option::none).onFailure(cause -> fail(cause.message()));

        assertThat(executions.get()).isZero();
        assertThat(scenario.provider.terminated).isEmpty();
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).as("finalised: the ledger is closed").isFalse();
    }

    @Test
    void committingAndTheClusterConfigMoved_resumeIsRefused_andTheLedgerBecomesAborted() {
        var scenario = scenarioWithMatchingHash();

        scenario.createRecorded("n-1");
        scenario.recorder.phase(ApplyPhase.COMMITTING).unwrap();

        resume(scenario, () -> Option.some(new ClusterConfigView("hash-other", 99))).onSuccess(_ -> fail("moved: roll back"))
                                                                                      .onFailure(cause -> assertThat(cause.message()).contains("Refused"));

        assertThat(scenario.onDisk().phase()).as("written before returning").isEqualTo(ApplyPhase.ABORTED);
        assertThat(executions.get()).isZero();
    }

    @Test
    void committingAndTheClusterUnreadable_neitherProceeds_andNothingIsMoved() {
        var scenario = scenarioWithMatchingHash();

        scenario.recorder.phase(ApplyPhase.COMMITTING).unwrap();

        resume(scenario, Option::none).onSuccess(_ -> fail("cannot decide"))
                                      .onFailure(cause -> assertThat(cause.message()).contains("cannot determine the commit state"));

        assertThat(scenario.onDisk().phase()).isEqualTo(ApplyPhase.COMMITTING);
    }

    @Test
    void resume_withAChangedConfig_isStillRefused() {
        var scenario = scenarioWithMatchingHash();
        var other = ClusterBootstrapConfigParser.parse(DOCKER.replace("count = 1", "count = 2")).unwrap();

        ApplyOrchestrator.resume(other, other, scenario.computes(), (plan, stored, wanted, recorder) -> Result.success(ApplyResult.applyResult(plan, 0, 0, 0)), Option::none)
                         .onSuccess(_ -> fail("hash mismatch"))
                         .onFailure(cause -> assertThat(cause.message()).contains("hash mismatch"));
    }

    // ---------------------------------------------------------------- the real executor over a real ledger

    private ClusterBootstrapConfig installDocker(HookedProvider provider) {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withLocalCompute(provider));
            }
        };

        return desired();
    }

    private static DiffPlan plan(DiffAction... additions) {
        return DiffPlan.diffPlan(List.of(additions), List.of(), List.of(), List.of());
    }

    @Test
    void resume_afterActionOneDone_providerCreateCallsForActionOneAreZero() {
        var scenario = scenarioWithMatchingHash();
        var desired = installDocker(scenario.provider);
        var local = sourceNameOrDefault("local");
        var plan = plan(new DiffAction.AddRole(local, NodeRole.CORE, 2), new DiffAction.AddRole(local, NodeRole.WORKER, 1));
        var keys = plan.additions().stream().map(ApplyAction::actionKey).toList();

        scenario.harness.provider.failNthCreate(3);
        withLedgerActions(scenario, keys);
        WaveExecutor.execute(plan, desired, desired, ApplyRecorder.applyRecorder(scenario.onDisk())).onSuccess(_ -> fail("the third create fails"));

        var createsBefore = scenario.harness.provider.totalCreates();
        var actionOneNodes = scenario.onDisk().resources().stream().filter(row -> row.actionKey().equals(keys.get(0))).map(ApplyResource::nodeId).toList();

        assertThat(actionOneNodes).hasSize(2);

        // resume: a fresh recorder over the ledger on disk, the same plan
        var resumed = ApplyRecorder.applyRecorder(scenario.onDisk());

        ApplyReconciler.reconcileIntents(resumed, scenario.computes()).unwrap();
        WaveExecutor.execute(plan, desired, desired, resumed).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.harness.provider.totalCreates() - createsBefore).as("only action two's node is created").isEqualTo(1);
        actionOneNodes.forEach(id -> assertThat(scenario.harness.provider.createCalls()).as(id).containsEntry(id, 1));
        scenario.harness.assertAtMostOneCreatePerNodeId();
        assertThat(scenario.onDisk().actions()).extracting(ApplyAction::status).containsOnly(ApplyAction.Status.DONE);
    }

    @Test
    void resume_aPartlyDoneAction_createsOnlyTheRemainder_withAFreshId_neverReusingAnAbsentOne() {
        var scenario = scenarioWithMatchingHash();
        var desired = installDocker(scenario.provider);
        var plan = plan(new DiffAction.AddRole(sourceNameOrDefault("local"), NodeRole.CORE, 3));
        var keys = plan.additions().stream().map(ApplyAction::actionKey).toList();

        scenario.harness.provider.failNthCreate(3);
        withLedgerActions(scenario, keys);
        WaveExecutor.execute(plan, desired, desired, ApplyRecorder.applyRecorder(scenario.onDisk())).onSuccess(_ -> fail("the third create fails"));

        var afterFirstRun = scenario.onDisk();
        var intended = afterFirstRun.resources().stream().filter(row -> row.state() == ApplyResource.State.INTENDED).map(ApplyResource::nodeId).toList();

        assertThat(intended).as("the failed create left its INTENDED row").hasSize(1);

        var resumed = ApplyRecorder.applyRecorder(afterFirstRun);

        ApplyReconciler.reconcileIntents(resumed, scenario.computes()).unwrap();
        WaveExecutor.execute(plan, desired, desired, resumed).onFailure(cause -> fail(cause.message()));

        var onDisk = scenario.onDisk();

        assertThat(onDisk.resources().stream().filter(row -> row.nodeId().equals(intended.getFirst())).findFirst().orElseThrow().state()).isEqualTo(ApplyResource.State.ABSENT);
        assertThat(onDisk.resources().stream().filter(row -> row.state() == ApplyResource.State.CREATED).count()).as("3 nodes in total").isEqualTo(3);
        assertThat(scenario.harness.provider.createCalls().get(intended.getFirst())).as("an id is used for at most one create call, ever").isEqualTo(1);
        scenario.harness.assertAtMostOneCreatePerNodeId();
        scenario.harness.assertNoLeak(onDisk.applyId(), onDisk);
    }

    /// DONE is final: even when the action's nodes have since vanished (the audit records them GONE), a resume does not
    /// create them again. The remainder arithmetic alone would, since nothing is recorded as existing any more.
    @Test
    void resume_aDoneAction_isNeverRedone_evenWhenItsNodesHaveVanished() {
        var scenario = scenarioWithMatchingHash();
        var desired = installDocker(scenario.provider);
        var plan = plan(new DiffAction.AddRole(sourceNameOrDefault("local"), NodeRole.CORE, 2));
        var key = ApplyAction.actionKey(plan.additions().getFirst());

        withLedgerActions(scenario, List.of(key));
        WaveExecutor.execute(plan, desired, desired, ApplyRecorder.applyRecorder(scenario.onDisk())).onFailure(cause -> fail(cause.message()));

        var createsBefore = scenario.harness.provider.totalCreates();
        var vanished = ApplyRecorder.applyRecorder(scenario.onDisk());

        scenario.onDisk().resources().forEach(row -> vanished.gone(row.nodeId()).unwrap());
        scenario.harness.provider.nodeIdsByServer().keySet().forEach(server -> scenario.harness.provider.terminate(new org.pragmatica.aether.environment.InstanceId(server)).await());

        WaveExecutor.execute(plan, desired, desired, ApplyRecorder.applyRecorder(scenario.onDisk())).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.onDisk().actions()).extracting(ApplyAction::status).containsExactly(ApplyAction.Status.DONE);
        assertThat(scenario.harness.provider.totalCreates() - createsBefore).as("a DONE action creates nothing on resume").isZero();
    }

    private void withLedgerActions(ApplyScenario scenario, List<String> keys) {
        var base = scenario.recorder.snapshot();

        ApplyState.save(ApplyState.applyState(base.applyId(), CLUSTER, base.configHash(), base.storedVersion(), base.startedAt(), base.phase(),
                                              keys.stream().map(key -> ApplyAction.applyAction(key, 0, ApplyAction.Status.PENDING)).toList(),
                                              List.of(), List.of(), List.of(), List.of())).unwrap();
    }
}
