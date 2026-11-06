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

/// #2574 F2 (know 26daff40e3): a backfill flight's landing re-runs once when a call JOINED it after it began, whatever the
/// landing -- failed or succeeded, an empty success included. The join is the offset-0 gap trigger; the flight it joined is
/// the catch-up read that left before offset 0 existed, or was refused or lost. A landing with no joiner never re-runs, so
/// re-runs chain only while real triggers keep joining. Before #2574 the joined trigger was swallowed and nothing pulled
/// until the 5 s redrive. #2574 F1 (know 89e17399a9): a refused flight holds its slot for the partition's refusal hold.
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
    /// The request of each pull, in the order the pulls were issued.
    private CopyOnWriteArrayList<ReplicationMessage.CatchupRequest> rerunRequests;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        recovery = manager.alignedRecovery();
        pulls = new CopyOnWriteArrayList<>();
        rerunRequests = new CopyOnWriteArrayList<>();
    }

    /// Every pull is held until the test answers it; `pulls` lists them in the order they were issued.
    private CatchupTransport gated() {
        return (_, request) -> {
            var response = Promise.<ReplicationMessage.CatchupResponse>promise();

            rerunRequests.add(request);
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

    /// #2574 F2 (know 26daff40e3): a landing with a late joiner re-runs once, whatever the landing.
    @Nested
    class JoinedFailedFlight {
        /// Flight #1 never answers, a second call joins, flight #1 lands READ_FORWARD_TIMEOUT -> exactly one more pull. The
        /// re-run, with no joiner of its own, lands failed too and starts nothing: no loop without new triggers.
        @Test
        void joinedFlight_landsWithReadForwardTimeout_rerunsExactlyOnce_andAnUnjoinedRerunDoesNotLoop() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);
            var joined = backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the second call joined flight #1").hasSize(1);

            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(joined.await(AWAIT).isFailure()).as("the joiner resolves with the flight it joined").isTrue();
            assertThat(pulls).as("the failed joined flight re-ran exactly once").hasSize(2);

            pulls.get(1).fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);
            sleep(100L);

            assertThat(pulls).as("the re-run had no joiner: it does not re-run, never a loop").hasSize(2);

            backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the slot is free afterwards: a later call pulls").hasSize(3);
        }

        /// Re-runs chain only while real triggers keep joining: a joiner of the re-run earns one more pull.
        @Test
        void rerunWithAJoinerOfItsOwn_landing_rerunsOnceMore() {
            var backfill = sourceBackfill();

            backfill.backfill(STREAM, PARTITION);
            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);
            assertThat(awaitPulls(2)).isEqualTo(2);
            backfill.backfill(STREAM, PARTITION);
            pulls.get(1).fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(awaitPulls(3)).as("the re-run's own joiner earns one more pull").isEqualTo(3);
            sleep(100L);
            assertThat(pulls).hasSize(3);
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

    /// #2574 F1 (know 89e17399a9): a flight that fails on a transport refusal ([StreamForwardError.ForwardNotSent])
    /// delivers its failure at once but keeps the slot for the partition's refusal hold (250 ms doubling to 4 s, +-20%),
    /// so the gap triggers arriving meanwhile pull nothing, and the trigger is honoured by one re-run when the hold ends.
    @Nested
    class RefusedFlightHoldsItsSlot {
        /// The first hold's jittered range.
        private static final long FIRST_HOLD_MIN_MS = 200L;
        private static final long FIRST_HOLD_MAX_MS = 300L;

        private static StreamForwardError.ForwardNotSent refusal() {
            return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(SOURCE));
        }

        @Test
        void refusedFlight_failsAtOnce_triggersDuringTheHoldPullNothing_thenOneRerunAfterTheFirstHold() {
            var backfill = sourceBackfill();
            var startedAt = System.nanoTime();
            var first = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().fail(refusal());

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(elapsedMs(startedAt)).as("the failure is delivered at once, not after the hold").isLessThan(FIRST_HOLD_MIN_MS);

            for (var trigger = 0; trigger < 10; trigger++) {
                assertThat(backfill.backfill(STREAM, PARTITION).await(AWAIT).isFailure()).as("a trigger during the hold joins")
                                                                                         .isTrue();
            }

            assertThat(pulls).as("no trigger pulled during the hold").hasSize(1);
            assertThat(awaitPulls(2)).as("the hold's end re-ran the joined backfill once").isEqualTo(2);
            assertThat(elapsedMs(startedAt)).as("the first hold: 250 ms +-20%, not the 2 s read timeout")
                                            .isBetween(FIRST_HOLD_MIN_MS, FIRST_HOLD_MAX_MS + 150L);
        }

        /// A call that joined while the read was still in flight is honoured too: the refusal holds the slot and the hold's
        /// end re-runs once.
        @Test
        void flightJoinedBeforeItsRefusal_holdsTheSlot_thenOneRerun() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().fail(refusal());

            assertThat(first.await(AWAIT).isFailure()).isTrue();
            assertThat(pulls).as("held: nothing pulls before the hold's end").hasSize(1);
            assertThat(awaitPulls(2)).as("the earlier joiner's trigger re-ran at the hold's end").isEqualTo(2);
        }

        @Test
        void refusedFlight_withoutAJoiner_releasesTheSlotAtTheHoldsEnd_withoutARerun() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().fail(refusal());
            assertThat(first.await(AWAIT).isFailure()).isTrue();
            sleep(FIRST_HOLD_MAX_MS + 200L);

            assertThat(pulls).as("nothing joined, so nothing re-ran").hasSize(1);

            backfill.backfill(STREAM, PARTITION);

            assertThat(pulls).as("the slot was released: a later call pulls").hasSize(2);
        }

        /// A lane that keeps refusing doubles the hold: the joined re-run, refused again, holds about 500 ms before its own
        /// joiner's re-run.
        @Test
        void refusedAgain_holdsDoubled() {
            var backfill = sourceBackfill();

            backfill.backfill(STREAM, PARTITION);
            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().fail(refusal());
            assertThat(awaitPulls(2)).isEqualTo(2);
            backfill.backfill(STREAM, PARTITION);
            var refusedAgainAt = System.nanoTime();

            pulls.get(1).fail(refusal());

            assertThat(awaitPulls(3)).isEqualTo(3);
            assertThat(elapsedMs(refusedAgainAt)).as("the second consecutive hold: 500 ms +-20%").isGreaterThanOrEqualTo(400L);
        }

        /// Reset on Sent: a flight that lands without a refusal (its read was sent) resets the partition's hold, so the next
        /// refusal holds the first 250 ms again, not the doubled 500 ms.
        @Test
        void sentLanding_resetsTheHold() {
            var backfill = sourceBackfill();

            backfill.backfill(STREAM, PARTITION);
            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().fail(refusal());
            assertThat(awaitPulls(2)).isEqualTo(2);
            pulls.get(1).fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);
            sleep(50L);
            backfill.backfill(STREAM, PARTITION);
            assertThat(pulls).hasSize(3);
            backfill.backfill(STREAM, PARTITION);
            var refusedAt = System.nanoTime();

            pulls.get(2).fail(refusal());

            assertThat(awaitPulls(4)).isEqualTo(4);
            assertThat(elapsedMs(refusedAt)).as("reset to the first hold, not doubled").isLessThan(FIRST_HOLD_MAX_MS + 80L);
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

        private long elapsedMs(long since) {
            return (System.nanoTime() - since) / 1_000_000L;
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

    private int awaitPulls(int expected) {
        var deadline = System.currentTimeMillis() + 5_000L;

        while (pulls.size() < expected && System.currentTimeMillis() < deadline) {
            sleep(5L);
        }

        return pulls.size();
    }

    private static void sleep(long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /// Pin (1): a landing with no joiner never re-runs, failed or succeeded.
    @Nested
    class NoJoinerNoRerun {
        @Test
        void unjoinedFlight_landsFailed_doesNotRerun() {
            var backfill = sourceBackfill();
            var only = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);

            assertThat(only.await(AWAIT).isFailure()).isTrue();
            sleep(100L);
            assertThat(pulls).as("no call joined: nothing to honour").hasSize(1);
        }

        @Test
        void unjoinedFlight_succeeds_doesNotRerun() {
            var backfill = sourceBackfill();
            var only = backfill.backfill(STREAM, PARTITION);

            pulls.getFirst().succeed(responseOf(0, 5));

            assertThat(only.await(AWAIT).isSuccess()).isTrue();
            sleep(100L);
            assertThat(pulls).as("no call joined: nothing to honour").hasSize(1);
        }
    }

    @Nested
    class JoinedLanding {
        /// A joined flight that SUCCEEDED re-runs once too (know 26daff40e3): the joiner's demand is newer than the flight.
        @Test
        void joinedFlight_succeeds_rerunsOnce() {
            var backfill = sourceBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().succeed(responseOf(0, 5));

            assertThat(first.await(AWAIT).isSuccess()).isTrue();
            assertThat(awaitPulls(2)).as("the joined success re-ran once").isEqualTo(2);
        }

        /// A call reacting to the joined flight's failure joins the re-run instead of pulling itself; that join earns the
        /// re-run one more pull, and nothing after it.
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
            assertThat(awaitPulls(3)).as("the reaction's join earned the re-run one more pull").isEqualTo(3);
            pulls.get(2).fail(StreamForwardError.General.READ_FORWARD_TIMEOUT);
            sleep(100L);
            assertThat(pulls).as("no joiner after that: no further pull").hasSize(3);
        }
    }

    /// Pin (2), know 26daff40e3: a joined flight that SUCCEEDS EMPTY re-runs once. Its read reached the owner before offset
    /// 0 existed, the owner answered empty, and the offset-0 trigger joined it; the re-run pulls offset 0.
    ///
    /// On a fresh stream the FIRST empty owner answer does not succeed: it starts the no-source wait (`sourceWaitBound`) and
    /// fails NO_SOURCE_REPLICA, re-run as a failed joined landing (pinned too). Past that wait an empty answer succeeds (the
    /// #631 genesis promotion at -1); that is the succeeded-empty landing.
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
        /// The owner's head as its watermark probe reports it: empty until offset 0 is appended.
        private final AtomicLong ownerHead = new AtomicLong(-1L);

        private PartitionBackfill ownerBackfill() {
            registry.registerReplica(STREAM, PARTITION, NON_OWNER);

            return partitionBackfill(registry,
                                     recovery,
                                     gated(),
                                     (_, _, _) -> Promise.success(ownerHead.get()),
                                     (_, _) -> -1L,
                                     NON_OWNER,
                                     BOUND,
                                     clock::get,
                                     () -> MEMBERS,
                                     (_, _) -> Option.some(new CommittedOwner(OWNER, Epoch.ZERO)));
        }

        /// Fresh stream, inside the no-source wait: the empty answer FAILS the joined flight, which re-runs once.
        @Test
        void joinedFlight_emptyOwnerAnswer_withinTheWait_failsAndRerunsOnce() {
            var backfill = ownerBackfill();
            var first = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            pulls.getFirst().succeed(emptyAnswer(OWNER));

            assertThat(first.await(AWAIT).isFailure()).as("an empty owner answer inside the wait is NO_SOURCE, a failure")
                                                    .isTrue();
            assertThat(awaitPulls(2)).as("so the joined trigger is honoured by the re-run").isEqualTo(2);
        }

        /// The pin: the empty answer succeeds (CAUGHT_UP at -1), the late joiner earns exactly one re-run, and that re-run
        /// pulls offset 0, which the owner appended meanwhile.
        @Test
        void joinedFlight_succeedsEmpty_rerunsOnce_andTheRerunPullsOffset0() {
            var backfill = armedPastTheWait();
            var flight = backfill.backfill(STREAM, PARTITION);

            backfill.backfill(STREAM, PARTITION);
            // the owner appends offset 0 after the flight's read reached it: its probe reports it from the re-run on
            backfill.releaseWindowHook(() -> ownerHead.set(0L));
            pulls.get(1).succeed(emptyAnswer(OWNER));

            assertThat(flight.await(AWAIT).isSuccess()).as("past the wait an empty owner answer promotes").isTrue();
            assertThat(awaitPulls(3)).as("the joined succeeded-empty landing re-ran once").isEqualTo(3);

            var rerunRequest = rerunRequests.get(2);

            assertThat(rerunRequest.fromOffset()).as("the re-run pulls from offset 0").isEqualTo(0L);
            pulls.get(2).succeed(responseFrom(OWNER, 0, 1));

            assertThat(awaitConfirmed(0L)).as("offset 0 landed on the replica").isEqualTo(0L);
            sleep(100L);
            assertThat(pulls).as("exactly one re-run: the re-run had no joiner").hasSize(3);
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

        private long awaitConfirmed(long expected) {
            var deadline = System.currentTimeMillis() + 5_000L;

            while (descriptorFor(NON_OWNER).confirmedOffset() < expected && System.currentTimeMillis() < deadline) {
                sleep(5L);
            }

            return descriptorFor(NON_OWNER).confirmedOffset();
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

    private static ReplicationMessage.CatchupResponse responseFrom(NodeId source, long from, int count) {
        var payloads = new ArrayList<byte[]>();
        var timestamps = new ArrayList<Long>();

        for (var offset = from; offset < from + count; offset++) {
            payloads.add(("event-" + offset).getBytes());
            timestamps.add(1000L + offset);
        }

        return catchupResponse(source, STREAM, PARTITION, from, from + count - 1, payloads, timestamps);
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
