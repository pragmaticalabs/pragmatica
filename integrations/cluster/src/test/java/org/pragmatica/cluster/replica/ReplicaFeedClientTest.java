// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cluster.replica.ClientFixtures.Net;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.replica.ClientFixtures.incarnation;
import static org.pragmatica.cluster.replica.FeedFixtures.Key;
import static org.pragmatica.cluster.replica.FeedFixtures.TAG;
import static org.pragmatica.cluster.replica.FeedFixtures.put;

/// The worker side of the replica feed against the real [ReplicaFeedServer] on two cores, over a manual network and clock. NT-O21..O26, O28,
/// O32, O33, O38 name the groups; each test names the mutation of the client that turns it red.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ReplicaFeedClientTest {
    private static Net joined() {
        var net = new Net(2);

        net.genesis();
        net.commitAll(1, put("a", "1"));
        net.commitAll(2, put("b", "2"));
        net.step();

        return net;
    }

    private static boolean lastPublished(Net net) {
        return net.published.getLast();
    }

    @Nested
    class Join {
        /// NT-O32. A fresh worker (empty KV) subscribes live-only, pulls the snapshot, installs it and becomes ready on the first heartbeat.
        /// Mutation: install without `replayNotifications`, or publish readiness before `caughtUp(true)`.
        @Test
        void join_installsTheSnapshot_andIsReadyOnTheFirstHeartbeat() {
            var net = joined();

            assertThat(net.valueOf("a")).isEqualTo("1");
            assertThat(net.valueOf("b")).isEqualTo("2");
            assertThat(net.subscribes()).singleElement().satisfies(subscribe -> {
                assertThat(subscribe.mode()).isEqualTo(SubscribeMode.LIVE_ONLY);
                assertThat(subscribe.tag()).as("a fresh replica makes no claim").isEqualTo(IncarnationTag.NONE);
            });
            assertThat(net.applier.position().tag()).isEqualTo(TAG);
            assertThat(net.applier.position().applied()).isEqualTo(3);
            assertThat(net.applier.position().anchor()).extracting("slot").containsExactly(1L, 2L);
            assertThat(net.installs.get()).isEqualTo(1);
            assertThat(lastPublished(net)).isTrue();
        }

        /// Subscribe first: entries committed while the snapshot is in flight are buffered and drained after the install: none lost, none repeated.
        /// Mutation: skip the buffer drain in `ReplicaApplier.drainBuffer`; or drain before the install.
        @Test
        void join_withWritesDuringTheTransfer_losesNoneAndRepeatsNone() {
            var net = new Net(1);

            net.genesis();
            net.commitAll(1, put("a", "1"));
            net.holdChunks.set(true);
            net.step();
            net.commitAll(2, put("c", "3"));
            net.commitAll(3, put("d", "4"));
            net.step();

            assertThat(net.valueOf("c")).as("still waiting for the snapshot").isEqualTo("<absent>");

            net.releaseChunks();
            net.pump();

            assertThat(net.valueOf("a")).isEqualTo("1");
            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.valueOf("d")).isEqualTo("4");
            assertThat(net.clientStore.committedRevision()).isEqualTo(4);
            assertThat(net.applier.position().anchor()).extracting("slot").containsExactly(1L, 2L, 3L);
        }

        /// The whole snapshot is hash-checked before it installs: a transfer whose bytes do not match the announced SHA-256 installs nothing.
        /// Mutation: drop the `hash.equals(transfer.hash)` filter in `ReplicaFeedClient.install`.
        @Test
        void aSnapshotWhoseHashDoesNotMatch_isNeverInstalled() {
            var net = new Net(1);

            net.genesis();
            net.commitAll(1, put("a", "1"));
            net.holdChunks.set(true);
            net.step();

            var real = net.heldChunks.removeFirst();
            var tampered = new ReplicaFeedMessage.ReplicaSnapshotChunk(real.sender(),
                                                                       real.requestId(),
                                                                       real.tag(),
                                                                       real.revision(),
                                                                       real.anchor(),
                                                                       "0".repeat(64),
                                                                       real.total(),
                                                                       real.offset(),
                                                                       real.bytes());

            net.client.onSnapshotChunk(tampered);
            net.pump();

            assertThat(net.installs.get()).isZero();
            assertThat(net.valueOf("a")).isEqualTo("<absent>");
            assertThat(net.applier.position().isHeld()).isFalse();
        }

        /// A restarted worker holds nothing: it takes the empty-KV path. Mutation: resume from a position the new process cannot hold.
        @Test
        void restartedWorker_takesTheEmptyKvPath() {
            var first = joined();
            var restarted = new Net(2);

            restarted.genesis();
            restarted.commitAll(1, put("a", "1"));
            restarted.step();

            assertThat(first.applier.position().isResumable()).isTrue();
            assertThat(restarted.subscribes()).singleElement().extracting(ReplicaFeedMessage.ReplicaSubscribe::mode).isEqualTo(SubscribeMode.LIVE_ONLY);
        }
    }

    @Nested
    class Refusals {
        /// G-1. A refusal is terminal for its request id: a grant acknowledgement, tagged entries and a chunk that follow it are discarded.
        /// Mutation: keep the session after a refusal (drop `session = null` in `end`), or match messages by sender only.
        @Test
        void aRefusal_isTerminalForItsRequestId_laterMessagesAreDiscarded() {
            var net = new Net(1);

            net.genesis();
            net.commitAll(1, put("a", "1"));
            net.holdChunks.set(true);
            net.step();

            var id = net.subscribes().getFirst().requestId();
            var core = net.core(0).id;
            var late = new ReplicaEntry(TAG, 2, 2, "late", List.of(new KVCommand.Put<>(new Key("c"), "3")));

            net.client.onRefused(new ReplicaRefused(core, id, RefusalReason.EVICTED, 0, TAG));
            net.client.onEntries(new ReplicaEntries(core, id, 0, List.of(), 2));
            net.client.onEntries(new ReplicaEntries(core, id, 1, List.of(late), 3));
            net.releaseChunks();
            net.pump();

            assertThat(net.installs.get()).as("the held chunk of the refused request installs nothing").isZero();
            assertThat(net.valueOf("c")).isEqualTo("<absent>");
            assertThat(net.valueOf("a")).isEqualTo("<absent>");
            assertThat(net.client.counters().get("replica_dropped_total")).isGreaterThanOrEqualTo(3L);
        }

        /// NT-O24. A re-genesis closes the subscription with INCARNATION_MISMATCH: the worker discards its position and snapshots under the new tag.
        /// Mutation: keep the position on INCARNATION_MISMATCH (resume FROM_ANCHOR), or compare tags by number only.
        @Test
        void incarnationChange_discardsThePosition_andSnapshotsUnderTheNewTag() {
            var net = joined();

            net.commitAll(3, incarnation(2, "B"), put("z", "9"));
            net.step();
            net.advance(300);
            net.step();

            assertThat(net.subscribes()).hasSize(2);
            assertThat(net.subscribes().getLast().mode()).isEqualTo(SubscribeMode.LIVE_ONLY);
            assertThat(net.applier.position().tag()).isEqualTo(IncarnationTag.incarnationTag(2, "B"));
            assertThat(net.valueOf("z")).isEqualTo("9");
            assertThat(net.installs.get()).isEqualTo(2);
        }

        /// BUSY / NOT_SERVING: the other uplink first; when both refuse, wait the full-jitter backoff before asking again.
        /// Mutation: retry both uplinks at once (no `everyUplinkAvoided` wait), or ignore `consecutiveRefusals` in the delay.
        @Test
        void bothCoresTurnTheWorkerAway_otherUplinkFirst_thenTheBackoffWait() {
            var net = new Net(2);

            net.genesis();
            net.core(0).serving.set(false);
            net.core(1).serving.set(false);
            net.step();
            net.step();
            net.step();
            net.step();

            assertThat(net.subscribeTargets()).as("each uplink tried once, then the wait").containsExactly(net.core(0).id, net.core(1).id);

            var delay = BusyBackoff.delayMillis(ClientFixtures.CLIENT.id(), 2);

            net.advance(delay - 1);
            net.step();

            assertThat(net.subscribes()).hasSize(2);

            net.advance(1);
            net.step();

            assertThat(net.subscribes()).hasSize(3);
        }
    }

    @Nested
    class Failover {
        /// NT-O21, NT-O23. The source goes silent: after 3 s the selector is asked again, the worker resubscribes to the other core FROM_ANCHOR
        /// and needs no second snapshot. Mutation: drop the silence check, or resubscribe LIVE_ONLY.
        @Test
        void silentSource_resubscribesToTheOtherCoreFromTheAnchor() {
            var net = joined();

            net.core(0).up.set(false);
            net.commitAll(3, put("c", "3"));
            net.advance(3_500);
            net.step();
            net.advance(300);
            net.step();

            assertThat(net.subscribeTargets()).containsExactly(net.core(0).id, net.core(1).id);
            assertThat(net.subscribes().getLast().mode()).isEqualTo(SubscribeMode.FROM_ANCHOR);
            assertThat(net.installs.get()).as("no second snapshot").isEqualTo(1);
            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.client.counters().get("replica_silence_switch_total")).isEqualTo(1L);
        }

        /// NT-O22. The core dies mid-snapshot: the partial transfer is discarded and the worker restarts at step 1 on the other core.
        /// Mutation: keep the partial `Transfer` across sessions.
        @Test
        void coreDiesMidSnapshot_restartsAtStepOneOnTheOtherCore() {
            var net = new Net(2, ReplicaClientLimits.DEFAULT, new ReplicaFeedLimits(4, 2, 5_000, 2, 8 << 20, 512, 30_000, 256, 256));

            net.genesis();
            net.commitAll(1, put("a", "1"));
            net.holdChunks.set(true);
            net.client.tick();
            net.pump();
            net.client.onSnapshotChunk(net.heldChunks.removeFirst());
            net.pump();
            net.core(0).up.set(false);
            net.heldChunks.clear();
            net.holdChunks.set(false);
            net.advance(3_500);
            net.step();
            net.advance(300);
            net.step();

            assertThat(net.subscribeTargets()).containsExactly(net.core(0).id, net.core(1).id);
            assertThat(net.subscribes().getLast().mode()).isEqualTo(SubscribeMode.LIVE_ONLY);
            assertThat(net.installs.get()).isEqualTo(1);
            assertThat(net.valueOf("a")).isEqualTo("1");
        }

        /// NT-O38. A snapshot whose source had no effect entry yet is unanchored; an unanchored position is never resumable, so a disconnect
        /// costs a snapshot. Mutation: `ReplicaPosition.isResumable` ignores the anchor.
        @Test
        void unanchoredPosition_resnapshotsOnDisconnect() {
            var net = new Net(2);

            net.genesis();
            net.step();

            assertThat(net.applier.position().isHeld()).isTrue();
            assertThat(net.applier.position().anchor()).isEmpty();

            net.core(0).up.set(false);
            net.advance(3_500);
            net.step();
            net.advance(300);
            net.step();

            assertThat(net.subscribes().getLast().mode()).isEqualTo(SubscribeMode.LIVE_ONLY);
            assertThat(net.installs.get()).isEqualTo(2);
        }

        /// NT-O26. Cut off from every source: the replica keeps its last state, readiness drops after 30 s (stale), and on heal it resumes FROM_ANCHOR.
        /// Mutation: clear the replica on silence; or leave readiness latched (today's `authorizePassiveClient`).
        @Test
        void cutOffFromAllSources_freezes_notReadyAfter30s_thenResumesFromAnchor() {
            var net = joined();

            assertThat(lastPublished(net)).isTrue();
            net.core(0).up.set(false);
            net.core(1).up.set(false);
            net.advance(31_000);
            net.step();

            assertThat(lastPublished(net)).as("stale after 30 s").isFalse();
            assertThat(net.valueOf("a")).as("the replica keeps its last state").isEqualTo("1");

            net.core(0).up.set(true);
            net.core(1).up.set(true);
            net.commitAll(3, put("c", "3"));
            net.advance(1_000);
            net.step();
            net.step();

            assertThat(net.subscribes().getLast().mode()).isEqualTo(SubscribeMode.FROM_ANCHOR);
            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.installs.get()).isEqualTo(1);
            assertThat(lastPublished(net)).isTrue();
        }

        /// NT-O28. A source that stops serving says so in its heartbeat: the worker never counts it toward freshness and moves to the other core.
        /// The `serving = false` heartbeat arrives 20 s after the last counted one; 11 s later the counted one is 31 s old, so the replica is not
        /// ready (counting the refused heartbeat would make it 11 s old and ready).
        /// Mutation: count a `serving=false` heartbeat, or do not avoid the source.
        @Test
        void aSourceThatStopsServing_isNeverCounted_andIsLeft() {
            var net = joined();

            net.advance(20_000);
            net.core(0).serving.set(false);
            net.step();
            net.advance(11_000);

            assertThat(net.readiness.ready()).isFalse();

            net.advance(300);
            net.step();

            assertThat(net.subscribeTargets()).containsExactly(net.core(0).id, net.core(1).id);
        }
    }

    @Nested
    class Bounds {
        /// NT-O25. A snapshot too slow for the receiver buffer: the subscription is abandoned (overflow counted); three in a row raise the loop
        /// signal and back off 30 s. Mutation: drop the buffer bound, or the three-in-a-row back-off.
        @Test
        void bufferOverflow_abandons_andThreeInARow_backOff() {
            var net = new Net(1, new ReplicaClientLimits(30_000, 5_000, 3_000, 400, 250), ReplicaFeedLimits.DEFAULT);

            net.genesis();
            net.holdChunks.set(true);
            for (var round = 0; round < 3; round++) {
                net.advance(300);
                net.step();
                net.commitAll(1 + round, put("k" + round, "v"));
                net.step();
            }

            assertThat(net.client.counters().get("replica_buffer_overflow_total")).isEqualTo(3L);
            assertThat(net.client.counters().get("replica_snapshot_loop_total")).isEqualTo(1L);

            var attempts = net.subscribes().size();

            net.advance(10_000);
            net.step();

            assertThat(net.subscribes()).as("backing off").hasSize(attempts);

            net.advance(21_000);
            net.step();

            assertThat(net.subscribes()).hasSize(attempts + 1);
        }
    }

    @Nested
    class Listeners {
        /// NT-O33. A listener that throws while an entry applies must not stop the replica: storage is written before notifications dispatch.
        /// Mutation: apply entries after dispatching (or let the exception end the session).
        @Test
        void aListenerThatThrows_doesNotStopTheReplica() {
            var net = joined();

            net.clientRouter.addRoute(ValuePut.class, (java.util.function.Consumer<ValuePut<?, ?>>) _ -> {
                throw new IllegalStateException("listener failure");
            });
            net.commitAll(3, put("c", "3"));
            net.step();
            net.commitAll(4, put("d", "4"));
            net.step();

            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.valueOf("d")).isEqualTo("4");
            assertThat(net.applier.position().applied()).isEqualTo(5);
        }
    }
}
