// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// `--rollback` (cluster-apply spec 4.5).
class ApplyRollbackTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("rollback-test").unwrap();

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final AtomicInteger drains = new AtomicInteger();
    private final java.util.ArrayList<String> out = new java.util.ArrayList<>();

    private org.pragmatica.lang.Result<ApplyRollback.Report> rollback(ApplyScenario scenario) {
        return ApplyOrchestrator.rollback(CLUSTER, scenario.computes(), _ -> {
            drains.incrementAndGet();
            return org.pragmatica.lang.Result.unitResult();
        }, org.pragmatica.lang.Option::none, out::add);
    }

    @Test
    void rollback_terminatesEveryRecordedVm_newestFirst_andClosesTheLedger() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var servers = List.of(scenario.createRecorded("n-1"), scenario.createRecorded("n-2"), scenario.createRecorded("n-3"));

        rollback(scenario).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.provider.terminated).as("newest first").containsExactly(servers.get(2), servers.get(1), servers.get(0));
        assertThat(scenario.harness.provider.nodeIdsByServer()).isEmpty();
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).as("every VM gone: the ledger is closed").isFalse();
    }

    @Test
    void rollback_failureNamedExitNonZeroStateKept() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var servers = List.of(scenario.createRecorded("n-1"), scenario.createRecorded("n-2"));

        scenario.provider.onTerminate = id -> id.value().equals(servers.get(1))
                                              ? Causes.cause("provider refused the delete (test cause)").promise()
                                              : scenario.harness.provider.terminate(id);

        rollback(scenario).onSuccess(_ -> fail("a VM is still standing: not a success"))
                          .onFailure(cause -> assertThat(cause.message()).contains("Rollback incomplete")
                                                                         .contains("STILL RUNNING AND BILLED")
                                                                         .contains("n-2")
                                                                         .contains("hetzner")
                                                                         .contains(servers.get(1))
                                                                         .contains("hcloud server delete " + servers.get(1)));

        var onDisk = scenario.onDisk();

        assertThat(scenario.row("n-2").state()).isEqualTo(ApplyResource.State.TERMINATE_FAILED);
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.TERMINATED);
        assertThat(onDisk.phase()).isEqualTo(ApplyPhase.ROLLING_BACK);
        scenario.harness.assertNoLeak(onDisk.applyId(), onDisk);
    }

    @Test
    void secondRollback_zeroTerminateForTerminatedRows_retriesOnlyWhatIsLeft() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var servers = List.of(scenario.createRecorded("n-1"), scenario.createRecorded("n-2"));
        var refuse = new java.util.concurrent.atomic.AtomicBoolean(true);

        scenario.provider.onTerminate = id -> refuse.get() && id.value().equals(servers.get(1))
                                              ? Causes.cause("provider refused the delete (test cause)").promise()
                                              : scenario.harness.provider.terminate(id);
        rollback(scenario).onSuccess(_ -> fail("incomplete"));
        scenario.provider.terminated.clear();
        refuse.set(false);

        rollback(scenario).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.provider.terminated).as("the TERMINATED row is not touched again").containsExactly(servers.get(1));
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).isFalse();
    }

    @Test
    void rollback_foreignLabelledVm_notTerminated_andNamed() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var foreign = scenario.harness.provider.create("somebody-elses", java.util.Map.of(ApplyChaosHarness.APPLY_LABEL, "zzzzzzzzzzzzzzzzzzzzzzzzzz")).id().value();

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        scenario.recorder.created("n-1", foreign).unwrap();

        rollback(scenario).onSuccess(_ -> fail("a foreign VM must not be terminated"))
                          .onFailure(cause -> assertThat(cause.message()).contains("NOT terminated").contains(foreign));

        assertThat(scenario.provider.terminated).isEmpty();
        assertThat(scenario.harness.provider.nodeIdsByServer()).containsKey(foreign);
    }

    /// The rows' VMs are already gone: the listing says so, whatever a terminate would have answered.
    @Test
    void rollback_absenceDecidedByListing_notByTerminateError() {
        var shapes = List.<java.util.function.Function<InstanceId, Promise<Unit>>>of(
                id -> EnvironmentError.instanceNotFound(id).promise(),
                id -> EnvironmentError.terminateFailed(id, new IllegalStateException("not found")).promise(),
                _ -> Causes.cause("No such container (docker-shaped)").promise(),
                _ -> Promise.unitPromise());

        for (var shape : shapes) {
            var scenario = new ApplyScenario(ledger, CLUSTER);
            var server = scenario.createRecorded("n-1");

            scenario.harness.provider.terminate(new InstanceId(server)).await();
            scenario.provider.onTerminate = shape;

            rollback(scenario).onFailure(cause -> fail(cause.message()));

            assertThat(scenario.provider.terminated).as("a VM the listing does not show is never terminated").isEmpty();
        }
    }

    /// The VM is present, the terminate answers with an error although it did take effect: the re-listing decides.
    @Test
    void rollback_aFailedTerminateOfAVmThatIsGoneAfterwards_isTerminated() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");
        scenario.provider.onTerminate = id -> {
            scenario.harness.provider.terminate(id).await();

            return EnvironmentError.instanceNotFound(id).promise();
        };

        rollback(scenario).onFailure(cause -> fail(cause.message()));

        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).isFalse();
    }

    @Test
    void rollback_afterCommit_isRefused_andTouchesNothing() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");
        scenario.recorder.phase(ApplyPhase.COMMITTED).unwrap();

        rollback(scenario).onSuccess(_ -> fail("committed: apply the previous config instead"))
                          .onFailure(cause -> assertThat(cause.message()).contains("Refused").contains("committed"));

        assertThat(scenario.provider.terminated).isEmpty();
        assertThat(scenario.provider.filteredListings).as("refused before the provider was looked at").isZero();
    }

    @Test
    void rollback_fromAborted_terminatesWhatRemains() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");
        scenario.recorder.phase(ApplyPhase.ABORTED).unwrap();

        rollback(scenario).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.provider.terminated).hasSize(1);
    }

    @Test
    void rollback_aJoinedNodeIsDrainedFirst_andAnUnreachableClusterDoesNotStopTheTerminate() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var server = scenario.createRecorded("n-1");

        scenario.recorder.created("n-1", server).unwrap();
        ApplyState.save(scenario.recorder.snapshot().withNode(ApplyResource.applyResource(ApplyScenario.ACTION, "n-1", "hetzner", ApplyScenario.some(server), ApplyResource.State.JOINED))).unwrap();

        var fresh = ApplyRecorder.applyRecorder(scenario.onDisk());
        var drained = new java.util.ArrayList<String>();

        ApplyOrchestrator.rollback(CLUSTER, scenario.computes(), node -> {
            drained.add(node);
            return Causes.cause("cluster unreachable (test cause)").result();
        }, org.pragmatica.lang.Option::none, out::add).onFailure(cause -> fail(cause.message()));

        assertThat(drained).containsExactly("n-1");
        assertThat(scenario.provider.terminated).containsExactly(server);
        assertThat(out).anyMatch(line -> line.contains("terminating anyway"));
        assertThat(fresh.snapshot().resources()).isNotEmpty();
    }

    @Test
    void rollback_terminatingRowWriteFails_noTerminateIsIssued_theRowIsKept() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var original = ApplyLedger.tempWriter;
        var writes = new AtomicInteger();

        scenario.createRecorded("n-1");
        // the ROLLING_BACK phase write succeeds, the TERMINATING row write after it fails
        ApplyLedger.tempWriter = (temp, content) -> writes.incrementAndGet() >= 2
                                                    ? failWrite()
                                                    : original.write(temp, content);

        try {
            rollback(scenario).onSuccess(_ -> fail("nothing could be recorded, nothing may be terminated"))
                              .onFailure(cause -> assertThat(cause.message()).contains("TERMINATING row could not be written"));
        } finally {
            ApplyLedger.tempWriter = original;
        }

        assertThat(scenario.provider.terminated).isEmpty();
        assertThat(scenario.harness.provider.nodeIdsByServer()).hasSize(1);
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.CREATED);
    }

    private static Unit failWrite() {
        throw new java.io.UncheckedIOException(new java.io.IOException("No space left on device (test cause)"));
    }

    /// B1 of the P3 verification: ids are compared exactly. The ledger's `srv-1` carries ANOTHER cluster's labels; this apply
    /// owns `srv-10`, whose terminate fails, so it stays listed under both labels. A prefix match made `srv-1` "ours".
    @Test
    void rollback_aLedgerIdThatIsAPrefixOfAnOwnedServerId_isNeverTerminatedAsOurs() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var otherCluster = java.util.Map.of(ApplyChaosHarness.APPLY_LABEL, scenario.recorder.applyId(), "aether-cluster", "another-cluster");

        var prefixVictim = scenario.harness.provider.create("victim", otherCluster).id().value();

        scenario.harness.provider.nextServerNumber(10);

        var owned = scenario.harness.provider.create("owned", scenario.labels()).id().value();

        assertThat(owned).startsWith(prefixVictim);
        scenario.recorder.intend("n-victim", "hetzner").unwrap();
        scenario.recorder.created("n-victim", prefixVictim).unwrap();
        scenario.recorder.intend("n-owned", "hetzner").unwrap();
        scenario.recorder.created("n-owned", owned).unwrap();
        scenario.provider.onTerminate = id -> Causes.cause("provider refused the delete (test cause)").promise();

        rollback(scenario).onSuccess(_ -> fail("incomplete"));

        assertThat(scenario.provider.terminated).as("only the owned server was attempted").containsExactly(owned);
        assertThat(scenario.harness.provider.nodeIdsByServer()).containsKey(prefixVictim);
    }

    /// The audit adopts a labelled VM whose id merely EXTENDS a ledger id; a prefix match counted it as known.
    @Test
    void audit_aLabelledServerWhoseIdExtendsALedgerId_isAdopted_notTakenForTheLedgerOne() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var known = scenario.createRecorded("n-1");

        scenario.harness.provider.nextServerNumber(10);

        var extending = scenario.harness.provider.create("n-late", scenario.labels()).id().value();

        assertThat(extending).startsWith(known);

        var report = ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).unwrap();

        assertThat(report.adopted()).singleElement().asString().contains(extending);
        assertThat(scenario.row("n-late").state()).isEqualTo(ApplyResource.State.ADOPTED);
    }

    /// The second label: a server carrying this apply's label under ANOTHER cluster's label is not this apply's.
    @Test
    void rollback_aServerWithTheApplyLabelButAnotherClustersLabel_isRefusedAndNamed() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var stranger = scenario.harness.provider.create("stranger", java.util.Map.of(ApplyChaosHarness.APPLY_LABEL, scenario.recorder.applyId(), "aether-cluster", "another-cluster")).id().value();

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        scenario.recorder.created("n-1", stranger).unwrap();

        rollback(scenario).onSuccess(_ -> fail("not this cluster's"))
                          .onFailure(cause -> assertThat(cause.message()).contains("NOT terminated").contains(stranger));

        assertThat(scenario.provider.terminated).isEmpty();
    }

    @Test
    void audit_aServerWithTheApplyLabelButAnotherClustersLabel_isNeverAdopted() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");
        scenario.harness.provider.create("stranger", java.util.Map.of(ApplyChaosHarness.APPLY_LABEL, scenario.recorder.applyId(), "aether-cluster", "another-cluster"));

        var report = ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).unwrap();

        assertThat(report.adopted()).isEmpty();
        assertThat(scenario.onDisk().resources()).hasSize(1);
    }

    /// A selector the provider ignored returns servers that are not ours: the labels as REPORTED are checked again.
    @Test
    void rollback_aProviderThatIgnoresTheSelector_neverMakesAnUnlabelledServerOurs() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var unlabelled = scenario.harness.provider.create("unlabelled", java.util.Map.of()).id().value();

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        scenario.recorder.created("n-1", unlabelled).unwrap();
        scenario.provider.onFilteredList = _ -> scenario.harness.provider.listInstances();

        rollback(scenario).onSuccess(_ -> fail("not verifiably ours"));

        assertThat(scenario.provider.terminated).isEmpty();
    }

    /// Fail closed: a listing that fails, at either listing the decision needs, terminates nothing and says so.
    @Test
    void audit_aProviderThatIgnoresTheSelector_neverAdoptsAServerWithoutBothLabels() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");
        scenario.harness.provider.create("unlabelled", java.util.Map.of());
        scenario.harness.provider.create("other-cluster", java.util.Map.of(ApplyChaosHarness.APPLY_LABEL, scenario.recorder.applyId(), "aether-cluster", "another-cluster"));
        scenario.provider.onFilteredList = _ -> scenario.harness.provider.listInstances();

        var report = ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).unwrap();

        assertThat(report.adopted()).isEmpty();
        assertThat(report.relabelled()).hasSize(2);
        assertThat(scenario.onDisk().resources()).hasSize(1);
    }

    @Test
    void rollback_aFailedOwnershipListing_terminatesNothing() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");

        var listings = new AtomicInteger();

        // the audit's listing works, the ownership listing of the terminate step does not
        scenario.provider.onFilteredList = filter -> listings.incrementAndGet() == 1
                                                     ? scenario.harness.provider.listInstances(filter)
                                                     : Causes.cause("HTTP 503 (test cause)").promise();

        rollback(scenario).onSuccess(_ -> fail("could not look: not a success"));

        assertThat(scenario.provider.terminated).isEmpty();
        assertThat(out).anyMatch(line -> line.contains("could not be listed"));
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.CREATED);
    }

    @Test
    void rollback_aFailedUnfilteredListing_terminatesNothing() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");
        scenario.harness.provider.terminate(new InstanceId("srv-1")).await();
        scenario.provider.onList = _ -> Causes.cause("HTTP 503 (test cause)").promise();

        rollback(scenario).onSuccess(_ -> fail("could not decide between foreign and gone"));

        assertThat(scenario.provider.terminated).isEmpty();
    }

    /// The labels are checked again right before the call: a server whose labels vanish during the drain is not terminated.
    @Test
    void rollback_aServerThatStopsBeingOursDuringTheDrain_isNotTerminated() {
        var scenario = new ApplyScenario(ledger, CLUSTER);
        var server = scenario.createRecorded("n-1");

        ApplyState.save(scenario.recorder.snapshot().withNode(ApplyResource.applyResource(ApplyScenario.ACTION, "n-1", "hetzner", ApplyScenario.some(server), ApplyResource.State.JOINED))).unwrap();

        var listings = new AtomicInteger();

        scenario.provider.onFilteredList = filter -> {
            // 1st: audit, 2nd: presence before the drain, 3rd: right before the terminate (labels gone by then)
            return listings.incrementAndGet() >= 3
                   ? Promise.success(List.of())
                   : scenario.harness.provider.listInstances(filter);
        };

        ApplyOrchestrator.rollback(CLUSTER, scenario.computes(), _ -> org.pragmatica.lang.Result.unitResult(), org.pragmatica.lang.Option::none, out::add)
                         .onSuccess(_ -> fail("no longer verifiably ours"));

        assertThat(scenario.provider.terminated).isEmpty();
    }

    @Test
    void rollback_aHoldingRowWithNoServerId_isKept_nothingIsTerminated() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        ApplyState.save(scenario.recorder.snapshot().withNode(ApplyResource.applyResource(ApplyScenario.ACTION, "n-1", "hetzner", org.pragmatica.lang.Option.none(), ApplyResource.State.TERMINATING))).unwrap();

        rollback(scenario).onSuccess(_ -> fail("a row without a server id cannot be terminated"));

        assertThat(scenario.provider.terminated).isEmpty();
        assertThat(out).anyMatch(line -> line.contains("no server id"));
    }

    @Test
    void rollback_withNoLedger_saysThereIsNothingToRollBack() {
        ApplyOrchestrator.rollback(ClusterName.clusterName("never-applied").unwrap(), _ -> fail("no provider needed"), _ -> org.pragmatica.lang.Result.unitResult(),
                                   org.pragmatica.lang.Option::none, out::add)
                         .onSuccess(_ -> fail("nothing to roll back"))
                         .onFailure(cause -> assertThat(cause.message()).contains("Nothing to roll back"));
    }

    @Test
    void rollback_listsRemovalsAsNotRestored_informationOnly() {
        var scenario = new ApplyScenario(ledger, CLUSTER);

        scenario.createRecorded("n-1");
        ApplyState.save(scenario.recorder.snapshot().withDestroyedNode("old-node-7")).unwrap();

        rollback(scenario).onFailure(cause -> fail(cause.message()));

        assertThat(out).anyMatch(line -> line.contains("NOT restored") && line.contains("old-node-7"));
    }
}
