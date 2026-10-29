// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// Write-ahead recording of the VMs a wave creates (cluster-apply spec P2): INTENDED before the provider is called,
/// CREATED after it answered, labels checked, every terminate recorded.
class WaveExecutorLedgerTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("prod-cluster").unwrap();
    private static final String CLOUD = """
            config_version = "1.0.0"
            [cluster]
            name = "prod-cluster"
            version = "1.0.0"
            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            credentials = "hcloud-token"
            [source.eu-1.worker]
            count = 2
            """;
    private static final String DOCKER = """
            config_version = "1.0.0"
            [cluster]
            name = "prod-cluster"
            version = "1.0.0"
            [source.local]
            type = "docker"
            [source.local.core]
            count = 1
            [source.local.worker]
            count = 1
            """;
    private static final String FORGE_AND_SSH = """
            config_version = "1.0.0"
            [cluster]
            name = "prod-cluster"
            version = "1.0.0"
            [source.sim]
            type = "forge"
            [source.sim.core]
            count = 1
            [source.dc-1]
            type = "ssh"
            [source.dc-1.core]
            hosts = ["10.1.0.1", "10.1.0.2"]
            """;
    private static final WaveNodeProvisioning.JoinInputs INPUTS = new WaveNodeProvisioning.JoinInputs("wave-secret",
                                                                                                        List.of("aether-prod-cluster-node-01k0peer000000000000000000:10.0.0.1:8090"));
    private static final String ACTION = "AddRole|eu-1|worker|->2";

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private ApplyChaosHarness harness = new ApplyChaosHarness();
    private final ApplyLedger.TempWriter realWriter = ApplyLedger.tempWriter;

    @AfterEach
    void restore() {
        ApplyLedger.tempWriter = realWriter;
        ProviderResolver.dockerFactoryOverride = null;
    }

    /// The harness provider with hooks: a test replaces what a create or a terminate does.
    private class Hooked implements ComputeProvider {
        Function<ProvisionSpec, Promise<InstanceInfo>> onProvision = spec -> harness.provider.provision(spec);
        Function<InstanceId, Promise<Unit>> onTerminate = id -> harness.provider.terminate(id);

        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            return onProvision.apply(spec);
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return onTerminate.apply(instanceId);
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return harness.provider.listInstances();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return harness.provider.instanceStatus(instanceId);
        }
    }

    private ClusterBootstrapConfig parse(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).unwrap();
    }

    private Result<List<org.pragmatica.aether.environment.ProvisionedNode>> provisionCloud(ComputeProvider compute, int count, ApplyRecorder recorder) {
        var desired = parse(CLOUD);

        return WaveExecutor.provisionCloudNodes(compute, desired, desired.sources().get("eu-1"), NodeRole.WORKER, count, INPUTS, recorder);
    }

    private ApplyRecorder cloudRecorder() {
        return ledger.recorder(CLUSTER, List.of(ACTION));
    }

    /// Arms `ApplyLedger.tempWriter` to fail from the `n`th write on (1-based, counted from now).
    private void failWritesFrom(int n) {
        var writes = new AtomicInteger();

        ApplyLedger.tempWriter = (temp, content) -> writes.incrementAndGet() >= n
                                                    ? failWrite()
                                                    : realWriter.write(temp, content);
    }

    private static Unit failWrite() {
        throw new java.io.UncheckedIOException(new java.io.IOException("No space left on device (test cause)"));
    }

    // ---------------------------------------------------------------- H2b and the kill points

    /// H2b: the process is killed right after the provider answered. The VM exists and is billed; the invariant of
    /// spec 4.2 is that its row (INTENDED) was written BEFORE the provider was called. At the P1 head nothing writes a
    /// row at all, so this fails there.
    @Test
    void createThenKill_everyVmTheProviderHoldsHasALedgerRow() {
        var hooked = new Hooked();
        var recorder = cloudRecorder();

        hooked.onProvision = spec -> {
            harness.provider.provision(spec);
            throw new ApplyChaosHarness.Killed(ApplyChaosHarness.Step.S2);
        };

        assertThatThrownBy(() -> provisionCloud(hooked, 1, recorder)).isInstanceOf(ApplyChaosHarness.Killed.class);

        assertThat(harness.provider.totalCreates()).as("the create reached the provider").isEqualTo(1);
        harness.assertEveryVmHasARow(ledger.ledger(CLUSTER));
    }

    /// The spec's enumerated kill points: a scenario of one action creating 2 nodes (and, for S6/S7, a second create
    /// that fails so the first node is torn down) is killed at every step a process can reach, at each occurrence.
    /// After every kill: no VM lacks a row, a node id was created at most once, and apart from the one window the
    /// resume lookup (P3) closes (S2: the VM exists, only INTENDED is written) the ledger equals the provider.
    @Test
    void killAtEveryStepAndOccurrence_neverLeavesAVmWithoutARow_andTheLeakCheckHoldsOutsideTheLookupWindow() {
        var points = List.of(new Object[] {ApplyRecorder.Step.S0, 1, false},
                             new Object[] {ApplyRecorder.Step.S1, 1, false},
                             new Object[] {ApplyRecorder.Step.S1, 2, false},
                             new Object[] {ApplyRecorder.Step.S2, 1, false},
                             new Object[] {ApplyRecorder.Step.S2, 2, false},
                             new Object[] {ApplyRecorder.Step.S3, 1, false},
                             new Object[] {ApplyRecorder.Step.S3, 2, false},
                             new Object[] {ApplyRecorder.Step.S5, 1, false},
                             new Object[] {ApplyRecorder.Step.S6, 1, true},
                             new Object[] {ApplyRecorder.Step.S7, 1, true});

        for (var point : points) {
            var step = (ApplyRecorder.Step) point[0];
            var nth = (Integer) point[1];
            var tearDownScenario = (Boolean) point[2];

            harness = new ApplyChaosHarness();

            var recorder = cloudRecorder();
            var label = step + "#" + nth + (tearDownScenario ? " (teardown)" : "");

            ApplyRecorder.stepHook = reached -> harness.reach(ApplyChaosHarness.Step.valueOf(reached.name()));
            harness.armKill(ApplyChaosHarness.Step.valueOf(step.name()), nth);

            if (tearDownScenario) {
                harness.provider.failNthCreate(2);
            }

            assertThatThrownBy(() -> runActionOfTwoNodes(recorder)).as(label).isInstanceOf(ApplyChaosHarness.Killed.class);

            var onDisk = ledger.ledger(CLUSTER);

            harness.assertEveryVmHasARow(onDisk);
            harness.assertAtMostOneCreatePerNodeId();

            if (step == ApplyRecorder.Step.S2) {
                assertThat(harness.leakReport(onDisk.applyId(), onDisk)).as(label + ": the lookup window").hasSize(1);
            } else {
                assertThat(harness.leakReport(onDisk.applyId(), onDisk)).as(label).isEmpty();
            }
        }
    }

    private void runActionOfTwoNodes(ApplyRecorder recorder) {
        recorder.beginAction(ACTION).unwrap();
        provisionCloud(new Hooked(), 2, recorder).onSuccess(_ -> recorder.finishAction().unwrap());
    }

    // ---------------------------------------------------------------- cloud: the write-ahead sequence

    @Test
    void successfulAction_recordsOneCreatedRowPerNodeWithProviderAndServerId() {
        var recorder = cloudRecorder();

        provisionCloud(new Hooked(), 2, recorder).onFailure(cause -> fail(cause.message()));

        var onDisk = ledger.ledger(CLUSTER);

        assertThat(onDisk.resources()).hasSize(2)
                                      .allSatisfy(row -> {
                                          assertThat(row.state()).isEqualTo(ApplyResource.State.CREATED);
                                          assertThat(row.provider()).isEqualTo("hetzner");
                                          assertThat(row.actionKey()).isEqualTo(ACTION);
                                          assertThat(row.serverId().isPresent()).isTrue();
                                      });
        harness.assertNoLeak(onDisk.applyId(), onDisk);
        harness.assertAtMostOneCreatePerNodeId();
    }

    @Test
    void intentRowIsDurableWhenProviderCreateIsCalled() {
        var hooked = new Hooked();
        var seen = new ArrayList<ApplyResource>();

        hooked.onProvision = spec -> {
            ledger.ledger(CLUSTER).resources().stream()
                  .filter(row -> row.nodeId().equals(spec.context().nodeId().or("")))
                  .forEach(seen::add);
            return harness.provider.provision(spec);
        };

        provisionCloud(hooked, 1, cloudRecorder()).onFailure(cause -> fail(cause.message()));

        assertThat(seen).as("the row existed, durably, when the provider was called").hasSize(1);
        assertThat(seen.getFirst().state()).isEqualTo(ApplyResource.State.INTENDED);
        assertThat(seen.getFirst().serverId().isPresent()).isFalse();
    }

    @Test
    void intentWriteFails_noProviderCall_noVm() {
        var recorder = cloudRecorder();

        failWritesFrom(1);

        provisionCloud(new Hooked(), 2, recorder).onSuccess(_ -> fail("an apply that cannot record its intent must not create"));

        assertThat(harness.provider.totalCreates()).as("no create call without an intent row").isZero();
        harness.assertNoApplyLabelledVm();
    }

    @Test
    void createdRowWriteFails_theReturnedVmIsTerminatedAtOnce() {
        var recorder = cloudRecorder();

        failWritesFrom(2);

        provisionCloud(new Hooked(), 1, recorder).onSuccess(_ -> fail("an unrecorded VM must fail the action"))
                                                 .onFailure(cause -> assertThat(cause.message()).contains("apply failed part-way"));
        ApplyLedger.tempWriter = realWriter;

        assertThat(harness.provider.totalTerminates()).isEqualTo(1);
        assertThat(harness.provider.nodeIdsByServer()).as("the VM is gone").isEmpty();
        harness.assertEveryVmHasARow(ledger.ledger(CLUSTER));
    }

    @Test
    void createdRowWriteFails_andTheTerminateFails_theVmIsNamedStillRunningAndBilled() {
        var hooked = new Hooked();
        var recorder = cloudRecorder();

        hooked.onTerminate = _ -> Causes.cause("provider refused the delete (test cause)").promise();
        failWritesFrom(2);

        provisionCloud(hooked, 1, recorder).onSuccess(_ -> fail("must fail"))
                                          .onFailure(cause -> assertThat(cause.message()).contains("STILL RUNNING AND BILLED")
                                                                                            .contains("srv-1")
                                                                                            .contains("hcloud server delete srv-1"));

        assertThat(harness.provider.nodeIdsByServer()).as("the VM stands").hasSize(1);
        ApplyLedger.tempWriter = realWriter;
        harness.assertEveryVmHasARow(ledger.ledger(CLUSTER));
    }

    @Test
    void providerDropsTheApplyLabel_failsAndTerminatesTheVm() {
        var hooked = new Hooked();

        hooked.onProvision = spec -> {
            var created = harness.provider.provision(spec).await().unwrap();
            var stripped = new java.util.HashMap<>(created.tags());

            stripped.remove(WaveNodeProvisioning.APPLY_LABEL);

            return Promise.success(new InstanceInfo(created.id(), created.status(), created.addresses(), created.type(), stripped));
        };

        provisionCloud(hooked, 1, cloudRecorder()).onSuccess(_ -> fail("a VM without its apply label must fail the action"))
                                                 .onFailure(cause -> assertThat(cause.message()).contains("apply failed part-way")
                                                                                                   .contains("labels"));

        assertThat(harness.provider.totalTerminates()).isEqualTo(1);
        assertThat(harness.provider.nodeIdsByServer()).isEmpty();
    }

    @Test
    void secondCreateFails_theFirstNodeIsTornDownThroughTerminatingAndTerminatedRows() {
        harness.provider.failNthCreate(2);

        provisionCloud(new Hooked(), 2, cloudRecorder()).onSuccess(_ -> fail("the second create fails"));

        var onDisk = ledger.ledger(CLUSTER);

        assertThat(onDisk.resources()).extracting(ApplyResource::state)
                                      .containsExactlyInAnyOrder(ApplyResource.State.TERMINATED, ApplyResource.State.INTENDED);
        assertThat(harness.provider.totalTerminates()).isEqualTo(1);
        harness.assertNoLeak(onDisk.applyId(), onDisk);
    }

    @Test
    void providerReturnsAnotherNodeId_failsAndTerminatesTheVm() {
        var hooked = new Hooked();

        hooked.onProvision = spec -> {
            var created = harness.provider.provision(spec).await().unwrap();

            return Promise.success(new InstanceInfo(created.id(), created.status(), created.addresses(), created.type(), created.tags(),
                                                    org.pragmatica.lang.Option.some("somebody-else"), org.pragmatica.lang.Option.none()));
        };

        provisionCloud(hooked, 1, cloudRecorder()).onSuccess(_ -> fail("a VM that reports another identity must fail the action"));

        assertThat(harness.provider.totalTerminates()).isEqualTo(1);
    }

    @Test
    void tearDownTerminateFails_theFirstNodeIsRecordedTerminateFailed_andNamedStillBilled() {
        var hooked = new Hooked();

        harness.provider.failNthCreate(2);
        hooked.onTerminate = _ -> Causes.cause("provider refused the delete (test cause)").promise();

        provisionCloud(hooked, 2, cloudRecorder()).onSuccess(_ -> fail("the second create fails"))
                                                  .onFailure(cause -> assertThat(cause.message()).contains("STILL RUNNING AND BILLED"));

        var onDisk = ledger.ledger(CLUSTER);

        assertThat(onDisk.resources()).extracting(ApplyResource::state)
                                      .containsExactlyInAnyOrder(ApplyResource.State.TERMINATE_FAILED, ApplyResource.State.INTENDED);
        harness.assertNoLeak(onDisk.applyId(), onDisk);
    }

    @Test
    void filteredListing_matchesTheShortContainerIdDockerPsPrints_andNothingElse() {
        var fullId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        var info = new InstanceInfo(new InstanceId(fullId), org.pragmatica.aether.environment.InstanceStatus.RUNNING, List.of(),
                                    org.pragmatica.aether.environment.InstanceType.ON_DEMAND, Map.of(),
                                    org.pragmatica.lang.Option.some("n-1"), org.pragmatica.lang.Option.none());

        var listing = new ArrayList<String>();
        ComputeProvider lister = new Hooked() {
            @Override
            public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
                return Promise.success(listing.stream().map(id -> new InstanceInfo(new InstanceId(id), org.pragmatica.aether.environment.InstanceStatus.RUNNING, List.of(),
                                                                                   org.pragmatica.aether.environment.InstanceType.ON_DEMAND, Map.of())).toList());
            }
        };

        listing.add("0123456789ab");
        assertThat(WaveNodeProvisioning.filteredListing(lister, info, "n-1", "applyid").isSuccess()).as("short id of the same container").isTrue();

        listing.clear();
        listing.add(fullId);
        assertThat(WaveNodeProvisioning.filteredListing(lister, info, "n-1", "applyid").isSuccess()).as("full id").isTrue();

        listing.clear();
        listing.add("ffffffffffff");
        assertThat(WaveNodeProvisioning.filteredListing(lister, info, "n-1", "applyid").isSuccess()).as("another container").isFalse();

        listing.clear();
        listing.add("");
        assertThat(WaveNodeProvisioning.filteredListing(lister, info, "n-1", "applyid").isSuccess()).as("a blank listed id matches nothing").isFalse();

        listing.clear();
        assertThat(WaveNodeProvisioning.filteredListing(lister, info, "n-1", "applyid").isSuccess()).as("nothing listed").isFalse();

        listing.add("0123456789ab");
        assertThat(WaveNodeProvisioning.filteredListing(lister, info, "another-node", "applyid").isSuccess()).as("identity differs").isFalse();
    }

    @Test
    void terminatingRowWriteFails_noTerminateIsIssued_theVmStaysRecorded() {
        harness.provider.failNthCreate(2);
        var recorder = cloudRecorder();
        var hooked = new Hooked();
        var writes = new AtomicInteger();

        // writes: intent#1, created#1, intent#2 succeed; the TERMINATING write of node 1 is the 4th and fails
        ApplyLedger.tempWriter = (temp, content) -> writes.incrementAndGet() >= 4
                                                    ? failWrite()
                                                    : realWriter.write(temp, content);

        provisionCloud(hooked, 2, recorder).onSuccess(_ -> fail("the second create fails"));
        ApplyLedger.tempWriter = realWriter;

        assertThat(harness.provider.totalTerminates()).as("no terminate without the TERMINATING row").isZero();
        harness.assertNoLeak(ledger.ledger(CLUSTER).applyId(), ledger.ledger(CLUSTER));
    }

    @Test
    void recorder_refusesACreateBeforeAnyActionStarted() {
        var state = ApplyStateFixtures.state(CLUSTER);

        ApplyState.save(state).unwrap();

        var recorder = ApplyRecorder.applyRecorder(state);

        recorder.intend("n-1", "hetzner").onSuccess(_ -> fail("a row without an action cannot be read back"))
                .onFailure(cause -> assertThat(cause.message()).contains("no started action"));
    }

    // ---------------------------------------------------------------- docker

    private ClusterBootstrapConfig installDocker(Hooked hooked) {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withLocalCompute(hooked));
            }
        };

        return parse(DOCKER);
    }

    private DiffPlan plan(DiffAction... additions) {
        return DiffPlan.diffPlan(List.of(additions), List.of(), List.of(), List.of());
    }

    private ApplyRecorder recorderFor(DiffPlan plan) {
        return ledger.recorder(CLUSTER, plan.additions().stream().map(ApplyAction::actionKey).toList());
    }

    /// #1726: the first action's nodes are in the ledger, with provider and server id, when the second action fails.
    @Test
    void failAction2_action1NodesAreInLedgerWithProviderAndServerId() {
        var desired = installDocker(new Hooked());
        var local = sourceNameOrDefault("local");
        var plan = plan(new DiffAction.AddRole(local, NodeRole.CORE, 2), new DiffAction.AddRole(local, NodeRole.WORKER, 1));

        harness.provider.failNthCreate(3);

        WaveExecutor.execute(plan, desired, desired, recorderFor(plan)).onSuccess(_ -> fail("the third create fails"));

        var onDisk = ledger.ledger(CLUSTER);
        var action1 = onDisk.resources().stream().filter(row -> row.actionKey().startsWith("AddRole|local|core")).toList();

        assertThat(action1).hasSize(2).allSatisfy(row -> {
            assertThat(row.state()).isEqualTo(ApplyResource.State.CREATED);
            assertThat(row.provider()).isEqualTo("docker");
            assertThat(row.serverId().isPresent()).isTrue();
        });
        assertThat(onDisk.actions()).extracting(ApplyAction::status)
                                    .containsExactly(ApplyAction.Status.DONE, ApplyAction.Status.IN_PROGRESS);
        harness.assertEveryVmHasARow(onDisk);
    }

    @Test
    void dockerAdd_intentRowIsDurableBeforeTheContainerCreate_andIdsAreMintedBeforeTheCall() {
        var hooked = new Hooked();
        var desired = installDocker(hooked);
        var seen = new ArrayList<String>();

        hooked.onProvision = spec -> {
            var nodeId = spec.context().nodeId().or("");

            ledger.ledger(CLUSTER).resources().stream()
                  .filter(row -> row.nodeId().equals(nodeId) && row.state() == ApplyResource.State.INTENDED)
                  .forEach(row -> seen.add(row.nodeId()));
            return harness.provider.provision(spec);
        };

        var plan = plan(new DiffAction.AddRole(sourceNameOrDefault("local"), NodeRole.CORE, 2));

        WaveExecutor.execute(plan, desired, desired, recorderFor(plan)).onFailure(cause -> fail(cause.message()));

        assertThat(seen).hasSize(2).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).startsWith("aether-prod-cluster-node-"));
    }

    @Test
    void dockerAdd_createdRowCarriesTheContainerId_andTheApplyLabelReachedTheContainer() {
        var desired = installDocker(new Hooked());
        var plan = plan(new DiffAction.AddRole(sourceNameOrDefault("local"), NodeRole.CORE, 1));
        var recorder = recorderFor(plan);

        WaveExecutor.execute(plan, desired, desired, recorder).onFailure(cause -> fail(cause.message()));

        var onDisk = ledger.ledger(CLUSTER);

        assertThat(onDisk.resources()).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo(ApplyResource.State.CREATED);
            assertThat(row.provider()).isEqualTo("docker");
            assertThat(harness.provider.inventoryByLabel(ApplyChaosHarness.APPLY_LABEL, onDisk.applyId())).containsExactly(row.serverId().or(""));
        });
        harness.assertNoLeak(onDisk.applyId(), onDisk);
    }

    /// The Docker provider re-stamps the instance it returns from `docker inspect` (no custom labels), so the check is the
    /// daemon's label filter: a container that does not carry the apply label is not listed and fails the action.
    @Test
    void dockerContainerWithoutTheApplyLabel_failsAndIsTerminated() {
        var hooked = new Hooked();
        var desired = installDocker(hooked);

        hooked.onProvision = spec -> harness.provider.provision(withoutApplyLabel(spec));

        var plan = plan(new DiffAction.AddRole(sourceNameOrDefault("local"), NodeRole.CORE, 1));

        WaveExecutor.execute(plan, desired, desired, recorderFor(plan)).onSuccess(_ -> fail("a container without the apply label must fail"))
                    .onFailure(cause -> assertThat(cause.message()).contains("labels"));

        assertThat(harness.provider.totalTerminates()).isEqualTo(1);
        assertThat(harness.provider.nodeIdsByServer()).isEmpty();
    }

    private static ProvisionSpec withoutApplyLabel(ProvisionSpec spec) {
        var context = spec.context();
        var tags = new java.util.HashMap<>(context.extraTags());

        tags.remove(WaveNodeProvisioning.APPLY_LABEL);

        return new ProvisionSpec(spec.instanceType(),
                                 spec.instanceSize(),
                                 spec.pool(),
                                 org.pragmatica.aether.environment.ProvisionContext.provisionContext(context.clusterName(),
                                                                                                    context.role(),
                                                                                                    context.sourceName(),
                                                                                                    context.nodeId(),
                                                                                                    context.peers(),
                                                                                                    context.coreMax(),
                                                                                                    context.provisionedBy(),
                                                                                                    tags),
                                 spec.imageId(),
                                 spec.userData(),
                                 spec.placement());
    }

    /// Parity with the group path: the per-node spec is the group path's spec for the same node id, plus the labels.
    @Test
    void dockerPerNodeSpec_isTheGroupPathsSpecForTheSameNodeId_plusOnlyTheLabels() {
        var group = NodeGroupConfig.nodeGroupConfig(sourceNameOrDefault("local"), "core", 1, "default", "default", Map.of("aether-cluster", "prod-cluster"));
        var labels = WaveExecutor.provisionTags(CLUSTER, sourceNameOrDefault("local"), NodeRole.CORE, "0123456789abcdefghijklmnop");
        var groupPath = CloudProviderSupport.provisionSpecFor(group, "aether-prod-cluster-node-x", Map.of()).unwrap();
        var perNode = CloudProviderSupport.provisionSpecFor(group, "aether-prod-cluster-node-x", labels).unwrap();

        assertThat(groupPath.context().extraTags()).isEmpty();
        assertThat(perNode.context().extraTags()).isEqualTo(labels);

        var context = perNode.context();
        var withoutLabels = org.pragmatica.aether.environment.ProvisionContext.provisionContext(context.clusterName(),
                                                                                                context.role(),
                                                                                                context.sourceName(),
                                                                                                context.nodeId(),
                                                                                                context.peers(),
                                                                                                context.coreMax(),
                                                                                                context.provisionedBy(),
                                                                                                Map.of());

        assertThat(withoutLabels).isEqualTo(groupPath.context());
        assertThat(perNode.instanceType()).isEqualTo(groupPath.instanceType());
        assertThat(perNode.instanceSize()).isEqualTo(groupPath.instanceSize());
        assertThat(perNode.pool()).isEqualTo(groupPath.pool());
        assertThat(perNode.placement()).isEqualTo(groupPath.placement());
        assertThat(perNode.imageId()).isEqualTo(groupPath.imageId());
        assertThat(perNode.userData()).isEqualTo(groupPath.userData());
    }

    // ---------------------------------------------------------------- sources that create nothing billable

    @Test
    void nonCloudSources_neverEnterTheLedger() {
        var desired = parse(FORGE_AND_SSH);
        var plan = plan(new DiffAction.AddRole(sourceNameOrDefault("sim"), NodeRole.CORE, 2),
                        new DiffAction.AddRole(sourceNameOrDefault("dc-1"), NodeRole.CORE, 2));

        WaveExecutor.execute(plan, desired, desired, recorderFor(plan)).onFailure(cause -> fail(cause.message()));

        assertThat(ledger.ledger(CLUSTER).resources()).as("FORGE placeholders and the operator's SSH hosts are not billable VMs").isEmpty();
        assertThat(harness.provider.totalCreates()).isZero();
    }
}
