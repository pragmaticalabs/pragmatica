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

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3_000))
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
        pause(5);
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

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3_000))
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
        pause(5);
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

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3_000))
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
        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3_000)).isTrue();
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

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, workers.get(0))), 3_000)).isTrue();
        assertThat(state(membership, workers.get(1))).isEqualTo("Member");
        assertThat(state(membership, workers.get(2))).isEqualTo("Member");
    }

    /// #1717 (community case, measured on bigboy): the governor keeps reporting a dead worker alive for up to
    /// `communityAbsence` after its last pong. That evidence is OLDER than the link drop, so it must not veto it.
    @Test
    void staleGovernorTail_olderThanTheDrop_thenSilence_reachesDead() {
        var membership = fsm();
        var id = new NodeId("worker-stale-tail");
        var governor = new NodeId("governor");

        membership.onGovernorHealthy(id, "community", governor, 1, 4, worker());
        membership.onLivenessGone(id);
        pause(5);
        membership.onGovernorHealthy(id, "community", governor, 1, 4, worker(), 60_000L);
        membership.onGovernorHealthy(id, "community", governor, 1, 4, worker(), 61_000L);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 15_000))
            .as("evidence older than the drop proves nothing; state=%s", state(membership, id)).isTrue();
    }

    /// The same sequence with a FRESH pong after the drop is the live worker: vetoed.
    @Test
    void governorEvidenceNewerThanTheDrop_vetoes() {
        var membership = fsm();
        var id = new NodeId("worker-fresh-pong");
        var governor = new NodeId("governor");

        membership.onGovernorHealthy(id, "community", governor, 1, 4, worker());
        membership.onLivenessGone(id);
        pause(5);
        membership.onGovernorHealthy(id, "community", governor, 1, 4, worker(), 0L);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
        assertThat(state(membership, id)).isEqualTo("Member");
    }

    /// A live worker whose link drops and comes back, with no other evidence source (a worker's view of a peer
    /// worker, a non-leader core, a governor re-election): the re-established link vetoes the transport death.
    @Test
    void transportBlipThenReconnect_isNeverEvicted() {
        var membership = fsm();
        var id = new NodeId("worker-blip");

        membership.onWorkerAdmissionHealthy(id, 1, worker());
        membership.onPeerDisconnected(id);
        membership.onLivenessGone(id);
        pause(5);
        membership.onPeerConnected(id);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
    }

    /// The reconnect veto covers the transport plane only: a SWIM-FAULTY death still evicts.
    @Test
    void reconnect_doesNotVetoSwimFaulty() {
        var membership = fsm();
        var id = new NodeId("worker-faulty-reconnect");

        membership.onWorkerAdmissionHealthy(id, 1, worker());
        membership.onSwimFaulty(id, 1);
        membership.onPeerConnected(id);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 15_000)).isTrue();
    }

    private static MemberDescriptor spot() {
        return new MemberDescriptor(Option.none(), "spot", "source");
    }

    /// A spot is a non-core role like a worker: it is evicted on either plane, and the role is not just a literal
    /// nobody exercises.
    @Test
    void killedSpot_reachesDead_onEitherPlane() {
        var bySwim = fsm();
        var byTransport = fsm();
        var a = new NodeId("spot-a");
        var b = new NodeId("spot-b");

        bySwim.onWorkerAdmissionHealthy(a, 1, spot());
        bySwim.onSwimFaulty(a, 1);
        byTransport.onWorkerAdmissionHealthy(b, 1, spot());
        byTransport.onLivenessGone(b);

        assertThat(awaitTrue(() -> "Dead".equals(state(bySwim, a)), 15_000)).as("spot, SWIM plane").isTrue();
        assertThat(awaitTrue(() -> "Dead".equals(state(byTransport, b)), 15_000)).as("spot, transport plane").isTrue();
    }

    /// Each evidence source vetoes a death signal on its own: governor (above), admission and SWIM-healthy.
    @Test
    void admissionEvidenceAfterTheSignal_vetoes() {
        var membership = fsm();
        var id = new NodeId("worker-admission-veto");

        membership.onWorkerAdmissionHealthy(id, 1, worker());
        membership.onLivenessGone(id);
        pause(5);
        membership.onWorkerAdmissionHealthy(id, 1, worker());

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
        assertThat(state(membership, id)).isEqualTo("Member");
    }

    @Test
    void swimHealthyAfterTheSignal_vetoes() {
        var membership = fsm();
        var id = new NodeId("worker-swim-veto");

        membership.onWorkerAdmissionHealthy(id, 1, worker());
        membership.onSwimFaulty(id, 1);
        pause(5);
        membership.onSwimHealthy(id, 2);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
    }

    /// SWIM-healthy at the SAME incarnation does not move a SUSPECT member back to MEMBER, yet it retracts the
    /// death signal (a healthy sample retracts both planes): only the explicit clear in `healthy` does that.
    @Test
    void swimHealthyAtTheSameIncarnation_retractsTheSignalWithoutLeavingSuspect() {
        var membership = fsm();
        var id = new NodeId("worker-swim-same-inc");

        membership.onWorkerAdmissionHealthy(id, 1, worker());
        membership.onSwimFaulty(id, 5);
        pause(5);
        membership.onSwimHealthy(id, 5);

        assertThat(awaitTrue(() -> "Dead".equals(state(membership, id)), 3 * BACKSTOP_MS)).isFalse();
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
