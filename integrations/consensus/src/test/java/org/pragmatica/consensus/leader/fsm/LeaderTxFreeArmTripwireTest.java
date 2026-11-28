/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import static org.pragmatica.consensus.leader.LeaderInboundForTest.inbound;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PeerRemovedFromView;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3846 row K22b (memo r3 section 4): a tx-free handler arm of `Led` runs ON the drainer with a null transition request.
/// `led.handle(new PeerRemovedFromView(leader), null)` takes the arm at State:616, `storeRemoval` then `holdLeaderToView`,
/// which dispatches `LeaderOutOfView` for the held leader (State:712). Lane St gives that dispatch an episode identity
/// minted from the null request: the mint answers NEVER (`refusedNullIdentities` + 1), the dispatch is refused and `Led`
/// stays. The other tx-free arms that reach :712 are State:224-226, :319-321, :508-510, :998-1000 and :1033-1035.
///
/// Two methods, as the tripwires of [LeaderEpisodeEntryPathTest]: the ENABLED tripwire asserts today's behaviour (the
/// dispatch ACTS, the tenure ends), and its `@Disabled` twin asserts the rule. Lane St enables the twin and deletes the
/// tripwire. The control, in the same test class, is the same removal of a node that is NOT the held leader: it must leave
/// `Led` standing at every head, so a tenure that ends in the tripwire ended because of the dispatch and not because of
/// the harness.
class LeaderTxFreeArmTripwireTest {
    private static final NodeId SELF = new NodeId("core-0");
    private static final NodeId PEER = new NodeId("core-1");
    private static final NodeId OTHER = new NodeId("core-2");
    private static final List<NodeId> CORES = List.of(SELF, PEER, OTHER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    /// Instrument: the removal of a node that is not the held leader does not end the tenure (ENABLED at every head).
    @Test
    void controlProbe_removalOfANonLeader_leavesTheLeaderStanding() {
        var ctx = ledContext();

        try {
            var led = seededLed(ctx);

            inbound(ctx).serially(() -> led.handle(new PeerRemovedFromView(OTHER), null));

            assertThat(ctx.current()).as("instrument: a removal of a non-leader peer leaves Led standing")
                                     .isInstanceOf(LeaderElectionState.Led.class);
            assertThat(ctx.confinementBreachesForTest()).as("instrument: nothing was refused")
                                                        .isZero();
        } finally {
            inbound(ctx).stop();
        }
    }

    /// ENABLED tripwire (flipped in lane St): at the head the dispatch of `LeaderOutOfView` ACTS and `Led` leaves.
    @Test
    void k22b_txFreeRemovalOfTheLeader_atHead_actsAndLedLeaves() {
        var ctx = ledContext();

        try {
            var led = seededLed(ctx);

            inbound(ctx).serially(() -> led.handle(new PeerRemovedFromView(PEER), null));

            assertThat(ctx.current()).as("K22b no longer acts: lane St landed the identity mint for tx-free arms. Enable "
                                         + "k22b_txFreeRemovalOfTheLeader_underInvE_isRefusedAndLedStays and delete this method")
                                     .isNotInstanceOf(LeaderElectionState.Led.class);
        } finally {
            inbound(ctx).stop();
        }
    }

    /// The rule, `@Disabled` until lane St: the null request mints NEVER, the dispatch is refused and `Led` stays.
    /// Lane St adds the `refusedDispatches` count (a lane C counter that does not exist at this head).
    @Disabled("lane St: enable when handlerEpisode(null) gives NEVER for a tx-free arm (K22b)")
    @Test
    void k22b_txFreeRemovalOfTheLeader_underInvE_isRefusedAndLedStays() {
        var ctx = ledContext();

        try {
            var led = seededLed(ctx);

            inbound(ctx).serially(() -> led.handle(new PeerRemovedFromView(PEER), null));

            assertThat(ctx.current()).as("the refused LeaderOutOfView never reached the FSM: Led stays")
                                     .isInstanceOf(LeaderElectionState.Led.class);
            assertThat(ctx.refusedNullIdentitiesForTest()).as("the null transition request mints NEVER and counts")
                                                          .isEqualTo(1);
        } finally {
            inbound(ctx).stop();
        }
    }

    /// The stored view starts empty, so a removal would end any tenure: the view [CORES] is stored first, through the same
    /// tx-free arm (`NodeAdded`, State:615), and the tenure must stand after it.
    private static LeaderElectionState.Led seededLed(LeaderElectionContext ctx) {
        var led = (LeaderElectionState.Led) ctx.current();

        inbound(ctx).serially(() -> led.handle(new ClusterFsmEvent.NodeAdded(SELF, CORES, 1L), null));
        assertThat(ctx.current()).as("instrument: the view [core-0, core-1, core-2] holds the leader, so Led stands")
                                 .isSameAs(led);

        return led;
    }

    private static LeaderElectionContext ledContext() {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderTxFreeArmTripwireTest::ignoreChange);

        var fsm = Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-tx-free-arm-tripwire",
                                                                 f -> LeaderElectionState.Led.fresh(context(f, router), PEER));

        return fsm.current()
                  .ctx();
    }

    private static void ignoreChange(LeaderChange change) {}

    private static boolean pingFresh(NodeId leader) {
        return true;
    }

    private static LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm, MessageRouter router) {
        var proposal = Promise.<Unit>promise();
        LeaderProposalHandler pending = (_, _) -> proposal;

        return new LeaderElectionContext(fsm,
                                         SELF,
                                         Option.some(pending),
                                         CORES,
                                         router,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> false,
                                         Option::none,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderTxFreeArmTripwireTest::pingFresh,
                                         LONG);
    }
}
