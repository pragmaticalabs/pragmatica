// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #3119 — a partition that leaves EVERY node without quorum must not leave the cluster quorumless after the heal.
/// Each node's own quorum-loss report (#2936) derives from its own membership view, so it clears only after the node
/// re-admits its peers; if a quorumless observer held a quorumless peer out on that peer's report, every node would
/// wait on every other one forever (the E06 regression from #2940). A quorate observer still holds a quorumless member
/// out, so #2936's invariant stands on the side of the cluster that can write.
class QuorumlessReadmissionCycleTest {
    private static final List<NodeId> NODES = List.of(new NodeId("n-1"),
                                                      new NodeId("n-2"),
                                                      new NodeId("n-3"),
                                                      new NodeId("n-4"),
                                                      new NodeId("n-5"));
    private static final NodeId SUSPECT = NODES.getFirst();
    private static final int THRESHOLD = NODES.size() / 2 + 1;
    private static final int HEAL_ROUND_LIMIT = 4;
    private static final TimeSpan SHORT_BACKSTOP = TimeSpan.timeSpan(40).millis();
    private static final TimeSpan LONG_WINDOW = TimeSpan.timeSpan(30).seconds();
    private static final long NO_HINT_DECAY = Long.MAX_VALUE;

    private final Map<NodeId, MembershipFsm> cluster = new LinkedHashMap<>();
    private final Map<NodeId, Long> incarnations = new ConcurrentHashMap<>();
    private final Map<NodeId, Boolean> published = new ConcurrentHashMap<>();
    private final Set<NodeId> reporting = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean observerLost = new AtomicBoolean();

    /// E06 shape on five in-JVM FSMs: 3 of 5 blackholed, so the two survivors (2 < 3) and the three isolated nodes
    /// (1 < 3) all lose quorum and every peer goes terminal DEAD everywhere. After the heal each node gossips a newer
    /// incarnation, carrying its LOST report; a node bumps again whenever its own report changes. Every node must
    /// re-admit every peer and regain quorum within a few dissemination rounds.
    @Test
    void everyNodeLostQuorum_afterTheHeal_allReadmitAndRegainQuorum() {
        NODES.forEach(this::bootNode);
        NODES.forEach(this::admitEveryPeer);
        assertThat(NODES).as("control: the booted cluster holds quorum everywhere").noneMatch(this::lostQuorum);

        NODES.forEach(this::declareEveryPeerDead);
        assertThat(NODES).as("the partition leaves every node without quorum").allMatch(this::lostQuorum);
        NODES.forEach(this::publishOwnReport);

        for (int round = 0; round < HEAL_ROUND_LIMIT && NODES.stream().anyMatch(this::lostQuorum); round++) {
            NODES.forEach(this::gossipToEveryPeer);
            NODES.forEach(this::publishOwnReport);
        }

        assertThat(NODES).as("quorum re-forms on every node after the heal (no circular wait on LOST reports)")
                         .noneMatch(this::lostQuorum);
        assertThat(cluster.values()).allSatisfy(fsm -> assertThat(fsm.memberStates().values()).containsOnly("Member"));
    }

    @Test
    void quorumlessObserver_reopensAReportingDeadMember() {
        var observer = observer();

        killSuspect(observer);
        reporting.add(SUSPECT);
        observerLost.set(true);
        observer.onSwimHealthy(SUSPECT, 2L);

        assertThat(observer.memberStates()).as("a quorumless observer cannot hold a quorumless member out")
                                           .containsEntry(SUSPECT, "Member");
        assertThat(observer.forwardableMembers(List.of(SUSPECT))).as("the forward filter still excludes the reporter")
                                                                 .isEmpty();
    }

    @Test
    void quorumlessObserver_recoversAReportingMembersRefutedDrain() {
        var observer = observer();

        enterDepartingWithoutDeath(observer);
        reporting.add(SUSPECT);
        observerLost.set(true);
        observer.onSwimHealthy(SUSPECT, 2L);

        assertThat(observer.memberStates()).containsEntry(SUSPECT, "Member");
    }

    /// The observer's own report is read live at transition time: once it regains quorum the #2936 hold applies again.
    @Test
    void observerRegainsQuorum_holdsAReportingMemberOutAgain() {
        var observer = observer();

        killSuspect(observer);
        reporting.add(SUSPECT);
        observerLost.set(true);
        observerLost.set(false);
        observer.onSwimHealthy(SUSPECT, 2L);
        assertThat(observer.memberStates()).as("quorate observer: DEAD rejoin held (#2936)").containsEntry(SUSPECT, "Dead");

        observerLost.set(true);
        observer.onSwimHealthy(SUSPECT, 3L);
        assertThat(observer.memberStates()).containsEntry(SUSPECT, "Member");

        enterDeparting(observer, 3L);
        observerLost.set(false);
        observer.onSwimHealthy(SUSPECT, 4L);
        assertThat(observer.memberStates()).as("quorate observer: refuted drain held (#2936)")
                                           .containsEntry(SUSPECT, "Departing");
    }

    private void bootNode(NodeId node) {
        var fsm = fsm();

        fsm.setQuorumLossReport(reporting::contains);
        fsm.setSelfQuorumLoss(() -> lostQuorum(node));
        cluster.put(node, fsm);
        incarnations.put(node, 1L);
        published.put(node, false);
    }

    private MembershipFsm observer() {
        var fsm = fsm();

        fsm.setQuorumLossReport(reporting::contains);
        fsm.setSelfQuorumLoss(observerLost::get);

        return fsm;
    }

    private static MembershipFsm fsm() {
        return MembershipFsm.membershipFsm(FsmObserver.noop(),
                                           System::currentTimeMillis,
                                           NO_HINT_DECAY,
                                           SHORT_BACKSTOP,
                                           LONG_WINDOW,
                                           LONG_WINDOW);
    }

    /// A node's own detector: below the simple-majority threshold over its own MEMBER view (self included).
    private boolean lostQuorum(NodeId node) {
        var members = cluster.get(node)
                             .memberStates()
                             .values()
                             .stream()
                             .filter("Member"::equals)
                             .count();

        return members + 1 < THRESHOLD;
    }

    /// Publish the node's report as its SWIM label: a change bumps its self-incarnation (as `SwimProtocol` does).
    private void publishOwnReport(NodeId node) {
        var lost = lostQuorum(node);

        if (published.put(node, lost) != lost) {
            incarnations.merge(node, 1L, Long::sum);
        }
        if (lost) {
            reporting.add(node);
        } else {
            reporting.remove(node);
        }
    }

    private void admitEveryPeer(NodeId observer) {
        peersOf(observer).forEach(peer -> cluster.get(observer).onSwimHealthy(peer, incarnations.get(peer)));
    }

    private void gossipToEveryPeer(NodeId subject) {
        incarnations.merge(subject, 1L, Long::sum);
        peersOf(subject).forEach(observer -> cluster.get(observer).onSwimHealthy(subject, incarnations.get(subject)));
    }

    private void declareEveryPeerDead(NodeId observer) {
        peersOf(observer).forEach(peer -> kill(cluster.get(observer), peer));
    }

    private static List<NodeId> peersOf(NodeId node) {
        return NODES.stream()
                    .filter(peer -> !peer.equals(node))
                    .toList();
    }

    private static void killSuspect(MembershipFsm fsm) {
        fsm.onSwimHealthy(SUSPECT, 1L);
        kill(fsm, SUSPECT);
    }

    private static void kill(MembershipFsm fsm, NodeId peer) {
        fsm.onSwimFaulty(peer, 1L);
        fsm.onLivenessGone(peer);
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(fsm.memberStates()).containsEntry(peer, "Dead"));
    }

    private static void enterDepartingWithoutDeath(MembershipFsm fsm) {
        fsm.onSwimHealthy(SUSPECT, 1L);
        enterDeparting(fsm, 1L);
    }

    private static void enterDeparting(MembershipFsm fsm, long incarnation) {
        fsm.onSwimSuspect(SUSPECT, incarnation);
        fsm.onDownHysteresisMet(SUSPECT);
        assertThat(fsm.memberStates()).containsEntry(SUSPECT, "Departing");
    }
}
