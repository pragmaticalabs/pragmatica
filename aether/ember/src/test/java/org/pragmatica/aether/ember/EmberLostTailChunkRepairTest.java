// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// #2347 pin: a `ReplicateEvents` chunk lost at the TAIL of a run (nothing follows it, so the receive-path gap detector never
/// fires, and the commit announcements do not compare heads) is repaired by the #333/#445 interval re-verify in
/// `PartitionBackfill`, not by the live path. Without this test nothing would notice a refactor of `staleCaughtUpNonOwner`
/// removing the only repair. The unit-level pin is `PartitionBackfillTest.LostTail`.
///
/// Three nodes, the cluster-default stream (RF 3, CF 2, committed ISR of all three). The inbound fault filter of one replica
/// drops exactly one `ReplicateEvents` (the next one), after which nothing more is published.
/// - Arm 1 (control): the owner holds the new offset N, the replica N-1, so the drop landed and nothing repairs instantly.
/// - Arm 2: the replica's head reaches N by itself, within `2 x bound + 10 s` (bound = `backfillSourceWaitBound`).
/// - Arm 3: with the committed ISR the publish needs the dropped replica's ack, so it is still pending while the replica lags,
///   and the owner's visible head reaches N after the repair (the completion ack). The publish outcome itself (5 s ack timer
///   against the repair) is reported, not asserted.
/// - Arm 4 (separate test, ordered last because it kills the owner): the owner dies inside the window, and the drop is armed on
///   the survivor the failover will elect, so the new owner is always the node that missed N (#2682). T1: with no publish and
///   no owner read after the kill, both survivors reach N (the new owner's promotion gate starts eagerly on the committed record
///   and pulls N from the other survivor; before #2682 nothing started it and the new owner stayed at N-1). T2: the next publish
///   through the new owner lands at N+1, never N, and the other survivor's record at N is still the one published before the kill.
/// Control rounds interleave the drop rounds: no drop, the publish succeeds and the replica holds N within seconds, which
/// shows the instrument sees a healthy replica as healthy.
///
/// **Not built as the design wrote it, and why.** The design's "RF 3, CF 2, no ISR" variant of arm 2 cannot be built here:
/// a committed ISR exists in every Ember stream (the ISR of three is awaited at boot), so the publish always needs both
/// replicas. The owner's registry row for the replica is not reachable from `AetherNode` (no accessor), so "row >= N" is
/// observed through its consequence, the owner's visible head. Arm 4 picks the lagging survivor with the public
/// `ReplicaPlacement.place` over the survivors, the call the leader's writer makes over its live members, and asserts after the
/// kill that the committed owner is that node, so a change of the election fails loudly instead of silently testing the other case.
///
/// `read_forward_timeout` is lowered (default 2 s, here [#READ_FORWARD_SECONDS]) so the re-verify bound is 10 x that.
@PortBudget
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmberLostTailChunkRepairTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PUBLISH_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long FAILOVER_BUDGET_MS = 90_000L;
    private static final int READ_FORWARD_SECONDS = Integer.getInteger("lostTail.readForwardSeconds", 1);
    private static final long BOUND_MS = 10_000L * READ_FORWARD_SECONDS;
    private static final long REPAIR_BUDGET_MS = 2 * BOUND_MS + 10_000L;
    private static final long CONTROL_BUDGET_MS = 2_000L;
    private static final long DROP_LANDED_BUDGET_MS = 10_000L;
    private static final int ROUND_PAIRS = 3;
    private static final int WARM_UP = 3;
    private static final String STREAM = "lt2347";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private final AtomicReference<String> dropOn = new AtomicReference<>("");
    private final AtomicLong droppedAtMs = new AtomicLong(0L);

    private EmberCluster cluster;
    private Throwable bootFailure;

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Order(1)
    @Timeout(1200)
    void lostTailChunk_isRepairedByTheIntervalReverify_andTheOwnerSeesItAfterwards() {
        boot();

        var repairMs = new ArrayList<Long>();
        var controlMs = new ArrayList<Long>();

        for (var pair = 0; pair < ROUND_PAIRS; pair++) {
            repairMs.add(dropRound(pair));
            controlMs.add(controlRound(pair));
        }
        System.out.printf("LOSTTAIL readForwardSeconds=%d boundMs=%d dropRounds=%d repairMs=%s controlRounds=%d controlMs=%s%n",
                          READ_FORWARD_SECONDS, BOUND_MS, repairMs.size(), repairMs, controlMs.size(), controlMs);
    }

    /// T1 is measured before T2 and both are asserted together, so a run on code without #2682 reports T2 (the premise that a
    /// publish never reuses N) even though T1 is red there.
    @Test
    @Order(2)
    @Timeout(1200)
    void ownerKilledInsideTheWindow_survivorsConvergeOnTheLostTail_andTheNextPublishLandsAfterIt() {
        boot();
        awaitRoundPrecondition("arm 4");

        var owner = owner();
        var survivors = cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).toList();
        var lagging = electedAmong(survivors);
        var ahead = survivors.stream().filter(node -> !node.self().equals(lagging.self())).findFirst().orElseThrow();
        var tail = ringHead(owner) + 1;

        armDrop(lagging);

        var publish = publishAsync(owner, "arm4");

        awaitUntil(() -> droppedAtMs.get() > 0L, DROP_LANDED_BUDGET_MS, "arm 4: the tail chunk was dropped on the lagging replica");
        awaitUntil(() -> ringHead(ahead) == tail, CONTROL_BUDGET_MS, "arm 4 control: the other survivor holds the tail");
        assertThat(ringHead(lagging)).as("arm 4 control: the lagging survivor misses the tail").isEqualTo(tail - 1);
        assertThat(cluster.killNode(owner.self().id(), false).await(STOP_BOUND).isSuccess()).as("owner killed inside the window").isTrue();
        publish.await(PUBLISH_BOUND);
        awaitUntil(() -> survivors.stream().anyMatch(node -> node.self().id().equals(committedOwnerId())),
                   FAILOVER_BUDGET_MS,
                   "arm 4: the committed ownership record names a survivor");
        assertThat(committedOwnerId()).as("arm 4 precondition: the committed owner is the lagging survivor. HRW wiring changed; re-derive the lagging choice")
                                      .isEqualTo(lagging.self().id());

        // T1: no publish and no owner read until both survivors hold the tail, or the budget ends
        var t1StartedAt = System.currentTimeMillis();
        var heads = survivorHeadsAfter(survivors, tail, REPAIR_BUDGET_MS + 30_000L);
        var t1Ms = System.currentTimeMillis() - t1StartedAt;
        // T2: the next publish through the new owner
        var next = publishThroughTheNewOwner(lagging);
        var atTail = payloadAt(ahead, tail);

        System.out.printf("LOSTTAIL arm4 readForwardSeconds=%d laggingNode=%s aheadNode=%s tail=%d t1Heads=%s t1Ms=%d t2Offset=%s aheadAtTail=%s%n",
                          READ_FORWARD_SECONDS, lagging.self().id(), ahead.self().id(), tail, heads, t1Ms, next.fold(Cause::message, String::valueOf), atTail);
        var soft = new SoftAssertions();

        soft.assertThat(next.fold(Cause::message, String::valueOf)).as("T2: the next publish lands after the tail, never on it")
                                                                    .isEqualTo(String.valueOf(tail + 1));
        soft.assertThat(atTail).as("T2: the other survivor's record at the tail is the one published before the kill")
                               .isEqualTo("arm4-payload");
        soft.assertThat(heads).as("T1: both survivors hold the tail after the owner died, with no demand on the new owner")
                              .containsOnly(tail);
        soft.assertAll();
    }

    /// The survivor the leader's writer elects: `desiredOwner` is `ReplicaPlacement.place` over the live members, and the
    /// failover keeps it because it is a live ISR member.
    private AetherNode electedAmong(List<AetherNode> survivors) {
        var elected = ReplicaPlacement.place(ENGINE_KEY, PARTITION, survivors.stream().map(AetherNode::self).toList(), 3)
                                      .map(ReplicaPlacement.Placement::owner)
                                      .unwrap();

        return survivors.stream().filter(node -> node.self().equals(elected)).findFirst().orElseThrow();
    }

    private String committedOwnerId() {
        return StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION).map(value -> value.owner().id()).or("none");
    }

    /// Publishes once through the new owner's router, retrying while its promotion gate has not activated it yet.
    private Result<Long> publishThroughTheNewOwner(AetherNode newOwner) {
        return StreamTripwireSupport.publishOnceActivated(() -> publishAsync(newOwner, "arm4-next"));
    }

    /// The payload `node` holds at `offset`, read from its ring including the appended, not yet visible, suffix.
    private static String payloadAt(AetherNode node, long offset) {
        return node.streamPartitionManager()
                   .partitionBuffer(ENGINE_KEY, PARTITION)
                   .flatMap(ring -> ring.readAppended(offset, 1).option())
                   .flatMap(events -> Option.from(events.stream().filter(event -> event.offset() == offset).findFirst()))
                   .map(event -> new String(event.data(), StandardCharsets.UTF_8))
                   .or("<missing>");
    }

    /// Each round starts from a committed ISR of all three: the publish of a round needs every ISR peer's ack, so a shrunk ISR
    /// (a replica that lagged past `isr_lag_max` in an earlier round) would let the publish resolve without the dropped replica.
    private void awaitRoundPrecondition(String round) {
        var isrBefore = StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION).map(value -> value.isr().size()).or(-1);

        System.out.printf("LOSTTAIL %s isrSizeAtStart=%d%n", round, isrBefore);
        StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS);
    }

    /// Waits until every survivor's head is `tail` or the budget ends, printing the heads and the committed owner every ten
    /// seconds, and returns the heads it ended on.
    private List<Long> survivorHeadsAfter(List<AetherNode> survivors, long tail, long budgetMs) {
        var startedAt = System.currentTimeMillis();
        var nextReport = 0L;

        while (System.currentTimeMillis() - startedAt < budgetMs && !survivors.stream().allMatch(node -> ringHead(node) == tail)) {
            if (System.currentTimeMillis() - startedAt >= nextReport) {
                System.out.printf("LOSTTAIL arm4 t=%ds tail=%d heads=%s committedOwner=%s%n",
                                  (System.currentTimeMillis() - startedAt) / 1000,
                                  tail,
                                  survivors.stream().map(node -> node.self().id() + "=" + ringHead(node)).toList(),
                                  StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION).map(value -> value.owner().id()).or("none"));
                nextReport += 10_000L;
            }

            sleepQuietly(500L);
        }
        return survivors.stream().map(node -> ringHead(node)).toList();
    }

    private long dropRound(int round) {
        awaitRoundPrecondition("drop round " + round);

        var owner = owner();
        var replica = replicaOf(owner);
        var tail = ringHead(owner) + 1;

        armDrop(replica);

        var publish = publishAsync(owner, "drop-" + round);

        awaitUntil(() -> droppedAtMs.get() > 0L, DROP_LANDED_BUDGET_MS, "round " + round + ": the tail chunk was dropped");
        // arm 1: the drop landed, the owner holds the tail, the replica does not, and nothing repaired it at once
        sleepQuietly(500L);
        assertThat(ringHead(owner)).as("arm 1: owner holds the tail (round %d)", round).isEqualTo(tail);
        assertThat(ringHead(replica)).as("arm 1: replica misses the tail (round %d)", round).isEqualTo(tail - 1);
        var earlyOutcome = publish.isResolved() ? publish.await(PUBLISH_BOUND).fold(Cause::message, id -> "published " + id) : "pending";

        assertThat(earlyOutcome).as("arm 3: the committed ISR needs the dropped replica's ack, so the publish is still pending (round %d)", round).isEqualTo("pending");

        // arm 2: the replica repairs itself
        awaitUntil(() -> ringHead(replica) == tail, REPAIR_BUDGET_MS, "arm 2 round " + round + ": the replica's head reaches the lost tail by itself");

        var repaired = System.currentTimeMillis() - droppedAtMs.get();

        // arm 3: the owner sees the tail once the repair's completion ack arrives. The publish outcome is a race between the 5 s ack
        // timer and the repair (a timeout failure, or a late success), so it is reported, not asserted.
        var outcome = publish.await(PUBLISH_BOUND);

        awaitUntil(() -> visibleHead(owner) >= tail, REPAIR_BUDGET_MS, "arm 3 round " + round + ": the owner's visible head reaches the tail after the repair");
        System.out.printf("LOSTTAIL drop round %d: repairMs=%d publishOutcome=%s%n", round, repaired, outcome.fold(Cause::message, _ -> "ok"));

        return repaired;
    }

    private long controlRound(int round) {
        var owner = owner();
        var replica = replicaOf(owner);
        var tail = ringHead(owner) + 1;
        var startedAt = System.currentTimeMillis();
        var outcome = StreamTripwireSupport.publishOnceActivated(() -> publishAsync(owner, "control-" + round));

        assertThat(outcome.isSuccess()).as("control round %d: undisturbed publish succeeds: %s", round, outcome).isTrue();
        awaitUntil(() -> ringHead(replica) == tail, CONTROL_BUDGET_MS, "control round " + round + ": undisturbed replica holds the tail at once");

        return System.currentTimeMillis() - startedAt;
    }

    private Promise<Long> publishAsync(AetherNode owner, String label) {
        var router = StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class);

        return router.publish(ENGINE_KEY, PARTITION, (label + "-payload").getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
    }

    private void armDrop(AetherNode replica) {
        droppedAtMs.set(0L);
        dropOn.set(replica.self().id());
    }

    /// Drops exactly one `ReplicateEvents` of the stream on the armed node: the claim on `dropOn` is a compare-and-set, so a
    /// second chunk in flight passes.
    private boolean admitted(String nodeId, Object message) {
        if (message instanceof ReplicationMessage.ReplicateEvents replicate && ENGINE_KEY.equals(replicate.streamName()) && dropOn.compareAndSet(nodeId, "")) {
            droppedAtMs.set(System.currentTimeMillis());

            return false;
        }

        return true;
    }

    private AetherNode owner() {
        return StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION)
                                    .flatMap(value -> cluster.getNode(value.owner().id()))
                                    .unwrap();
    }

    private AetherNode replicaOf(AetherNode owner) {
        return cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).findFirst().orElseThrow();
    }

    private static long ringHead(AetherNode node) {
        return node.streamPartitionManager().partitionBuffer(ENGINE_KEY, PARTITION).map(ring -> ring.headOffset()).or(-1L);
    }

    private static long visibleHead(AetherNode node) {
        return node.streamPartitionManager().visibleBounds(ENGINE_KEY, PARTITION).map(bounds -> bounds.visibleHead()).or(-2L);
    }

    private void boot() {
        if (bootFailure != null) {
            throw new AssertionError("the shared cluster failed to boot for an earlier test: " + bootFailure, bootFailure);
        }

        if (cluster != null) {
            return;
        }

        try {
            bootCluster();
        } catch (Throwable failure) {
            bootFailure = failure;

            throw failure;
        }
    }

    private void bootCluster() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(), StreamTripwireSupport.smallRingStream(STREAM, 1, 100_000L, 3_600_000L));
        var record = StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS);

        warmUp(cluster.getNode(record.owner().id()).unwrap());
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, message) -> admitted(node.self().id(), message)));
    }

    /// A tail past offset 0, so the lost chunk is a tail and not a lost first event (a different path).
    private void warmUp(AetherNode owner) {
        for (var i = 0; i < WARM_UP; i++) {
            var outcome = StreamTripwireSupport.publishOnceActivated(() -> publishAsync(owner, "warm-" + i));

            assertThat(outcome.isSuccess()).as("warm-up publish %d: %s", i, outcome).isTrue();
        }
    }

    private EmberCluster threeNodes(int basePort) {
        var built = EmberCluster.emberCluster(StreamTripwireSupport.CLUSTER_SIZE,
                                              basePort,
                                              basePort + StreamTripwireSupport.MGMT_OFFSET,
                                              basePort + StreamTripwireSupport.APP_HTTP_OFFSET,
                                              "lt");
        var defaults = StreamingConfig.streamingConfig();

        built.withStreamingConfig(StreamingConfig.streamingConfig(defaults.publishForwardTimeout(),
                                                                  TimeSpan.timeSpan(READ_FORWARD_SECONDS).seconds(),
                                                                  defaults.maxReadResponseBytes(),
                                                                  defaults.readLinearization(),
                                                                  defaults.reshuffleConcurrency()));

        return built;
    }
}
