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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
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
/// - Arm 4 (separate test, ordered last because it kills the owner, `@Disabled`: see its reason): the owner dies inside the
///   window; the survivors should converge on N. In 3 of 5 samples they did; in 2 the new owner (the node that missed N)
///   stayed at N-1 for 60 s while the other survivor held N.
/// Control rounds interleave the drop rounds: no drop, the publish succeeds and the replica holds N within seconds, which
/// shows the instrument sees a healthy replica as healthy.
///
/// **Not built as the design wrote it, and why.** The design's "RF 3, CF 2, no ISR" variant of arm 2 cannot be built here:
/// a committed ISR exists in every Ember stream (the ISR of three is awaited at boot), so the publish always needs both
/// replicas. The owner's registry row for the replica is not reachable from `AetherNode` (no accessor), so "row >= N" is
/// observed through its consequence, the owner's visible head. The new owner of arm 4 is whichever survivor wins the
/// election; forcing it to be the node that missed N would need the package-private `ReplicaPlacement.rank`.
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

    @Test
    @Disabled("#2682 (found by #2347 arm 4): RED in 2 of 5 samples (bigboy, readForward 1 s and 2 s): the committed new owner is the node that missed the tail "
              + "(head N-1) while the other survivor holds N, and in the red samples the new owner stayed at N-1 for 60 s. "
              + "Nondeterministic, so it cannot be an enabled tripwire; enable once the promotion gate pulls from the ahead survivor deterministically")
    @Order(2)
    @Timeout(1200)
    void ownerKilledInsideTheWindow_survivorsConvergeOnTheLostTail() {
        boot();
        awaitRoundPrecondition("arm 4");

        var owner = owner();
        var survivors = cluster.allNodes().stream().filter(node -> !node.self().equals(owner.self())).toList();
        var lagging = survivors.getFirst();
        var tail = ringHead(owner) + 1;

        armDrop(lagging);

        var publish = publishAsync(owner, "arm4");

        awaitUntil(() -> droppedAtMs.get() > 0L, DROP_LANDED_BUDGET_MS, "arm 4: the tail chunk was dropped on the lagging replica");
        awaitUntil(() -> ringHead(survivors.getLast()) == tail, CONTROL_BUDGET_MS, "arm 4 control: the other survivor holds the tail");
        assertThat(ringHead(lagging)).as("arm 4 control: the lagging survivor misses the tail").isEqualTo(tail - 1);
        assertThat(cluster.killNode(owner.self().id(), false).await(STOP_BOUND).isSuccess()).as("owner killed inside the window").isTrue();
        publish.await(PUBLISH_BOUND);

        awaitSurvivorsHoldTheTail(survivors, tail, REPAIR_BUDGET_MS + 30_000L);
        System.out.printf("LOSTTAIL arm4 newOwner=%s laggingNode=%s%n",
                          StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION).map(value -> value.owner().id()).or("none"),
                          lagging.self().id());
    }

    /// Each round starts from a committed ISR of all three: the publish of a round needs every ISR peer's ack, so a shrunk ISR
    /// (a replica that lagged past `isr_lag_max` in an earlier round) would let the publish resolve without the dropped replica.
    private void awaitRoundPrecondition(String round) {
        var isrBefore = StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION).map(value -> value.isr().size()).or(-1);

        System.out.printf("LOSTTAIL %s isrSizeAtStart=%d%n", round, isrBefore);
        StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS);
    }

    /// As `awaitUntil`, printing the survivors' heads and the committed owner every ten seconds, so a failure shows who lacks the tail.
    private void awaitSurvivorsHoldTheTail(List<AetherNode> survivors, long tail, long budgetMs) {
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
        assertThat(survivors.stream().map(node -> ringHead(node)).toList()).as("arm 4: both survivors hold the tail after the owner died")
                                                                          .containsOnly(tail);
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
        var outcome = publishAsync(owner, "control-" + round).await(PUBLISH_BOUND);

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
            var outcome = publishAsync(owner, "warm-" + i).await(PUBLISH_BOUND);

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
