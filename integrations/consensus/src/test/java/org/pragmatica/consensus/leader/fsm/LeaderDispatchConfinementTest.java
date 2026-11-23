/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3605 round-4 ruling (know 4c954b1c5d): confinement is ENFORCED at the dispatch boundary. Every FSM dispatch of
/// leader code goes through [LeaderElectionContext#dispatchConfined], which throws [IllegalStateException] naming the
/// rule and the calling thread when the caller is not the node's serial drainer; `dispatchSelf` uses it.
///
/// Pinned here: the checked method refuses a foreign thread and the event never reaches the FSM; `dispatchSelf`,
/// reached from a handler that runs off the drainer, refuses the same way; and a Led tenure whose timers all fire
/// refuses nothing. The last one is the detector for a NEW unwrapped site: an off-drainer callback added to the Led
/// entry that dispatches without `ctx.serialized(...)` is refused on its scheduler thread, and the refusal count goes
/// up. It sees only sites that fire inside its window (every Led delay is [#SHORT]); a site elsewhere is refused the
/// same way, and fails the first test that fires it and reads the count.
class LeaderDispatchConfinementTest {
    private static final NodeId SELF = new NodeId("core-0");
    private static final NodeId PEER = new NodeId("core-1");
    private static final List<NodeId> CORES = List.of(SELF, PEER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(50).millis();
    private static final String FOREIGN = "foreign-caller-3605";
    private static final String RULE = "#3605 confinement rule";

    private final AtomicInteger leaseChecks = new AtomicInteger();

    @Test
    void dispatchConfined_fromAForeignThread_isRefusedNamingTheRuleAndTheCaller() throws InterruptedException {
        var ctx = context(LeaderElectionContext::dormant, LONG);

        try {
            var refused = onForeignThread(() -> ctx.dispatchConfined(new ClusterFsmEvent.QuorumEstablished()));

            assertThat(refused).as("a dispatch off the serial drainer is refused")
                               .isInstanceOf(IllegalStateException.class)
                               .hasMessageContaining(RULE)
                               .hasMessageContaining("serial drainer")
                               .hasMessageContaining(FOREIGN);
            assertThat(ctx.fsm()
                          .current()).as("the refused event never reached the FSM")
                                     .isInstanceOf(LeaderElectionState.Dormant.class);
            assertThat(ctx.confinementBreachesForTest()).as("the refusal is counted")
                                                        .isEqualTo(1);
            ctx.serially(() -> ctx.dispatchConfined(new ClusterFsmEvent.QuorumEstablished()));
            assertThat(ctx.fsm()
                          .current()).as("control: the same dispatch on the drainer reaches the FSM")
                                     .isNotInstanceOf(LeaderElectionState.Dormant.class);
            assertThat(ctx.confinementBreachesForTest()).as("control: a dispatch on the drainer is not refused")
                                                        .isEqualTo(1);
        } finally {
            ctx.stop();
        }
    }

    /// `dispatchSelf` from a handler that runs off the drainer: a view-free removal of the Led leader holds the leader
    /// to the view ([LeaderElectionState] `holdLeaderToView`), which dispatches `LeaderOutOfView` through `dispatchSelf`.
    /// Delivered straight to the FSM from a foreign thread, that dispatch is refused and the exception leaves the
    /// handler.
    @Test
    void dispatchSelf_reachedOffTheDrainer_isRefusedNamingTheRuleAndTheCaller() throws InterruptedException {
        var ctx = context(ctx2 -> LeaderElectionState.Led.fresh(ctx2, PEER), LONG);

        try {
            var refused = onForeignThread(() -> ctx.fsm()
                                                   .dispatch(new PeerRemovedFromView(PEER)));

            assertThat(refused).as("dispatchSelf off the serial drainer is refused")
                               .isInstanceOf(IllegalStateException.class)
                               .hasMessageContaining(RULE)
                               .hasMessageContaining("LeaderOutOfView")
                               .hasMessageContaining(FOREIGN);
            assertThat(ctx.fsm()
                          .current()).as("the refused LeaderOutOfView never reached the FSM: the tenure stands")
                                     .isInstanceOf(LeaderElectionState.Led.class);
            ctx.serially(() -> ctx.fsm()
                                  .dispatch(new PeerRemovedFromView(PEER)));
            assertThat(ctx.fsm()
                          .current()).as("control: on the drainer the same removal ends the tenure")
                                     .isNotInstanceOf(LeaderElectionState.Led.class);
            assertThat(ctx.confinementBreachesForTest()).as("control: only the off-drainer dispatch was refused")
                                                        .isEqualTo(1);
        } finally {
            ctx.stop();
        }
    }

    /// The detector for a new unwrapped site in the Led entry: the tenure is entered on the drainer with every Led
    /// delay (lease warm-up and interval, proposal timeout) [#SHORT]. The test awaits the condition the timers produce
    /// (the lease has checked twice, so the warm-up has passed), then requires that no dispatch is refused for a
    /// further 10 x [#SHORT]. A callback that dispatches off the drainer is refused on its scheduler thread: RED.
    @Test
    void ledTenure_whileEveryTimerFires_refusesNoDispatch() {
        var ctx = context(ctx2 -> LeaderElectionState.Led.fresh(ctx2, PEER), SHORT);

        try {
            ctx.serially(() -> ctx.fsm()
                                  .current()
                                  .onEntry());
            Awaitility.await("instrument: the lease checked twice, so every Led timer armed at entry has fired")
                      .atMost(Duration.ofSeconds(10))
                      .pollInterval(Duration.ofMillis(5))
                      .until(() -> leaseChecks.get() >= 2);
            Awaitility.await("no Led callback dispatched off the serial drainer")
                      .during(Duration.ofMillis(10 * SHORT.millis()))
                      .atMost(Duration.ofSeconds(10))
                      .pollInterval(Duration.ofMillis(5))
                      .until(() -> ctx.confinementBreachesForTest() == 0);
        } finally {
            ctx.stop();
        }
    }

    private static Throwable onForeignThread(Runnable call) throws InterruptedException {
        var thrown = new AtomicReference<Throwable>();
        var caller = Thread.ofPlatform()
                           .name(FOREIGN)
                           .start(() -> runCapturing(call, thrown));

        assertThat(caller.join(Duration.ofSeconds(10))).as("instrument: the foreign caller finished")
                                                       .isTrue();
        assertThat(thrown.get()).as("instrument: the foreign call threw")
                                .isNotNull();

        return thrown.get();
    }

    private static void runCapturing(Runnable call, AtomicReference<Throwable> thrown) {
        try {
            call.run();
        } catch (Throwable failure) {
            thrown.set(failure);
        }
    }

    private boolean pingFresh(NodeId leader) {
        leaseChecks.incrementAndGet();

        return true;
    }

    private static void ignoreChange(LeaderChange change) {}

    private LeaderElectionContext context(Function<LeaderElectionContext, LeaderElectionState> initial,
                                          TimeSpan ledDelay) {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderDispatchConfinementTest::ignoreChange);
        var fsm = Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-dispatch-confinement",
                                                                 f -> initial.apply(context(f, router, ledDelay)));

        return fsm.current()
                  .ctx();
    }

    private LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                          MessageRouter router,
                                          TimeSpan ledDelay) {
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
                                         ledDelay,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> false,
                                         Option::none,
                                         LONG,
                                         LONG,
                                         ledDelay,
                                         this::pingFresh,
                                         ledDelay);
    }
}
