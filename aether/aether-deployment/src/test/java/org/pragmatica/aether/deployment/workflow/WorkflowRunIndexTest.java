// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_1;
import static org.assertj.core.api.Assertions.assertThat;


/// rc6 f2b §5: the run index the KV route feeds, and the durable announce derived from the applied transition. The index cases run
/// the real store's put/remove route ([WorkflowTestCluster]); the announce cases feed applied records to one index per node, as every
/// node of a cluster applies every record, and only the cluster-events owner raises.
class WorkflowRunIndexTest {
    private WorkflowTestCluster cluster;
    private WarningProbe probe;

    @BeforeEach
    void setUp() {
        cluster = new WorkflowTestCluster();
        probe = new WarningProbe();
    }

    private static WorkflowRunValue run(String id, long startedAt) {
        return WorkflowRunValue.admitted(id, "k", Map.of(), List.of("k:" + id), startedAt, startedAt + 1_000L);
    }

    private static ValuePut<WorkflowRunKey, WorkflowRunValue> put(WorkflowRunValue run) {
        return new ValuePut<>(new KVCommand.Put<>(new WorkflowRunKey(run.id()), run), Option.none());
    }

    private static List<OperatorWarningCode> codes(List<OperatorWarning> warnings) {
        return warnings.stream()
                       .map(OperatorWarning::code)
                       .toList();
    }

    //--- the view fed by the KV route

    @Test
    void absorbPut_viaTheStoreRoute_indexesTheCommittedRun_andIgnoresTheClaims() {
        var ledger = cluster.ledger(CORE_1);
        var created = run("wf-a", 10L);

        assertThat(ledger.create(created).await().unwrap()).isTrue();
        assertThat(cluster.index().runs()).containsExactly(created);
    }

    @Test
    void absorbRemove_viaTheStoreRoute_dropsTheRun() {
        var ledger = cluster.ledger(CORE_1);

        ledger.create(run("wf-a", 10L)).await().unwrap();
        ledger.create(run("wf-b", 20L)).await().unwrap();
        cluster.removeRaw("wf-a");
        assertThat(cluster.index().runs()).extracting(WorkflowRunValue::id).containsExactly("wf-b");
    }

    @Test
    void restore_replacesTheView_withTheSnapshotsRunsOnly_andAnnouncesNothing() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var stale = run("wf-stale", 5L);
        var restored = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(() -> true, probe.sink()));
        index.put(stale);
        index.restore(Map.<Object, Object> of(new WorkflowRunKey("wf-a"),
                             restored,
                             new WorkflowClaimKey("k:wf-a"),
                             new WorkflowClaimValue("wf-a"),
                             LeaderKey.INSTANCE,
                             "not an AetherValue"));
        assertThat(index.runs()).containsExactly(restored);
        assertThat(probe.settled()).as("a restore is not a transition").isEmpty();
    }

    //--- the announce: owner-only, never on replay, ESCALATED on entry and RESOLVED on exit

    @Test
    void announcer_escalation_raisesOneIncident_withTheDetail() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var running = run("wf-a", 10L);

        index.onTransition(WorkflowRunIndex.announcer(() -> true, probe.sink()));
        index.put(running);
        index.put(running.in(WorkflowState.ESCALATED, "attempt budget exhausted"));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED);
        assertThat(raised.getFirst().subject()).isEqualTo("wf-a");
        assertThat(raised.getFirst().message()).contains("attempt budget exhausted");
    }

    /// The replay bit is captured at absorb and carried with the run: a replayed escalation is committed history.
    @Test
    void announcer_replayedEscalation_announcesNothing_yetIndexesTheRun() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(() -> true, probe.sink()));
        WorkflowRunIndex.absorbPut(index, () -> true, put(escalated));
        assertThat(index.runs()).containsExactly(escalated);
        assertThat(probe.settled()).isEmpty();
    }

    /// Every node applies every run; three nodes, one owner of the cluster-events partition: exactly one raise per transition.
    @Test
    void announcer_onEveryNode_raisesOnlyOnTheClusterEventsOwner_once() {
        var nodes = List.of(WorkflowRunIndex.workflowRunIndex(), WorkflowRunIndex.workflowRunIndex(), WorkflowRunIndex.workflowRunIndex());

        for (int n = 0; n < nodes.size(); n++) {
            var owner = n == 1;

            nodes.get(n).onTransition(WorkflowRunIndex.announcer(() -> owner, probe.sink()));
        }
        var running = run("wf-a", 10L);
        var escalated = running.in(WorkflowState.ESCALATED, "incident");

        for (var node : nodes) {
            WorkflowRunIndex.absorbPut(node, () -> false, put(running));
            WorkflowRunIndex.absorbPut(node, () -> false, put(escalated));
            WorkflowRunIndex.absorbPut(node, () -> false, put(escalated.resumed(99_000L)));
        }
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    @Test
    void announcer_resumeOfAnEscalatedRun_raisesResolved() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(() -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated.resumed(99_000L));
        var raised = probe.settled();

        assertThat(codes(raised)).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
        assertThat(raised.getLast().subject()).isEqualTo("wf-a");
        assertThat(raised.getLast().message()).contains("RUNNING");
    }

    /// An abandon ends the escalated run as FAILED: that also leaves ESCALATED, so it resolves the incident (Architect ruling Q4).
    @Test
    void announcer_abandonOfAnEscalatedRun_raisesResolved() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "incident");

        index.onTransition(WorkflowRunIndex.announcer(() -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated.in(WorkflowState.FAILED, "abandoned"));
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
    }

    /// A run that escalates again after a resume opens a new incident; a re-applied ESCALATED value is not a new transition.
    @Test
    void announcer_reEscalationAfterResume_opensANewIncident_andAnUnchangedStateRaisesNothing() {
        var index = WorkflowRunIndex.workflowRunIndex();
        var escalated = run("wf-a", 10L).in(WorkflowState.ESCALATED, "first");
        var resumed = escalated.resumed(99_000L);
        var again = resumed.in(WorkflowState.ESCALATED, "second");

        index.onTransition(WorkflowRunIndex.announcer(() -> true, probe.sink()));
        index.put(escalated);
        index.put(escalated);
        index.put(resumed);
        index.put(again);
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED,
                                                           OperatorWarningCode.WORKFLOW_RESOLVED,
                                                           OperatorWarningCode.WORKFLOW_ESCALATED);
    }

    /// End to end over the real store route: the ledger's resume commit of an escalated run raises RESOLVED.
    @Test
    void announcer_ledgerResumeCommit_viaTheStoreRoute_raisesResolved() {
        var ledger = cluster.ledger(CORE_1);
        var running = run("wf-a", 10L);
        var escalated = running.in(WorkflowState.ESCALATED, "incident");

        cluster.announceTo(() -> true, probe.sink());
        ledger.create(running).await().unwrap();
        assertThat(ledger.commit(running, escalated).await().unwrap()).isTrue();
        assertThat(ledger.commit(escalated, escalated.resumed(99_000L)).await().unwrap()).isTrue();
        assertThat(codes(probe.settled())).containsExactly(OperatorWarningCode.WORKFLOW_ESCALATED, OperatorWarningCode.WORKFLOW_RESOLVED);
    }
}
