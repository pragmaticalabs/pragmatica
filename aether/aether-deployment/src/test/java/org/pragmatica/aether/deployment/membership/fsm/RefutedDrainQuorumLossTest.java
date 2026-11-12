// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #2936 — a node that cannot write to the quorum (it says so on its SWIM self-ALIVE) is not re-admitted
/// DEPARTING→MEMBER by a refuted drain (nor reopened from DEAD by a newer incarnation) and does not pass the forward filter ([`MembershipFsm#forwardableMembers`]),
/// while a healthy node with a briefly refuted drain is re-admitted, and a reporting node is re-admitted as soon as
/// its report clears (bounded by its next strictly-newer `SwimHealthy`, which the clearing carries).
///
/// The r6 timeline (E11, 2026-11-08) is replayed on three survivors' FSMs: the future leader saw the suspect DEAD
/// before the election; the other two still held it DEPARTING (FAULTY contradicted by a live transport link) when the
/// heal let its refutation through while it reported quorum loss.
class RefutedDrainQuorumLossTest {
    private static final NodeId SUSPECT = new NodeId("f4f-1");
    private static final TimeSpan SHORT_BACKSTOP = TimeSpan.timeSpan(40).millis();
    private static final TimeSpan SHORT_DEPARTURE = TimeSpan.timeSpan(80).millis();
    private static final TimeSpan LONG_WINDOW = TimeSpan.timeSpan(30).seconds();
    private static final long NO_HINT_DECAY = Long.MAX_VALUE;

    private final Set<NodeId> reporting = ConcurrentHashMap.newKeySet();

    @Test
    void r6Timeline_quorumlessRefutation_isNotReadmittedOnAnySurvivor_norForwardable() {
        var futureLeader = survivor(LONG_WINDOW);
        var survivorThree = survivor(LONG_WINDOW);
        var survivorFour = survivor(LONG_WINDOW);
        var readmitted = new ArrayList<NodeId>();

        survivorThree.onDepartingRecovery(readmitted::add);
        survivorFour.onDepartingRecovery(readmitted::add);
        // Blackhole: the future leader co-confirms death before the election ...
        futureLeader.onSwimHealthy(SUSPECT, 1L);
        futureLeader.onSwimFaulty(SUSPECT, 1L);
        futureLeader.onLivenessGone(SUSPECT);
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(futureLeader.memberStates()).containsEntry(SUSPECT, "Dead"));
        // ... the other two only reach DEPARTING (FAULTY contradicted by a live transport link).
        enterDepartingWithoutDeath(survivorThree);
        enterDepartingWithoutDeath(survivorFour);
        // The suspect lost quorum during the blackhole; the heal lets its refutation through, carrying the report.
        reporting.add(SUSPECT);
        List.of(futureLeader, survivorThree, survivorFour).forEach(fsm -> fsm.onSwimHealthy(SUSPECT, 2L));

        assertThat(futureLeader.memberStates()).as("a quorumless refutation does not reopen a DEAD identity")
                                               .containsEntry(SUSPECT, "Dead");
        assertThat(survivorThree.memberStates()).as("refuted drain of a quorumless node: stays DEPARTING")
                                                .containsEntry(SUSPECT, "Departing");
        assertThat(survivorFour.memberStates()).containsEntry(SUSPECT, "Departing");
        assertThat(readmitted).as("no DEPARTING->MEMBER recovery edge, no DHT ring re-add").isEmpty();
        assertThat(List.of(futureLeader, survivorThree, survivorFour))
            .as("no survivor forwards to a node that cannot write to the quorum")
            .allSatisfy(fsm -> assertThat(fsm.forwardableMembers(List.of(SUSPECT))).isEmpty());
    }

    @Test
    void healthyNode_briefRefutedDrain_isReadmittedAndForwardable() {
        var survivor = survivor(LONG_WINDOW);
        var readmitted = new ArrayList<NodeId>();

        survivor.onDepartingRecovery(readmitted::add);
        enterDepartingWithoutDeath(survivor);
        survivor.onSwimHealthy(SUSPECT, 2L);

        assertThat(survivor.memberStates()).as("no report: a refuted drain re-admits as before (no false exclusion)")
                                           .containsEntry(SUSPECT, "Member");
        assertThat(readmitted).containsExactly(SUSPECT);
        assertThat(survivor.forwardableMembers(List.of(SUSPECT))).containsExactly(SUSPECT);
    }

    @Test
    void heldNode_isReadmittedOnTheRefutationThatClearsItsReport() {
        var survivor = survivor(LONG_WINDOW);
        var readmitted = new ArrayList<NodeId>();

        survivor.onDepartingRecovery(readmitted::add);
        enterDepartingWithoutDeath(survivor);
        reporting.add(SUSPECT);
        survivor.onSwimHealthy(SUSPECT, 2L);
        assertThat(survivor.memberStates()).containsEntry(SUSPECT, "Departing");
        survivor.onSwimHealthy(SUSPECT, 2L);
        assertThat(survivor.memberStates()).as("the held refutation's incarnation was recorded; a repeat is stale")
                                           .containsEntry(SUSPECT, "Departing");

        reporting.remove(SUSPECT);
        survivor.onSwimHealthy(SUSPECT, 3L);

        assertThat(survivor.memberStates()).as("quorum regained: the clearing bump re-admits at once")
                                           .containsEntry(SUSPECT, "Member");
        assertThat(readmitted).containsExactly(SUSPECT);
        assertThat(survivor.forwardableMembers(List.of(SUSPECT))).containsExactly(SUSPECT);
    }

    @Test
    void heldNode_terminalizesAtTheDepartingTimeout_andRejoinsWhenItsReportClears() {
        var survivor = survivor(SHORT_DEPARTURE);

        enterDepartingWithoutDeath(survivor);
        reporting.add(SUSPECT);
        survivor.onSwimHealthy(SUSPECT, 2L);
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(survivor.memberStates()).as("held DEPARTING is not a trap")
                                                                       .containsEntry(SUSPECT, "Dead"));

        survivor.onSwimHealthy(SUSPECT, 3L);
        assertThat(survivor.memberStates()).as("a quorumless refutation does not reopen the DEAD identity")
                                           .containsEntry(SUSPECT, "Dead");

        reporting.remove(SUSPECT);
        survivor.onSwimHealthy(SUSPECT, 2L);
        assertThat(survivor.memberStates()).as("the held refutation's incarnation was recorded: a relay of it is stale")
                                           .containsEntry(SUSPECT, "Dead");
        survivor.onSwimHealthy(SUSPECT, 4L);

        assertThat(survivor.memberStates()).as("the clearing bump reopens the identity and re-admits it")
                                           .containsEntry(SUSPECT, "Member");
        assertThat(survivor.forwardableMembers(List.of(SUSPECT))).containsExactly(SUSPECT);
    }

    /// #3028 S3: the refutation was refused while the member reported quorum loss, the report then clears WITHOUT a further
    /// incarnation bump, and nothing else offers the incarnation again. The FSM re-offers it itself.
    @Test
    void rejoinRefusedDuringQuorumLoss_retriedOnRegain() {
        var survivor = survivor(SHORT_DEPARTURE);

        enterDepartingWithoutDeath(survivor);
        reporting.add(SUSPECT);
        survivor.onSwimHealthy(SUSPECT, 2L);
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(survivor.memberStates()).containsEntry(SUSPECT, "Dead"));
        survivor.onSwimHealthy(SUSPECT, 3L);
        assertThat(survivor.memberStates()).as("refused while the member reports quorum loss").containsEntry(SUSPECT, "Dead");

        reporting.remove(SUSPECT);

        await().atMost(6, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(survivor.memberStates()).as("quorum regained: the refused incarnation is re-offered")
                                                                       .containsEntry(SUSPECT, "Observed"));
    }

    @Test
    void rejoinRefusedDuringQuorumLoss_staysDeadWhileTheReportHolds() throws InterruptedException {
        var survivor = survivor(SHORT_DEPARTURE);

        enterDepartingWithoutDeath(survivor);
        reporting.add(SUSPECT);
        survivor.onSwimHealthy(SUSPECT, 2L);
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(survivor.memberStates()).containsEntry(SUSPECT, "Dead"));
        survivor.onSwimHealthy(SUSPECT, 3L);
        Thread.sleep(4500);

        assertThat(survivor.memberStates()).as("the re-offer is refused again while the report holds").containsEntry(SUSPECT, "Dead");
    }

    @Test
    void reportingMember_staysReachable_butIsNotForwardable() {
        var survivor = survivor(LONG_WINDOW);

        survivor.onSwimHealthy(SUSPECT, 1L);
        reporting.add(SUSPECT);

        assertThat(survivor.reachableMembers(List.of(SUSPECT)))
            .as("reachableMembers also scopes SWIM observation: narrowing it could hide the report clearing")
            .containsExactly(SUSPECT);
        assertThat(survivor.forwardableMembers(List.of(SUSPECT))).isEmpty();
    }

    private MembershipFsm survivor(TimeSpan departureTimeout) {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              SHORT_BACKSTOP,
                                              departureTimeout,
                                              LONG_WINDOW);

        fsm.setQuorumLossReport(reporting::contains);

        return fsm;
    }

    private static void enterDepartingWithoutDeath(MembershipFsm fsm) {
        fsm.onSwimHealthy(SUSPECT, 1L);
        fsm.onSwimSuspect(SUSPECT, 1L);
        fsm.onDownHysteresisMet(SUSPECT);
        assertThat(fsm.memberStates()).containsEntry(SUSPECT, "Departing");
    }
}
