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

/// Drives the real membership ingress, transitions and output projections for process evidence
/// (governor reports and worker admission). Process evidence carries the worker's per-process random
/// BOOT TOKEN, compared by EQUALITY only (owner ruling, session 28): the first token is recorded, an
/// equal token is accepted, a different token is a different process and is refused, and DEAD /
/// DEPARTING refuse process evidence unconditionally (terminal removal). The SWIM counter is
/// deliberately many orders larger than the tokens, as in assembled production nodes.
class MembershipEvidenceDomainTest {
    private static final NodeId WORKER = new NodeId("governor-worker");
    private static final NodeId GOVERNOR_NODE = new NodeId("governor");
    private static final MemberDescriptor DESCRIPTOR = new MemberDescriptor(Option.none(), "worker", "source");
    private static final long SWIM_BOOT = 1_790_018_000_000L;
    private static final long TOKEN = 0x5eed_0001L;
    private static final long OTHER_TOKEN = 0x5eed_0002L;

    enum Evidence {
        GOVERNOR, ADMISSION;

        void publish(MembershipFsm membership, long bootToken) {
            switch (this) {
                case GOVERNOR -> membership.onGovernorHealthy(WORKER, "community", GOVERNOR_NODE, 1, bootToken, DESCRIPTOR);
                case ADMISSION -> membership.onWorkerAdmissionHealthy(WORKER, bootToken, DESCRIPTOR);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void swimBeforeProcessEvidence_firstTokenPromotesWorker(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onSwimSuspect(WORKER, SWIM_BOOT);
        evidence.publish(membership, TOKEN);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        assertThat(membership.memberIncarnations()).containsEntry(WORKER, SWIM_BOOT);
        assertThat(membership.coreCountedMembers()).doesNotContain(WORKER);
        assertThat(membership.refusedProcessEvidenceCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void differentToken_isRefusedAndCounted_equalTokenRecovers(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        evidence.publish(membership, TOKEN);
        membership.onSwimSuspect(WORKER, SWIM_BOOT);
        evidence.publish(membership, OTHER_TOKEN);
        assertThat(membership.memberStates()).as("a different boot token is a different process").containsEntry(WORKER, "Suspect");
        assertThat(membership.refusedProcessEvidenceCount()).isEqualTo(1);
        evidence.publish(membership, TOKEN);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
        assertThat(membership.refusedProcessEvidenceCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void departing_ignoresProcessEvidenceUnconditionally(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        evidence.publish(membership, TOKEN);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onDrainRequested(WORKER);
        evidence.publish(membership, TOKEN);
        evidence.publish(membership, OTHER_TOKEN);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Departing");
        assertThat(membership.refusedProcessEvidenceCount()).isEqualTo(2);
        membership.onSwimHealthy(WORKER, SWIM_BOOT + 1);
        assertThat(membership.memberStates()).as("SWIM same-process refutation still recovers a drainer")
                                             .containsEntry(WORKER, "Member");
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void dead_ignoresProcessEvidenceUnconditionally(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        var edges = new ArrayList<MembershipDeltaEdge>();
        membership.onMembershipDelta(edges::add);
        evidence.publish(membership, TOKEN);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onSwimDeparted(WORKER, SWIM_BOOT + 1);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Dead");
        evidence.publish(membership, TOKEN);
        evidence.publish(membership, OTHER_TOKEN);
        assertThat(membership.memberStates()).as("terminal removal: process evidence never reopens a dead identity")
                                             .containsEntry(WORKER, "Dead");
        assertThat(membership.refusedProcessEvidenceCount()).isEqualTo(2);
        membership.onSwimHealthy(WORKER, SWIM_BOOT + 2);
        assertThat(membership.memberStates()).as("SWIM same-process heal (higher incarnation) is unchanged")
                                             .containsEntry(WORKER, "Member");
        assertThat(edges.stream().map(MembershipDeltaEdge::kind)).containsExactly(
            MembershipDeltaEdge.Kind.JOINED, MembershipDeltaEdge.Kind.REMOVED, MembershipDeltaEdge.Kind.JOINED);
        assertThat(edges).allSatisfy(edge -> assertThat(edge.role()).isEqualTo("worker"));
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void departingEvidenceCannotEraseDeathConfirmation(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis,
                                                       Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
        evidence.publish(membership, TOKEN);
        membership.onSwimHealthy(WORKER, SWIM_BOOT);
        membership.onDrainRequested(WORKER);
        membership.onSwimFaulty(WORKER, SWIM_BOOT);
        evidence.publish(membership, OTHER_TOKEN);
        evidence.publish(membership, TOKEN);
        membership.onLivenessGone(WORKER);
        assertThatCode(() -> await().atMost(2, TimeUnit.SECONDS)
                                    .untilAsserted(() -> assertThat(membership.memberStates()).containsEntry(WORKER, "Dead")))
            .doesNotThrowAnyException();
        evidence.publish(membership, OTHER_TOKEN);
        evidence.publish(membership, TOKEN);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Dead");
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void governorAndAdmissionShareTheBootToken(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        evidence.publish(membership, TOKEN);
        membership.onSwimSuspect(WORKER, SWIM_BOOT);
        var other = evidence == Evidence.GOVERNOR ? Evidence.ADMISSION : Evidence.GOVERNOR;
        other.publish(membership, OTHER_TOKEN);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Suspect");
        other.publish(membership, TOKEN);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void zeroToken_isNotEvidence(Evidence evidence) {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop());
        evidence.publish(membership, 0);
        assertThat(membership.memberStates()).doesNotContainKey(WORKER);
        evidence.publish(membership, TOKEN);
        assertThat(membership.memberStates()).containsEntry(WORKER, "Member");
    }
}
