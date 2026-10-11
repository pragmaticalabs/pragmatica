// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OwnerActivation.ActivationBlock;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2004: a block alarm's recovery is never delivered BEFORE its raise. The aggregator drops a recovery with no open warning, so a
/// `[resolve, raise]` order leaves the CRITICAL open until the next episode. Every path that records a block and raises it
/// (`report`, the unreachable wait, the lineage slot) is driven here with a raise that is held open while a clear lands on another
/// thread; the delivered order is `[raise, resolve]`, never `[resolve, raise]`. The clear is not allowed to finish before the raise
/// does (it waits on the gate's alarm monitor), which is observed from the clearing thread's state, not by sleeping.
///
/// Also pins that a block of another kind never ends the lineage block (v-1914 P1 / v-1979 round 2).
class BlockAlarmOrderingTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId PEER = new NodeId("peer");
    private static final NodeId OTHER = new NodeId("other");
    private static final Epoch EPOCH = Epoch.epoch(1L, 2L, 3L);
    private static final long LOCAL = 9L;
    private static final long PEER_AHEAD = 15L;
    private static final long WAIT_SECONDS = 10L;

    enum PeerMode {
        ANSWERS,
        OVERSIZED,
        SILENT,
        DIVERGENT
    }

    private final AtomicReference<PeerMode> peerMode = new AtomicReference<>(PeerMode.ANSWERS);
    private final AtomicBoolean refuse = new AtomicBoolean(true);
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.some(withStart(SELF)));
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<Consumer<ActivationBlock>> duringRaise = new AtomicReference<>(_ -> {});
    private final OwnerActivation gate = gate();

    private OwnerActivation gate() {
        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> true,
                                               Option.none(),
                                               () -> List.of(SELF, PEER),
                                               (_, _, _) -> switch (peerMode.get()) {
                                                   case ANSWERS -> Promise.success(LOCAL);
                                                   case OVERSIZED -> new OwnerPeerReads.EventExceedsReadCap(4L).promise();
                                                   case SILENT -> Causes.cause("no answer").promise();
                                                   case DIVERGENT -> Promise.success(PEER_AHEAD);
                                               },
                                               (_, _) -> LOCAL,
                                               (_, _, _, tail) -> Promise.success(tail),
                                               () -> true,
                                               (node, _, _, from, to) -> Promise.success(LongStream.rangeClosed(from, to)
                                                                                                   .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset,
                                                                                                                                                           ((node.equals(PEER) && peerMode.get() == PeerMode.DIVERGENT
                                                                                                                                                             ? "div-"
                                                                                                                                                             : "rec-") + offset).getBytes(StandardCharsets.UTF_8),
                                                                                                                                                           1L))
                                                                                                   .toList()),
                                               new OwnerActivation.BlockAlarm() {
                                                   @Override
                                                   public Unit raise(ActivationBlock block) {
                                                       duringRaise.get().accept(block);
                                                       events.add("raise:" + block.getClass().getSimpleName());

                                                       return Unit.unit();
                                                   }

                                                   @Override
                                                   public Unit resolved(ActivationBlock block) {
                                                       events.add("resolved:" + block.getClass().getSimpleName());

                                                       return Unit.unit();
                                                   }
                                               },
                                               TimeSpan.timeSpan(0).millis(),
                                               (_, _) -> 1L,
                                               (_, _, current, start, restarted) -> refuse.get()
                                                                                    ? OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED.<Unit> promise()
                                                                                    : applied(current, start, restarted));
    }

    private Promise<Unit> applied(StreamPartitionOwnershipValue current, long start, boolean restarted) {
        record.set(Option.some(restarted
                               ? current.restarted(start, HlcTimestamp.ZERO)
                               : current.withEpochStart(start)));

        return Promise.success(Unit.unit());
    }

    /// A promotion refused for a divergent peer: the block goes through `report`. Red when `report` records and raises without the
    /// alarm monitor: the clear resolves a block whose raise has not been delivered yet.
    @Test
    void report_aClearDuringTheRaise_deliversRaiseThenResolve() throws InterruptedException {
        record.set(Option.some(notInTheCommittedIsr()));
        peerMode.set(PeerMode.DIVERGENT);

        clearWhileRaising(ActivationBlock.DivergentPeer.class, this::activateOnce, this::clearTheBlocks);

        assertThat(events).containsExactly("raise:DivergentPeer", "resolved:DivergentPeer");
    }

    /// The unreachable wait (alarm window 0): same shape through `trackUnreachable`.
    @Test
    void unreachableWait_aClearDuringTheRaise_deliversRaiseThenResolve() throws InterruptedException {
        peerMode.set(PeerMode.SILENT);

        clearWhileRaising(ActivationBlock.HoldersUnreachable.class, this::activateUntilRaised, this::clearTheBlocks);

        assertThat(events).containsExactly("raise:HoldersUnreachable", "resolved:HoldersUnreachable");
    }

    /// The lineage slot: the Nth refusal raises, and quorum loss lands during that raise.
    @Test
    void lineageSlot_quorumLossDuringTheRaise_deliversRaiseThenResolve() throws InterruptedException {
        clearWhileRaising(ActivationBlock.LineageRefused.class,
                          this::refuseUntilRaised,
                          () -> gate.onQuorumStateChange(ClusterStateNotification.passive()));

        assertThat(events).containsExactly("raise:LineageRefused", "resolved:LineageRefused");
    }

    /// A clear that lands BEFORE the block is recorded leaves nothing to resolve: the delivered events are empty or
    /// `[raise, resolve]`, never a lone resolve.
    @Test
    void aClearWithNothingRecorded_resolvesNothing() {
        clearTheBlocks();
        gate.onQuorumStateChange(ClusterStateNotification.passive());

        assertThat(events).isEmpty();
    }

    /// v-1914 P1 (adopted): a block of another kind neither replaces nor ends the lineage block. Raised and ended oversized and
    /// unreachable episodes stand between the lineage block's raise and its landing; it resolves exactly once, at the landing.
    @Test
    void lineageBlock_survivesOtherKindsRaisedAndEnded_andResolvesOnceOnLanding() {
        refuseUntilRaised();
        assertThat(events).containsExactly("raise:LineageRefused");

        peerMode.set(PeerMode.OVERSIZED);
        activateOnce();
        assertThat(gate.blockOf(STREAM, PARTITION).map(b -> b.getClass().getSimpleName()).or("none")).isEqualTo("PeerEventExceedsReadCap");

        peerMode.set(PeerMode.ANSWERS);
        activateOnce();
        assertThat(events).as("the oversized block ended, the lineage block stands").doesNotContain("resolved:LineageRefused");
        assertThat(gate.blockOf(STREAM, PARTITION).map(b -> b.getClass().getSimpleName()).or("none")).isEqualTo("LineageRefused");

        peerMode.set(PeerMode.SILENT);
        activateOnce();
        peerMode.set(PeerMode.ANSWERS);
        activateOnce();
        assertThat(events).as("no other block's end resolved the lineage block").doesNotContain("resolved:LineageRefused");
        assertThat(gate.blockOf(STREAM, PARTITION).map(b -> b.getClass().getSimpleName()).or("none")).isEqualTo("LineageRefused");

        refuse.set(false);
        assertThat(activateOnce()).isTrue();
        assertThat(events.stream().filter("raise:LineageRefused"::equals).count()).isEqualTo(1L);
        assertThat(events.stream().filter("resolved:LineageRefused"::equals).count()).isEqualTo(1L);
        assertThat(gate.blockOf(STREAM, PARTITION)).isEqualTo(Option.none());
    }

    private void clearWhileRaising(Class<? extends ActivationBlock> kind, Runnable raiser, Runnable clearer) throws InterruptedException {
        var inRaise = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        duringRaise.set(block -> {
            if (kind.isInstance(block)) {
                inRaise.countDown();
                awaitUninterruptibly(release);
            }
        });

        var raising = new Thread(raiser, "raising");
        var clearing = new Thread(clearer, "clearing");

        raising.start();
        assertThat(inRaise.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the raise started").isTrue();
        clearing.start();
        awaitState(clearing);
        release.countDown();
        raising.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        clearing.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(raising.isAlive() || clearing.isAlive()).as("both finished").isFalse();
    }

    /// The clearing thread either finished (nothing held it: the unguarded order) or is parked on the alarm monitor behind the raise.
    private static void awaitState(Thread clearing) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

        while (clearing.getState() != Thread.State.BLOCKED && clearing.getState() != Thread.State.TERMINATED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean activateOnce() {
        return gate.activate(STREAM, PARTITION).await().isSuccess();
    }

    /// Alarm window 0 needs a later attempt than the first to count the wait as past the window.
    private void activateUntilRaised() {
        for (var attempt = 0; attempt < 50 && events.stream().noneMatch(event -> event.startsWith("raise:")); attempt++) {
            gate.activate(STREAM, PARTITION).await();
        }
    }

    /// The refusal count is taken by a callback that may run after `activate` returns; keep refusing until the Nth raises.
    private void refuseUntilRaised() {
        for (var attempt = 0; attempt < 50 && events.stream().noneMatch("raise:LineageRefused"::equals); attempt++) {
            gate.activate(STREAM, PARTITION).await();
        }
    }

    private void clearTheBlocks() {
        gate.clearBlock(PartitionKey.partitionKey(STREAM, PARTITION));
    }

    private static StreamPartitionOwnershipValue withStart(NodeId owner) {
        return new StreamPartitionOwnershipValue(owner, EPOCH, 3L, HlcTimestamp.ZERO, List.of(owner), 1L, false, List.of(), List.of(new EpochStart(EPOCH, 5L)));
    }

    /// An ISR that does not name this node: a divergent peer keeps blocking instead of being relaxed.
    private static StreamPartitionOwnershipValue notInTheCommittedIsr() {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 3L, 0), 3L, HlcTimestamp.ZERO, List.of(PEER, OTHER), 5L);
    }
}
