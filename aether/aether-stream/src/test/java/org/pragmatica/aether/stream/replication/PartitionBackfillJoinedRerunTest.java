// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

/// #2574 F2: a backfill call that joins a flight which then FAILS re-runs the backfill once, bounded: once, never a loop.
/// The join is the offset-0 gap trigger; the flight it joined is the catch-up read that left before offset 0 existed and
/// was refused or lost. Before #2574 the joined trigger was swallowed and nothing pulled until the 5 s redrive.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PartitionBackfillJoinedRerunTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId SOURCE = NodeId.randomNodeId();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private ReplicaRegistry registry;
    private AlignedRecovery recovery;
    private CopyOnWriteArrayList<Promise<ReplicationMessage.CatchupResponse>> pulls;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        recovery = manager.alignedRecovery();
        pulls = new CopyOnWriteArrayList<>();
    }

    /// Every pull is held until the test answers it; `pulls` lists them in the order they were issued.
    private CatchupTransport gated() {
        return (_, _) -> {
            var response = Promise.<ReplicationMessage.CatchupResponse>promise();

            pulls.add(response);

            return response;
        };
    }

    private PartitionBackfill sourceBackfill() {
        registry.registerReplica(STREAM, PARTITION, SOURCE);
        registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
        registry.registerReplica(STREAM, PARTITION, SELF);

        return partitionBackfill(registry, recovery, gated(), SELF);
    }

    @Nested
    class JoinedFailedFlight {
        /// The brief's case: flight #1 never answers, a second call joins, flight #1 lands READ_FORWARD_TIMEOUT -> a second
        /// pull is issued, exactly once. A further joiner on the re-run, which fails again, starts no third pull.
        @Test
        void joinedFlight_landsWithReadForwardTimeout_rerunsExactlyOnce_andTheRerunDoesNotLoop() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);
            var joined = backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the second call joined flight #1").hasSize(1);

            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(joined.await(AWAIT).isFailure()).as("the joiner resolves with the flight it joined").isTrue();
            assertThat(pulls).as("the failed joined flight re-ran once").hasSize(2);

            var joinedRerun = backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("a call during the re-run joins it").hasSize(2);

            pulls.get(1).fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(joinedRerun.await(AWAIT).isFailure()).isTrue();
            assertThat(pulls).as("a joined re-run that fails does not re-run again: once, never a loop").hasSize(2);

            backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the slot is free afterwards: a later call pulls").hasSize(3);
        }

        /// The re-run pulls the data the joined trigger reported and lands it.
        @Test
        void joinedFlight_failed_rerunLandsTheRecords() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);
            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(pulls).as("the re-run pulled").hasSize(2);

            var rerun = backfill.backfill(STREAM, PARTITION);

            pulls.get(1).succeed(responseOf(0, 5));

            assertThat(rerun.await(AWAIT).isSuccess()).as("the call that joined the re-run sees it land").isTrue();
            assertThat(descriptorFor(SELF).state()).as("the re-run caught the replica up").isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(SELF).confirmedOffset()).isEqualTo(4L);
        }

        /// The failure that resolves the flight may be its idle bound rather than its run: a joined flight that stalls past
        /// the bound re-runs too.
        @Test
        void joinedFlight_stalledPastItsBound_rerunsOnce() {
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
            registry.registerReplica(STREAM, PARTITION, SELF);
            var backfill = partitionBackfill(registry, recovery, gated(), SELF, TimeSpan.timeSpan(200).millis());
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);

            assertThat(first.await(AWAIT).isFailure()).as("the bound failed the flight").isTrue();
            assertThat(pulls).as("the stalled joined flight re-ran once").hasSize(2);
        }
    }

    private static void react(PartitionBackfill backfill, Promise<Long> reacted, CountDownLatch reactionMade) {
        backfill.backfill(STREAM, PARTITION).onResult(reacted::resolve);
        reactionMade.countDown();
    }

    /// #2574 F1: a flight that fails on a transport refusal ([StreamForwardError.ForwardNotSent]) delivers its failure at
    /// once but keeps the slot for the refusal's wait, so the gap triggers arriving meanwhile pull nothing (as they did
    /// while the silent 2 s wait held the slot), and the trigger is still honoured by one re-run when the wait ends.
    @Nested
    class RefusedFlightHoldsItsSlot {
        private static final TimeSpan REFUSAL_WAIT = TimeSpan.timeSpan(400).millis();

        private static StreamForwardError.ForwardNotSent refusal() {
            return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(SOURCE), REFUSAL_WAIT);
        }

        @Test
        void refusedFlight_failsAtOnce_triggersDuringTheWaitPullNothing_thenOneRerun() {
            var backfill = sourceBackfill();
            var startedAt = System.nanoTime();
            var first = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().fail(refusal());

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat((System.nanoTime() - startedAt) / 1_000_000L).as("the failure is delivered at once, not after the wait")
                                                                     .isLessThan(REFUSAL_WAIT.millis());

            for (var trigger = 0; trigger < 20; trigger++) {
                assertThat(backfill.backfill(STREAM, PARTITION).await(AWAIT).isFailure()).as("a trigger during the wait joins")
                                                                                         .isTrue();
            }

            assertThat(pulls).as("no trigger pulled during the refusal's wait").hasSize(1);
            assertThat(awaitPulls(2)).as("the wait's end re-ran the joined backfill once").isEqualTo(2);
            assertThat((System.nanoTime() - startedAt) / 1_000_000L).as("not before the wait ended")
                                                                     .isGreaterThanOrEqualTo(REFUSAL_WAIT.millis());
        }

        /// A call that joined while the read was still in flight is honoured too: the refusal holds the slot and the
        /// wait's end re-runs once.
        @Test
        void flightJoinedBeforeItsRefusal_holdsTheSlot_thenOneRerun() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().fail(refusal());

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(pulls).as("held: nothing pulls before the wait's end").hasSize(1);
            assertThat(awaitPulls(2)).as("the earlier joiner's trigger re-ran at the wait's end").isEqualTo(2);
        }

        @Test
        void refusedFlight_withoutAJoiner_releasesTheSlotAtTheWaitsEnd_withoutARerun() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().fail(refusal());
            assertThat(first.await(AWAIT).isFailure()).isTrue();
            sleep(REFUSAL_WAIT.millis() + 300L);

            assertThat(pulls).as("nothing joined, so nothing re-ran").hasSize(1);

            backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the slot was released: a later call pulls").hasSize(2);
        }

        /// The bound still holds under a lane that keeps refusing: the re-run is refused too, holds the slot in turn, and its
        /// joiners start no third pull; one pull per refusal wait.
        @Test
        void refusedRerun_joinedAndRefusedAgain_doesNotRerunAgain() {
            var backfill = sourceBackfill();

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().fail(refusal());
            backfill.backfill(STREAM, PARTITION);
            assertThat(awaitPulls(2)).isEqualTo(2);
            pulls.get(1).fail(refusal());
            backfill.backfill(STREAM, PARTITION);
            sleep(REFUSAL_WAIT.millis() + 300L);

            assertThat(pulls).as("a refused re-run that was joined does not re-run again").hasSize(2);
        }

        /// Only a refusal holds the slot: a flight that fails any other way releases it at once.
        @Test
        void flightFailedOtherwise_releasesTheSlotAtOnce() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);
            assertThat(first.await(AWAIT).isFailure()).isTrue();
            backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the next call pulled at once").hasSize(2);
        }

        private int awaitPulls(int expected) {
            var deadline = System.currentTimeMillis() + 5_000L;

            while (pulls.size() < expected && System.currentTimeMillis() < deadline) {
                sleep(10L);
            }

            return pulls.size();
        }
    }

    /// The race guards, pinned through the backfill's test seams.
    @Nested
    class Races {
        /// A call finds a flight in the slot, and the flight is released before the call marks its join: the call must not
        /// join a flight that already left (its trigger would be swallowed, the #2574 bug), so it starts over and pulls.
        @Test
        void join_racingTheFlightsRelease_startsOver_andPulls() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);
            var raced = new AtomicBoolean();

            backfill.joinWindowHook(() -> releaseFirstOnce(raced, first));
            var late = backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the late call started a flight of its own").hasSize(2);
            assertThat(late).as("it did not take the released flight's result").isNotSameAs(first);
        }

        /// The failed joined flight left the slot and another call took it before the re-run was decided: that flight
        /// serves the trigger, and the re-run must not start beside it (two concurrent pulls of one partition, #1638).
        @Test
        void rerun_whenAnotherCallTookTheSlot_doesNotStartBesideIt() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            backfill.releaseWindowHook(() -> takeSlotOnce(backfill));
            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(pulls).as("the call that took the slot pulled; no re-run beside it").hasSize(2);
        }

        private void releaseFirstOnce(AtomicBoolean raced, Promise<Long> first) {
            if (raced.compareAndSet(false, true)) {
                pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);
                first.await(AWAIT);
            }
        }

        private final AtomicBoolean taken = new AtomicBoolean();

        private void takeSlotOnce(PartitionBackfill backfill) {
            if (taken.compareAndSet(false, true)) {
                backfill.backfill(STREAM, PARTITION);
            }
        }
    }

    private static void sleep(long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Nested
    class NoRerun {
        @Test
        void unjoinedFlight_landsFailed_doesNotRerun() {
            var backfill = sourceBackfill();
            var only = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(only.await(AWAIT).isFailure()).isTrue();
            assertThat(pulls).as("no call joined: nothing to honour").hasSize(1);
        }

        @Test
        void joinedFlight_succeeds_doesNotRerun() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().succeed(responseOf(0, 5));

            assertThat(first.await(AWAIT).isSuccess()).isTrue();
            assertThat(pulls).as("a joined flight that succeeded is not re-run").hasSize(1);
        }

        /// The re-run never joins: a call that took the slot between the failed flight's removal and the re-run serves the
        /// trigger. Here the joiner reacts to the failure by calling again, which joins the re-run instead of adding a pull.
        @Test
        void joinedFlight_failed_aCallReactingToTheFailureJoinsTheRerun() throws InterruptedException {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);
            var reacted = Promise.<Long>promise();
            var reactionMade = new CountDownLatch(1);

            backfill.backfill(STREAM, PARTITION)
                    .onFailure(_ -> react(backfill, reacted, reactionMade));
            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(reactionMade.await(10, TimeUnit.SECONDS)).as("the joiner reacted").isTrue();
            assertThat(pulls).as("the failed joined flight re-ran, and the reaction joined it").hasSize(2);
            pulls.get(1).fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(reacted.await(AWAIT).isFailure()).as("the reacting call joined the re-run and shares its result").isTrue();
            assertThat(pulls).as("the reacting call added no pull of its own").hasSize(2);
        }
    }

    /// The case the ruling leaves open (sent to the CTO): a joined flight that SUCCEEDS EMPTY. Its read reached the owner
    /// before offset 0 existed, the owner answered empty, and the offset-0 trigger joined it. As ruled, it does not re-run.
    ///
    /// What the fixture shows of today's behaviour. On a fresh stream the FIRST empty owner answer does not succeed: it
    /// starts the no-source wait (`sourceWaitBound`, production `10 x read_forward_timeout` = 20 s) and FAILS with
    /// NO_SOURCE_REPLICA, so F2 re-runs it (pinned below, enabled). Only once that wait has elapsed does an empty answer
    /// succeed (the #631 genesis promotion at -1); then the joined trigger is swallowed, the replica is CAUGHT_UP at -1 with
    /// nothing pulled, and the next pull comes from the redrive.
    @Nested
    class SucceededEmptyWithJoiner {
        private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
        private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
        private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
        private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
        private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
        private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
        private static final NodeId NON_OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();

        private final AtomicLong clock = new AtomicLong(0L);

        private PartitionBackfill ownerBackfill() {
            registry.registerReplica(STREAM, PARTITION, NON_OWNER);

            return partitionBackfill(registry,
                                     recovery,
                                     gated(),
                                     (_, _, _) -> Promise.success(-1L),
                                     (_, _) -> -1L,
                                     NON_OWNER,
                                     BOUND,
                                     clock::get,
                                     () -> MEMBERS,
                                     (_, _) -> Option.some(new CommittedOwner(OWNER, Epoch.ZERO)));
        }

        /// Fresh stream, inside the no-source wait: the empty answer FAILS the joined flight, so F2 re-runs it.
        @Test
        void joinedFlight_emptyOwnerAnswer_withinTheWait_failsAndRerunsOnce() {
            var backfill = ownerBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().succeed(emptyAnswer(OWNER));

            assertThat(first.await(AWAIT).isFailure()).as("an empty owner answer inside the wait is NO_SOURCE, a failure")
                                                    .isTrue();
            assertThat(pulls).as("so the joined trigger is honoured by the re-run").hasSize(2);
        }

        /// TRIPWIRE of today's behaviour, as ruled: past the wait the empty answer SUCCEEDS (CAUGHT_UP at -1) and the joined
        /// trigger pulls nothing. If the CTO rules that a joined succeeded-empty flight re-runs, this goes red: delete it and
        /// enable [#joinedFlight_succeedsEmpty_rerunsOnce_whenRuled].
        @Test
        void joinedFlight_succeedsEmpty_pastTheWait_doesNotRerun_tripwire() {
            var backfill = armedPastTheWait();
            var flight = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.get(1).succeed(emptyAnswer(OWNER));

            assertThat(flight.await(AWAIT).isSuccess()).as("past the wait an empty owner answer promotes").isTrue();
            assertThat(descriptorFor(NON_OWNER).state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(descriptorFor(NON_OWNER).confirmedOffset()).as("nothing was pulled").isEqualTo(-1L);
            assertThat(pulls).as("TRIPWIRE (#2574 open question): a joined flight that succeeded empty does not re-run today; "
                                 + "if this fails, the ruling changed: enable joinedFlight_succeedsEmpty_rerunsOnce_whenRuled")
                             .hasSize(2);
        }

        /// The ruling-dependent expectation: a joined flight that succeeded empty re-runs once.
        @Disabled("#2574 open question to the CTO: a joined flight that SUCCEEDS EMPTY does not re-run as ruled in know 3038b24582; "
                  + "enable if the ruling extends F2 to it, and delete the tripwire beside it")
        @Test
        void joinedFlight_succeedsEmpty_rerunsOnce_whenRuled() {
            var backfill = armedPastTheWait();
            var flight = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.get(1).succeed(emptyAnswer(OWNER));

            assertThat(flight.await(AWAIT).isSuccess()).isTrue();
            assertThat(pulls).as("the joined trigger is honoured by one re-run").hasSize(3);
        }

        /// How long until something pulls again after the swallowed trigger: the CAUGHT_UP-at--1 non-owner copy is a redrive
        /// candidate at once, so the next redrive tick pulls it -- in production within one `STREAM_BACKFILL_REDRIVE_INTERVAL`
        /// (5 s), unless a later live batch triggers a gap first.
        @Test
        void joinedFlight_succeedsEmpty_pastTheWait_isARedriveCandidateAtOnce() {
            var backfill = armedPastTheWait();
            var flight = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.get(1).succeed(emptyAnswer(OWNER));

            assertThat(flight.await(AWAIT).isSuccess()).isTrue();
            assertThat(backfill.redriveCandidates()).as("the next redrive tick pulls it")
                                                    .containsExactly(new PartitionKey(STREAM, PARTITION));
        }

        /// The first call starts the no-source wait at t=0 and fails; the clock then moves past it.
        private PartitionBackfill armedPastTheWait() {
            var backfill = ownerBackfill();
            var arming = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().succeed(emptyAnswer(OWNER));
            assertThat(arming.await(AWAIT).isFailure()).as("the first empty answer starts the wait").isTrue();
            clock.set(BOUND.millis() + 1L);

            return backfill;
        }
    }

    private ReplicaDescriptor descriptorFor(NodeId nodeId) {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(nodeId))
                       .findFirst()
                       .orElseThrow();
    }

    private static ReplicationMessage.CatchupResponse emptyAnswer(NodeId source) {
        return catchupResponse(source, STREAM, PARTITION, 0, -1, List.of(), List.of());
    }

    private static ReplicationMessage.CatchupResponse responseOf(long from, int count) {
        var payloads = new ArrayList<byte[]>();
        var timestamps = new ArrayList<Long>();

        for (var offset = from; offset < from + count; offset++) {
            payloads.add(("event-" + offset).getBytes());
            timestamps.add(1000L + offset);
        }

        return catchupResponse(SOURCE, STREAM, PARTITION, from, from + count - 1, payloads, timestamps);
    }
}
