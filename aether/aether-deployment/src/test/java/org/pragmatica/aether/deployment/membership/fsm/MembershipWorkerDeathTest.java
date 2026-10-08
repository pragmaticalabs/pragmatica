// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1717 — a worker has only one reliable death plane on a core (no transport link in the hierarchy, or no
/// SWIM probe history when killed young), so requiring both left a force-killed worker SUSPECT for good. For
/// an explicit non-core role EITHER plane arms the eviction backstop, and evidence inside the window vetoes it.
class MembershipWorkerDeathTest {
    private static final long BACKSTOP_MS = 300;

    private static MembershipFsm fsm() {
        return MembershipFsm.membershipFsm(0L, timeSpan(BACKSTOP_MS).millis(), timeSpan(BACKSTOP_MS).millis(), timeSpan(BACKSTOP_MS).millis());
    }

    private static MemberDescriptor worker() {
        return new MemberDescriptor(Option.none(), "worker", "source");
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                return condition.getAsBoolean();
            }
        }

        return condition.getAsBoolean();
    }

    private static String state(MembershipFsm fsm, NodeId id) {
        return fsm.memberStates().get(id);
    }

    @Test
    void killedWorker_swimFaultyWithNoEvidence_reachesDead_andEmitsTheRemovedEdge() {
        var membership = fsm();
        var edges = new CopyOnWriteArrayList<MembershipDeltaEdge>();
        var id = new NodeId("worker-1");

        membership.onMembershipDelta(edges::add);
        membership.onWorkerAdmissionHealthy(id, 1, worker());
        assertThat(state(membership, id)).as("precondition: admitted worker is a MEMBER").isEqualTo("Member");
        membership.onSwimFaulty(id, 1);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 15_000))
            .as("a worker SWIM declared FAULTY, with no evidence of life, must reach DEAD; state=%s", state(membership, id))
            .isTrue();
        assertThat(edges.stream().map(MembershipDeltaEdge::kind).toList())
            .as("DEAD emits the REMOVED edge that drives the worker leave")
            .contains(MembershipDeltaEdge.Kind.REMOVED);
    }

    @Test
    void workerFaulty_butGovernorKeepsReportingIt_isNeverEvicted() {
        var membership = fsm();
        var id = new NodeId("worker-2");
        var governor = new NodeId("governor");

        membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());
        membership.onSwimFaulty(id, 1);
        membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS))
            .as("evidence of life inside the window vetoes the eviction; state=%s", state(membership, id))
            .isFalse();
        assertThat(state(membership, id)).isEqualTo("Member");
    }

    /// The other plane: a worker killed before SWIM ever probed it (as in TerminatedWorkerGhostTest) produces
    /// a transport disconnect and no SWIM-FAULTY at all.
    @Test
    void killedWorker_livenessGoneWithoutAnySwimFaulty_reachesDead() {
        var membership = fsm();
        var id = new NodeId("worker-4");

        membership.onWorkerAdmissionHealthy(id, 1, worker());
        membership.onPeerDisconnected(id);
        membership.onLivenessGone(id);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 15_000))
            .as("transport-gone alone is enough for a worker; state=%s", state(membership, id))
            .isTrue();
    }

    @Test
    void worker_livenessGone_butGovernorKeepsReportingIt_isNeverEvicted() {
        var membership = fsm();
        var id = new NodeId("worker-5");
        var governor = new NodeId("governor");

        membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());
        membership.onLivenessGone(id);
        membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
        assertThat(state(membership, id)).isEqualTo("Member");
    }

    /// A core needs BOTH planes: a transport drop alone must never kill it either.
    @Test
    void core_livenessGoneAlone_isNeverEvicted() {
        var membership = fsm();
        var id = new NodeId("core-2");

        membership.seed(java.util.Set.of(id));
        membership.onLivenessGone(id);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
    }

    /// Control for the veto test above: the same sequence WITHOUT the evidence after the FAULTY edge ends
    /// DEAD, so the veto test's "still a MEMBER" cannot be a vacuous pass.
    @Test
    void workerFaulty_withEvidenceOnlyBeforeTheFaultyEdge_isEvicted() {
        var membership = fsm();
        var id = new NodeId("worker-3");

        membership.onGovernorHealthy(id, "community", new NodeId("governor"), 1, 1, worker());
        membership.onSwimFaulty(id, 1);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 15_000))
            .as("evidence that predates the FAULTY edge does not veto it; state=%s", state(membership, id))
            .isTrue();
    }

    /// (c) Slow but alive: the death signal arrives and the governor's evidence follows late — inside the
    /// window (production cadence is the 1 s ping interval against a 15 s window). Never DEAD, even when the
    /// signal repeats.
    @Test
    void slowButAliveWorker_evidenceInsideTheWindow_isNeverEvicted() throws InterruptedException {
        var membership = fsm();
        var id = new NodeId("worker-slow");
        var governor = new NodeId("governor");

        membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());
        for (int round = 0; round < 4; round++) {
            membership.onSwimFaulty(id, 1);
            membership.onLivenessGone(id);
            Thread.sleep(BACKSTOP_MS * 2 / 3);
            membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());
        }

        assertThat(state(membership, id)).as("each late report landed inside the window").isEqualTo("Member");
    }

    /// (d) Partitioned but alive: evidence stops and the death signal holds, so the worker IS evicted after the
    /// window. DEAD is terminal for the identity — when the partition heals, the worker's evidence is REFUSED
    /// (boot-token gate), it stays DEAD, and it must rejoin under a new NodeId.
    @Test
    void partitionedWorker_evictedAfterTheWindow_andRefusedWhenItHeals() {
        var membership = fsm();
        var id = new NodeId("worker-partitioned");
        var governor = new NodeId("governor");

        membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());
        membership.onSwimFaulty(id, 1);
        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 15_000)).isTrue();
        var refusedBefore = membership.refusedProcessEvidenceCount();

        membership.onGovernorHealthy(id, "community", governor, 1, 1, worker());
        membership.onWorkerAdmissionHealthy(id, 1, worker());

        assertThat(state(membership, id)).as("healing does not resurrect a dead identity").isEqualTo("Dead");
        assertThat(membership.refusedProcessEvidenceCount()).as("the late evidence is refused, and counted").isGreaterThan(refusedBefore);
    }

    /// (e) The community governor dies: evidence stops for EVERY worker. With no death signal on any plane,
    /// nobody is evicted — staleness alone never kills. A worker that then really dies (a signal for it
    /// alone) is the only one evicted.
    @Test
    void governorDeath_silencesEvidenceForAll_butEvictsNoWorkerWithoutADeathSignal() {
        var membership = fsm();
        var governor = new NodeId("governor");
        var workers = java.util.List.of(new NodeId("w-a"), new NodeId("w-b"), new NodeId("w-c"));

        workers.forEach(w -> membership.onGovernorHealthy(w, "community", governor, 1, 1, worker()));
        workers.forEach(w -> membership.onSwimHealthy(w, 1));

        assertThat(awaitTrue(() -> workers.stream().anyMatch(w -> "Dead".equals(state(membership, w))), 3 * BACKSTOP_MS))
            .as("silence is not a death signal").isFalse();

        membership.onSwimFaulty(workers.get(0), 1);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, workers.get(0))), 15_000)).isTrue();
        assertThat(state(membership, workers.get(1))).isEqualTo("Member");
        assertThat(state(membership, workers.get(2))).isEqualTo("Member");
    }

    /// #1717 — the governor relays its SWIM-confirmed death of a non-governor worker, the only signal a core can
    /// receive for it. It arms the backstop and ends DEAD with the REMOVED edge.
    @Test
    void governorReportedDeath_reachesDead_andEmitsTheRemovedEdge() {
        var membership = fsm();
        var edges = new CopyOnWriteArrayList<MembershipDeltaEdge>();
        var id = new NodeId("worker-gd");

        membership.onMembershipDelta(edges::add);
        membership.onGovernorHealthy(id, "community", new NodeId("governor"), 1, 4, worker());
        membership.onGovernorReportedDead(id, 4);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 15_000))
            .as("a governor-relayed SWIM death evicts after the window; state=%s", state(membership, id)).isTrue();
        assertThat(edges.stream().map(MembershipDeltaEdge::kind).toList()).contains(MembershipDeltaEdge.Kind.REMOVED);
    }

    /// (c) A later positive report vetoes the relayed death inside the window.
    @Test
    void governorReportedDeath_thenPositiveReportInsideTheWindow_isVetoed() {
        var membership = fsm();
        var id = new NodeId("worker-veto");
        var governor = new NodeId("governor");

        membership.onGovernorHealthy(id, "community", governor, 1, 4, worker());
        membership.onGovernorReportedDead(id, 4);
        membership.onGovernorHealthy(id, "community", governor, 1, 4, worker());

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
        assertThat(state(membership, id)).isEqualTo("Member");
    }

    @Test
    void governorReportedDeath_forACore_aWrongBootToken_orAnUnclassifiedMember_isRefused() {
        var membership = fsm();
        var core = new NodeId("core-9");
        var worker = new NodeId("worker-token");
        var mystery = new NodeId("mystery-2");

        membership.seed(java.util.Set.of(core));
        membership.onGovernorHealthy(worker, "community", new NodeId("governor"), 1, 4, worker());
        membership.onSwimHealthy(mystery, 1);
        membership.onGovernorReportedDead(core, 1);
        membership.onGovernorReportedDead(worker, 99);
        membership.onGovernorReportedDead(mystery, 1);

        assertThat(awaitTrue(() -> membership.memberStates().containsValue("Dead"), 3 * BACKSTOP_MS))
            .as("a core, another process's token, and an unclassified member are never evicted by a relay").isFalse();
        assertThat(membership.refusedProcessEvidenceCount()).as("the wrong token is refused and counted").isGreaterThan(0);
    }

    /// The relay is the only governor-sourced removal signal. A governor that dies or falls silent relays
    /// nothing, so with no relayed death no member is evicted however long evidence is absent.
    @Test
    void governorSilence_withNoRelayedDeath_evictsNobody() {
        var membership = fsm();
        var governor = new NodeId("governor");
        var workers = java.util.List.of(new NodeId("s-a"), new NodeId("s-b"), new NodeId("s-c"));

        workers.forEach(w -> membership.onGovernorHealthy(w, "community", governor, 1, 4, worker()));

        assertThat(awaitTrue(() -> membership.memberStates().containsValue("Dead"), 4 * BACKSTOP_MS)).isFalse();
        workers.forEach(w -> assertThat(state(membership, w)).isEqualTo("Member"));
    }

    /// The waiver is for workers and spots only. A core is dialed, so its liveness plane exists and must
    /// still be required: SWIM-FAULTY alone must never kill a core.
    @Test
    void core_swimFaultyAlone_isNeverEvicted() {
        var membership = fsm();
        var id = new NodeId("core-1");

        membership.seed(java.util.Set.of(id));
        membership.onSwimFaulty(id, 1);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS))
            .as("a core needs BOTH planes; state=%s", state(membership, id))
            .isFalse();
    }

    /// An unknown role is not waived: a never-classified observation must not be evictable on one plane.
    @Test
    void unclassifiedMember_swimFaultyAlone_isNeverEvicted() {
        var membership = fsm();
        var id = new NodeId("mystery");

        membership.onSwimHealthy(id, 1);
        membership.onSwimFaulty(id, 1);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
    }

    @SuppressWarnings("unused")
    private static List<String> unused() {
        return List.of();
    }
}
