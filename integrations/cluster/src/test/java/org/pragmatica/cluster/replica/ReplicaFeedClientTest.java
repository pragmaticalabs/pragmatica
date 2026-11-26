// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.cluster.replica.ClientFixtures.Net;
import org.pragmatica.cluster.replica.ClientFixtures.Out;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.replica.ClientFixtures.incarnation;
import static org.pragmatica.cluster.replica.FeedFixtures.Key;
import static org.pragmatica.cluster.replica.FeedFixtures.LINEAGE;
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

    private static ReplicaFeedMessage.ReplicaSnapshotChunk withWrongHash(ReplicaFeedMessage.ReplicaSnapshotChunk real) {
        return new ReplicaFeedMessage.ReplicaSnapshotChunk(real.sender(),
                                                           real.requestId(),
                                                           real.tag(),
                                                           real.revision(),
                                                           real.anchor(),
                                                           "0".repeat(64),
                                                           real.total(),
                                                           real.offset(),
                                                           real.bytes());
    }

    private static void run(Net net, int rounds, long millis) {
        IntStream.range(0, rounds).forEach(_ -> advanceAndStep(net, millis));
    }

    private static void advanceAndStep(Net net, long millis) {
        net.advance(millis);
        net.step();
    }

    /// The worker follows 2/B at revision 5 from core-0 (which also holds `c`); core-1 is still at 1/A, revision 3.
    private static Net followingTwoB() {
        var net = joined();

        net.commitOnlyOn(0, 3, incarnation(2, "B"));
        net.commitOnlyOn(0, 4, put("c", "3"));
        run(net, 20, 500);
        assertThat(net.applier.position().tag()).as("setup: the worker follows 2/B").isEqualTo(IncarnationTag.incarnationTag(2, "B", LINEAGE));

        return net;
    }

    /// The worker applied slot 3 from core-0, which goes silent; core-1 lags, so it is asked and its same-incarnation snapshot is refused.
    private static Net laggingAfterSilence(Net net) {
        net.commitOnlyOn(0, 3, put("c", "3"));
        net.step();
        net.core(0).up.set(false);
        net.advance(3_500);
        net.step();
        net.advance(300);
        net.step();
        net.advance(300);
        net.step();

        return net;
    }

    /// Two cores whose snapshots span several 2-byte chunks and which serve `maxServes` snapshots at a time; genesis and slots 1, 2 committed.
    private static Net chunkedNet(int maxServes) {
        var defaults = ReplicaFeedLimits.DEFAULT;
        var net = new Net(2,
                          ReplicaClientLimits.DEFAULT,
                          new ReplicaFeedLimits(2,
                                                maxServes,
                                                defaults.cacheMillis(),
                                                defaults.cacheEntries(),
                                                defaults.queueBytes(),
                                                defaults.maxSubscribers(),
                                                defaults.transferIdleMillis(),
                                                defaults.drainMessagesPerTick(),
                                                defaults.catchUpWeight()));

        net.genesis();
        net.commitAll(1, put("a", "1"));
        net.commitAll(2, put("b", "2"));
        net.step();

        return net;
    }

    /// The worker holds revision 3 (`c`) of 1/A; both cores stay at revision 2 of the same incarnation.
    private static Net bothUplinksBehind() {
        var net = new Net(2);
        var ahead = new Net(1);

        ahead.genesis();
        ahead.commitAll(1, put("a", "1"));
        ahead.commitAll(2, put("c", "3"));
        net.genesis();
        net.commitAll(1, put("a", "1"));

        var image = ahead.core(0).store.captureReplicaSnapshot(ReplicaKeyFilter.includeAll()).unwrap();

        net.applier.install(image.bytes(), image.tag(), image.revision(), image.anchor()).unwrap();

        return net;
    }

    private static List<ReplicaSnapshotRequest> snapshotRequestsTo(Net net, ClientFixtures.Core core) {
        return net.outbox.stream()
                         .filter(out -> out.target().equals(core.id))
                         .map(Out::message)
                         .filter(ReplicaSnapshotRequest.class::isInstance)
                         .map(ReplicaSnapshotRequest.class::cast)
                         .toList();
    }

    private static long totalOfFirstChunkFrom(Net net, ClientFixtures.Core core) {
        return net.fromCores.stream()
                            .map(Out::message)
                            .filter(ReplicaSnapshotChunk.class::isInstance)
                            .map(ReplicaSnapshotChunk.class::cast)
                            .filter(chunk -> chunk.sender().equals(core.id))
                            .findFirst()
                            .map(ReplicaSnapshotChunk::total)
                            .orElseThrow();
    }

    /// Asks `core` for a new snapshot as `peer` and returns the first thing it answered.
    private static ProtocolMessage firstAnswerTo(Net net, ClientFixtures.Core core, NodeId peer, long requestId) {
        var before = net.fromCores.size();

        core.server.onSnapshotRequest(new ReplicaSnapshotRequest(peer, requestId, IncarnationTag.NONE, 0L, "", 0L));

        return net.fromCores.get(before).message();
    }

    /// Sends `request` to `core` and returns the refusal reasons it answered (nothing else may come back).
    private static List<RefusalReason> answersTo(Net net, ClientFixtures.Core core, ReplicaSnapshotRequest request) {
        var before = net.fromCores.size();

        core.server.onSnapshotRequest(request);

        return net.fromCores.subList(before, net.fromCores.size()).stream().map(out -> ((ReplicaRefused) out.message()).reason()).toList();
    }

    private static ReplicaSnapshotRequest release(NodeId peer, long requestId, ReplicaSnapshotChunk first) {
        return releaseWithHash(peer, requestId, first, first.hash());
    }

    private static ReplicaSnapshotRequest releaseWithHash(NodeId peer, long requestId, ReplicaSnapshotChunk first, String hash) {
        return new ReplicaSnapshotRequest(peer, requestId, first.tag(), first.revision(), hash, first.total());
    }

    /// Runs `windows` windows of 10 s in 100 ms steps and returns the snapshot requests the worker sent in each.
    private static List<Long> requestsPerTenSeconds(Net net, int windows) {
        return IntStream.range(0, windows).mapToObj(_ -> requestsInTenSeconds(net)).toList();
    }

    private static long requestsInTenSeconds(Net net) {
        var before = net.snapshotRequests();

        run(net, 100, 100);

        return net.snapshotRequests() - before;
    }

    /// The gaps between successive subscribes longer than the 250 ms retry of one round (each is the 250 ms retry plus the backoff wait).
    private static List<Long> backoffWaitsMillis(Net net) {
        var times = IntStream.range(0, net.outbox.size())
                             .filter(index -> net.outbox.get(index).message() instanceof ReplicaFeedMessage.ReplicaSubscribe)
                             .mapToObj(net.sentAtNanos::get)
                             .toList();

        return IntStream.range(1, times.size())
                        .mapToObj(index -> (times.get(index) - times.get(index - 1)) / 1_000_000L)
                        .filter(gap -> gap > 400L)
                        .toList();
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
            var tampered = withWrongHash(real);

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
            assertThat(net.applier.position().tag()).isEqualTo(IncarnationTag.incarnationTag(2, "B", LINEAGE));
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
    class LaggingSource {
        /// #3359 (twin #2776). The worker applied slot 3 from core-0, which then goes silent; core-1 lags (its frontier is behind the worker's:
        /// `AHEAD`), so the worker discards its position and is offered a snapshot of the SAME incarnation below what it applied. It refuses it, keeps its
        /// state and revision, and leaves that source. Mutation: admit every snapshot in `startTransfer`, or invert the revision comparison of
        /// `ReplicaApplier.admits`.
        @Test
        void aLaggingCoresSnapshot_ofTheSameIncarnation_isRefused_andTheWorkerKeepsItsState() {
            var net = joined();

            net.commitOnlyOn(0, 3, put("c", "3"));
            net.step();
            net.core(0).up.set(false);
            net.advance(3_500);
            net.step();
            net.advance(300);
            net.step();
            net.advance(300);
            net.step();

            assertThat(net.subscribeTargets()).as("the lagging core was asked").contains(net.core(1).id);
            assertThat(net.valueOf("c")).as("the newer state stays").isEqualTo("3");
            assertThat(net.clientStore.committedRevision()).as("the revision never moves down").isEqualTo(4);
            assertThat(net.installs.get()).as("no regressing snapshot installed").isEqualTo(1);
        }

        /// The control and the way out: once the lagging core catches up, the worker's next snapshot (at or above what it applied) installs.
        /// Mutation: refuse every snapshot after a refusal, or never forget the avoided source.
        @Test
        void afterTheLaggingCoreCatchesUp_theWorkerInstalls() {
            var net = joined();

            net.commitOnlyOn(0, 3, put("c", "3"));
            net.step();
            net.core(0).up.set(false);
            net.advance(3_500);
            net.step();
            net.advance(300);
            net.step();
            net.commitOnlyOn(1, 3, put("c", "3"));
            net.advance(30_000);
            net.step();
            net.advance(30_000);
            net.step();
            net.advance(30_000);
            net.step();

            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.clientStore.committedRevision()).isEqualTo(4);
            assertThat(net.installs.get()).as("the snapshot at the caught-up revision installed").isGreaterThanOrEqualTo(2);
        }

        /// Only a lagging source's snapshot makes the worker leave it: a snapshot that fails its hash is abandoned and the same current source is asked
        /// again (unchanged by #3359). Mutation: avoid the source in `abandonTransfer` (on every failed transfer).
        @Test
        void aSnapshotThatFailsItsHash_doesNotMakeTheWorkerLeaveItsSource() {
            var net = new Net(2);

            net.genesis();
            net.commitAll(1, put("a", "1"));
            net.holdChunks.set(true);
            net.step();
            net.client.onSnapshotChunk(withWrongHash(net.heldChunks.removeFirst()));
            net.pump();
            net.holdChunks.set(false);
            net.advance(300);
            net.step();
            net.step();

            assertThat(net.subscribeTargets()).as("the same core is asked again").containsExactly(net.core(0).id, net.core(0).id);
        }
    }

    /// The way out through another source: the refused source is avoided, so a core that is caught up is asked next although the lagging one was
    /// the current source (the selector keeps the current source while it is not avoided). Mutation: drop `avoided.add` in `ReplicaFeedClient.refuseSnapshot`.
    @Nested
    class LeavingTheLaggingSource {
        @Test
        void afterTheRefusal_aCaughtUpCoreIsAsked_andItsSnapshotInstalls() {
            var net = joined();

            net.commitOnlyOn(0, 3, put("c", "3"));
            net.step();
            net.core(0).up.set(false);
            net.advance(3_500);
            net.step();
            net.advance(300);
            net.step();
            net.advance(300);
            net.step();
            net.core(0).up.set(true);
            IntStream.range(0, 6).forEach(round -> {
                net.advance(1_000);
                net.step();
            });

            assertThat(net.subscribeTargets().getLast()).as("the caught-up core was asked last").isEqualTo(net.core(0).id);
            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.clientStore.committedRevision()).isEqualTo(4);
            assertThat(net.installs.get()).as("its snapshot, at the applied revision, installed").isEqualTo(2);
        }
    }

    /// #3359, the order rule at the first chunk (`ReplicaApplier.admits`): within one lineage the content never moves down in `(incarnation, revision)`;
    /// between lineages, and between two ids of one number, there is no order.
    @Nested
    class OrderRule {
        /// Probe P1 of the #3683 review: the worker follows 2/B from core-0; core-0 goes silent and core-1 is still at 1/A of the same lineage, so its
        /// snapshot is older and is refused: the newer incarnation's key and revision stay, and the worker is not ready on it. Mutations: order by revision
        /// alone (the 1/A snapshot carries no lower revision rule of its own); invert the incarnation comparison; admit every snapshot.
        @Test
        void anOlderIncarnationOfTheSameLineage_isRefused_andTheWorkerKeepsItsState() {
            var net = followingTwoB();

            net.core(0).up.set(false);
            run(net, 20, 500);

            assertThat(net.valueOf("c")).as("the newer incarnation's key stays").isEqualTo("3");
            assertThat(net.clientStore.committedRevision()).as("the revision never moves down").isEqualTo(5);
            assertThat(net.installs.get()).as("no older snapshot installed").isEqualTo(2);
            assertThat(net.readiness.ready()).as("not ready while every source is behind").isFalse();
        }

        /// A fresh re-genesis starts another lineage at 1: the worker at 2/B installs 1/X of lineage M, although its number is LOWER. Mutations: order
        /// by number alone; ignore the lineage.
        @Test
        void aLowerIncarnationOfAnotherLineage_installs() {
            var net = joined();
            var reborn = IncarnationTag.incarnationTag(1, "X", "M");

            net.commitAll(3, incarnation(2, "B"));
            run(net, 10, 500);
            assertThat(net.applier.position().tag()).as("setup: the worker follows 2/B").isEqualTo(IncarnationTag.incarnationTag(2, "B", LINEAGE));

            net.commitAll(4, incarnation(1, "X", "M"));
            net.commitAll(5, put("z", "9"));
            run(net, 10, 500);

            assertThat(net.applier.position().tag()).isEqualTo(reborn);
            assertThat(net.valueOf("z")).isEqualTo("9");
        }

        /// One number with two ids is unordered: the worker at 2/B (revision 5) installs core-1's 2/C at revision 4. Mutation: compare the number
        /// instead of the whole tag before comparing revisions.
        @Test
        void theSameNumberWithAnotherId_installs_belowTheAppliedRevision() {
            var net = joined();

            net.commitOnlyOn(1, 3, incarnation(2, "C"));
            net.commitOnlyOn(0, 3, incarnation(2, "B"));
            net.commitOnlyOn(0, 4, put("c", "3"));
            run(net, 20, 500);
            assertThat(net.applier.position().tag()).as("setup: the worker follows 2/B").isEqualTo(IncarnationTag.incarnationTag(2, "B", LINEAGE));

            net.core(0).up.set(false);
            run(net, 20, 500);

            assertThat(net.applier.position().tag()).isEqualTo(IncarnationTag.incarnationTag(2, "C", LINEAGE));
            assertThat(net.clientStore.committedRevision()).isEqualTo(4);
            assertThat(net.valueOf("c")).isEqualTo("<absent>");
        }

        /// Probe control of the #3683 review: the same incarnation from the lagging core does not regress over a long run either.
        @Test
        void theSameIncarnationBelowTheApplied_staysRefused_overALongRun() {
            var net = joined();

            net.commitOnlyOn(0, 3, put("c", "3"));
            net.step();
            net.core(0).up.set(false);
            run(net, 20, 500);

            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.clientStore.committedRevision()).isEqualTo(4);
        }
    }

    /// #3359: a refusal happens on the snapshot's FIRST chunk, pulls no further byte, and gives the source's serve slot back at once.
    @Nested
    class RefusalAtTheFirstChunk {
        /// The worker asks for no chunk past the first: its only further request to the lagging core is the release at `offset == total`. Mutation:
        /// check the order after the last chunk (in `install`) instead of at the first.
        @Test
        void aMultiChunkSnapshot_isRefusedBeforeTheSecondChunk() {
            var net = laggingAfterSilence(chunkedNet(1));
            var requests = snapshotRequestsTo(net, net.core(1));
            var total = totalOfFirstChunkFrom(net, net.core(1));

            assertThat(total).as("setup: the snapshot spans several chunks").isGreaterThan(2L);
            assertThat(requests.stream().map(ReplicaSnapshotRequest::offset).toList()).as("the first request, then the release").containsExactly(0L, total);
            assertThat(net.valueOf("c")).isEqualTo("3");
        }

        /// With one serve slot, the release frees it at once: another transfer is served right after the refusal, not turned away `BUSY` until the
        /// idle timeout. Mutations: do not send the release; do not free the slot on it.
        @Test
        void afterTheRefusal_theServeSlotIsFreeAtOnce() {
            var net = laggingAfterSilence(chunkedNet(1));
            var core = net.core(1);

            assertThat(core.server.counters().get("replica_transfers")).as("no transfer held").isEqualTo(0L);
            assertThat(firstAnswerTo(net, core, FeedFixtures.OTHER, 7)).as("a second transfer is served").isInstanceOf(ReplicaSnapshotChunk.class);
        }

        /// A release names its transfer by sender and request id and repeats its image: a release of another request id, of another sender, or with
        /// another hash answers `EVICTED` and frees nothing; only the transfer's own release frees the slot, and it answers nothing. Mutations: free
        /// without the same-image check; do not free on a release.
        @Test
        void aReleaseFreesOnlyItsOwnTransfer() {
            var net = chunkedNet(1);
            var core = net.core(1);
            var first = (ReplicaSnapshotChunk) firstAnswerTo(net, core, FeedFixtures.OTHER, 1);

            assertThat(answersTo(net, core, release(FeedFixtures.OTHER, 2, first))).as("another request id").containsExactly(RefusalReason.EVICTED);
            assertThat(answersTo(net, core, release(FeedFixtures.THIRD, 1, first))).as("another sender").containsExactly(RefusalReason.EVICTED);
            assertThat(answersTo(net, core, releaseWithHash(FeedFixtures.OTHER, 1, first, "0".repeat(64)))).as("another image")
                    .containsExactly(RefusalReason.EVICTED);
            assertThat(firstAnswerTo(net, core, FeedFixtures.THIRD, 9)).as("the slot is still held").isInstanceOf(ReplicaRefused.class);
            assertThat(answersTo(net, core, release(FeedFixtures.OTHER, 1, first))).as("its own release answers nothing").isEmpty();
            assertThat(firstAnswerTo(net, core, FeedFixtures.THIRD, 10)).as("and frees the slot").isInstanceOf(ReplicaSnapshotChunk.class);
        }

        /// A single-chunk snapshot sends nothing after the refusal: the source freed its slot with that chunk and would answer `EVICTED`. Mutation:
        /// send the release also when the first chunk was the last.
        @Test
        void aSingleChunkRefusal_sendsNothingExtra() {
            var net = laggingAfterSilence(joined());

            assertThat(snapshotRequestsTo(net, net.core(1)).stream().map(ReplicaSnapshotRequest::offset).distinct().toList()).containsExactly(0L);
            assertThat(net.fromCores.stream().map(Out::message).filter(ReplicaRefused.class::isInstance).map(ReplicaRefused.class::cast)
                          .map(ReplicaRefused::reason)).as("no EVICTED back").doesNotContain(RefusalReason.EVICTED);
        }
    }

    /// #3359 (probes P2 and P2b of the #3683 review): while every uplink stays behind, each refusal counts, so the waits grow to the 15 s cap with a
    /// jitter that varies per attempt; the worker keeps its newer state, is not ready, and becomes ready once a source catches up.
    @Nested
    class EveryUplinkBehind {
        /// Both uplinks answer and both lag (a lost tail of the same incarnation). Mutations: do not count the refusal; ignore the attempt in the
        /// backoff seed.
        @Test
        void bothUplinksBehind_theWaitsGrow_withAJitterPerAttempt_andTheWorkerIsReadyOnceTheyCatchUp() {
            var net = bothUplinksBehind();
            var counts = requestsPerTenSeconds(net, 9);
            var waits = backoffWaitsMillis(net);

            System.out.println("#3359 both behind: snapshot requests per 10 s " + counts + ", waits " + waits);
            assertThat(waits.getFirst()).as("the first wait draws from [0, 2 s]").isLessThanOrEqualTo(2_300L);
            assertThat(waits.stream().mapToLong(Long::longValue).max().orElse(0L)).as("later waits grow past it").isGreaterThan(2_300L);
            assertThat(waits.subList(2, waits.size()).stream().distinct().count()).as("waits at the cap differ").isGreaterThan(1L);
            assertThat(counts.getLast()).as("far below the constant-rate loop").isLessThan(counts.getFirst());
            assertThat(net.valueOf("c")).as("the newer state stays").isEqualTo("3");
            assertThat(net.readiness.ready()).as("not ready while refused").isFalse();

            net.commitAll(2, put("d", "4"));
            net.commitAll(3, put("e", "5"));
            run(net, 60, 500);

            assertThat(net.clientStore.committedRevision()).isEqualTo(4);
            assertThat(net.valueOf("d")).isEqualTo("4");
            assertThat(net.readiness.ready()).as("ready once a source caught up").isTrue();
        }

        /// One uplink silent, the other behind: only the lagging one refuses, once per round, and the waits still grow. Mutation: do not count the
        /// refusal.
        @Test
        void oneUplinkSilent_theOtherBehind_theWaitsGrow() {
            var net = joined();

            net.commitOnlyOn(0, 3, put("c", "3"));
            net.step();
            net.core(0).up.set(false);

            var counts = requestsPerTenSeconds(net, 9);

            System.out.println("#3359 one silent: snapshot requests per 10 s " + counts);
            assertThat(counts.getLast()).isLessThan(counts.getFirst());
            assertThat(counts.subList(3, counts.size()).stream().mapToLong(Long::longValue).sum()).as("at most one attempt per capped round")
                    .isLessThanOrEqualTo(counts.size() - 3L);
            assertThat(net.valueOf("c")).isEqualTo("3");
            assertThat(net.readiness.ready()).isFalse();
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
