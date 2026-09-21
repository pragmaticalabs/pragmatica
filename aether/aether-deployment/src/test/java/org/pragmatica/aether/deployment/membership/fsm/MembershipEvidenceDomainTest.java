// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/// Drives the real membership ingress, transitions and output projections. The SWIM counter is
/// deliberately many orders larger than the durable epoch, as in assembled production nodes.
class MembershipEvidenceDomainTest {
    private static final NodeId WORKER = new NodeId("governor-worker");
    private static final NodeId GOVERNOR_NODE = new NodeId("governor");
    private static final MemberDescriptor DESCRIPTOR = new MemberDescriptor(Option.none(), "worker", "source");
    private static final long SWIM_BOOT = 1_790_018_000_000L;

    enum Evidence {
        GOVERNOR, ADMISSION;

        void publish(MembershipFsm membership, long processEpoch) {
            switch (this) {
                case GOVERNOR -> membership.onGovernorHealthy(WORKER, "community", GOVERNOR_NODE, 1, processEpoch, DESCRIPTOR);
                case ADMISSION -> membership.onWorkerAdmissionHealthy(WORKER, processEpoch, DESCRIPTOR);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void swimBeforeDurableEvidenceCannotMakeFreshWorkerEvidenceLookStale(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onSwimSuspect(WORKER, SWIM_BOOT);
        evidence.publish(membership, 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        assertThat(membership.memberIncarnations()).containsEntry(WORKER, SWIM_BOOT);
        assertThat(membership.coreCountedMembers()).doesNotContain(WORKER);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void durableThenSwimStillRejectsOldProcessAndAcceptsCurrentProcess(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        evidence.publish(membership, 2);
        assertThat(membership.memberIncarnations()).containsEntry(WORKER, 0L);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onSwimSuspect(WORKER, SWIM_BOOT + 1);
        evidence.publish(membership, 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Suspect");
        evidence.publish(membership, 2);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        assertThat(membership.memberIncarnations()).containsEntry(WORKER, SWIM_BOOT + 1);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void departingNeedsNewEvidenceInItsOwnDomainAndPreservesTheOtherFence(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        evidence.publish(membership, 1);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onDrainRequested(WORKER);
        evidence.publish(membership, 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Departing");
        evidence.publish(membership, 2);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        membership.onDrainRequested(WORKER);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Departing");
        membership.onSwimHealthy(WORKER, SWIM_BOOT + 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        membership.onDrainRequested(WORKER);
        evidence.publish(membership, 2);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Departing");
        evidence.publish(membership, 3);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void processRestartRejoinsAfterSwimDeathWithoutResettingEitherHighWater(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var edges = new ArrayList<MembershipDeltaEdge>();
        membership.onMembershipDelta(edges::add);
        evidence.publish(membership, 1);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onSwimDeparted(WORKER, SWIM_BOOT + 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Dead");
        evidence.publish(membership, 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Dead");
        evidence.publish(membership, 2);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        assertThat(membership.memberIncarnations()).containsEntry(WORKER, SWIM_BOOT + 1);
        membership.onSwimDeparted(WORKER, SWIM_BOOT + 1);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Dead");
        membership.onSwimHealthy(WORKER, SWIM_BOOT + 2);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        membership.onSwimSuspect(WORKER, SWIM_BOOT + 2);
        evidence.publish(membership, 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Suspect");
        evidence.publish(membership, 2);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        assertThat(edges.stream().map(MembershipDeltaEdge::kind)).containsExactly(
            MembershipDeltaEdge.Kind.JOINED, MembershipDeltaEdge.Kind.REMOVED,
            MembershipDeltaEdge.Kind.JOINED, MembershipDeltaEdge.Kind.REMOVED, MembershipDeltaEdge.Kind.JOINED);
        assertThat(edges).allSatisfy(edge -> assertThat(edge.role()).isEqualTo("worker"));
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void equalDepartingEvidenceCannotEraseDeathConfirmation(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis,
                                                       Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
        evidence.publish(membership, 2);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onDrainRequested(WORKER);
        membership.onSwimFaulty(WORKER, SWIM_BOOT);
        evidence.publish(membership, 1);
        evidence.publish(membership, 2);
        membership.onLivenessGone(WORKER);
        assertThatCode(() -> await().atMost(2, TimeUnit.SECONDS)
                                    .untilAsserted(() -> assertThat(membership.memberStates()).containsEntry(WORKER, "Dead")))
            .doesNotThrowAnyException();
        evidence.publish(membership, 1);
        evidence.publish(membership, 2);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Dead");
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void governorAndAdmissionShareTheDurableFence(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        evidence.publish(membership, 2);
        membership.onSwimSuspect(WORKER, SWIM_BOOT);
        var other = evidence == Evidence.GOVERNOR ? Evidence.ADMISSION : Evidence.GOVERNOR;
        other.publish(membership, 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Suspect");
        other.publish(membership, 3);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
    }
}
