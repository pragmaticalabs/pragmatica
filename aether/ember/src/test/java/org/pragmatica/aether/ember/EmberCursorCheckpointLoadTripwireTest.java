// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.ConsumerFence;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), consensus load: C-07 - each active (partition, group) costs about one
/// consensus slot per second for its cursor checkpoints.
///
/// The measure is the consensus log itself: `KVStore.committedRevision()` is the last applied Rabia slot, so its rate over
/// a window is the slot rate of the whole control plane. A stream of 4 partitions receives about 4 events per second per
/// partition. Over a baseline window with no consumers the slot rate is measured; then 2 groups x 4 partitions = 8
/// (partition, group) pairs are attached on one node through the node's real `StreamConsumerRuntime` - fenced under
/// committed `ConsumerAssignmentValue` records naming that node, exactly the admission the declarative
/// `StreamConsumerManager` gives - and the rate is measured again. The extra slots per pair per second is the finding's
/// number. (An unfenced subscription never publishes its cursor to consensus, so the fence is what makes the load real.)
///
/// - Tripwire: at least 0.5 extra slots per pair per second (the finding's prediction is about 1).
/// - `@Disabled` acceptance: at most 0.25 (checkpoints coalesced into a per-node tick, so load stops scaling with pairs).
///
/// Controls in the tripwire: all 8 pairs deliver events, and all 8 cursor checkpoints are committed in the KV.
@PortBudget
class EmberCursorCheckpointLoadTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final int PARTITIONS = 4;
    private static final List<String> GROUPS = List.of("c07-a", "c07-b");
    private static final long BASELINE_WINDOW_MS = 15_000L;
    private static final long ACTIVE_WINDOW_MS = 20_000L;
    private static final long WARMUP_MS = 5_000L;
    private static final long PUBLISH_GAP_MS = 60L;
    private static final String STREAM = "c07";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private EmberCluster cluster;
    private Path dataDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    private record Measurement(double baselineSlotsPerSecond, double activeSlotsPerSecond, int pairs, long delivered, int committedCheckpoints) {
        double extraSlotsPerPairPerSecond() {
            return (activeSlotsPerSecond - baselineSlotsPerSecond) / pairs;
        }
    }

    @Test
    @Timeout(600)
    void activeConsumerPairs_tripwire_costAboutOneConsensusSlotPerSecondEach() {
        var measured = measure();

        assertThat(measured.delivered()).as("control: the attached pairs delivered events").isPositive();
        assertThat(measured.committedCheckpoints()).as("control: every pair's cursor checkpoint is committed in the KV").isEqualTo(measured.pairs());
        assertThat(measured.extraSlotsPerPairPerSecond()).as("C-07 fixed? Cursor checkpoints are now coalesced: extra consensus slots per pair per second = %.2f "
                                                            + "(baseline %.1f/s, with %d pairs %.1f/s). Delete this tripwire and enable "
                                                            + "activeConsumerPairs_shareConsensusSlots (#2201)",
                                                            measured.extraSlotsPerPairPerSecond(),
                                                            measured.baselineSlotsPerSecond(),
                                                            measured.pairs(),
                                                            measured.activeSlotsPerSecond())
                                                         .isGreaterThanOrEqualTo(0.5);
    }

    @Test
    @Disabled("C-07 (#2201): enable when cursor checkpoints are coalesced; RED today: about one consensus slot per second per (partition, group)")
    @Timeout(600)
    void activeConsumerPairs_shareConsensusSlots() {
        var measured = measure();

        assertThat(measured.extraSlotsPerPairPerSecond()).as("extra consensus slots per (partition, group) pair per second (baseline %.1f/s, with %d pairs %.1f/s)",
                                                            measured.baselineSlotsPerSecond(),
                                                            measured.pairs(),
                                                            measured.activeSlotsPerSecond())
                                                         .isLessThanOrEqualTo(0.25);
    }

    private Measurement measure() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        assertThat(cluster.allNodes().getFirst()
                          .streamPartitionManager()
                          .createStream(StreamTripwireSupport.smallRingStream(STREAM, PARTITIONS, 100_000L, 3_600_000L))
                          .isSuccess()).as("stream created").isTrue();
        IntStream.range(0, PARTITIONS).forEach(partition -> awaitPartitionReady(partition));

        var node = cluster.allNodes().getFirst();
        var running = new AtomicBoolean(true);
        var publisher = Thread.ofPlatform().name("c07-publisher").start(() -> publishSteadily(node, running));

        try {
            var baseline = slotsPerSecond(node, BASELINE_WINDOW_MS);
            var delivered = new AtomicLong();
            var runtime = StreamTripwireSupport.component(node, "streamConsumerRuntime", StreamConsumerRuntime.class);

            attachAllPairs(node, runtime, delivered);
            sleepQuietly(WARMUP_MS);

            var active = slotsPerSecond(node, ACTIVE_WINDOW_MS);

            return new Measurement(baseline, active, GROUPS.size() * PARTITIONS, delivered.get(), committedCheckpoints(node));
        } finally {
            running.set(false);
            StreamTripwireSupport.joinQuietly(publisher);
        }
    }

    private void awaitPartitionReady(int partition) {
        awaitUntil(() -> StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, partition).filter(value -> value.isrVersion() > 0 && value.isr().size() == 3).isPresent(),
                   ISR_BUDGET_MS,
                   "partition " + partition + " has a committed ISR of three");
    }

    private void publishSteadily(AetherNode node, AtomicBoolean running) {
        var sequence = 0L;

        while (running.get()) {
            var partition = (int) (sequence++ % PARTITIONS);

            StreamTripwireSupport.post(node.managementPort(),
                                       "/api/v1/streams/" + StreamTripwireSupport.NAMESPACE + "/" + STREAM + "/" + StreamTripwireSupport.VERSION + "/publish",
                                       "{\"data\":\"e" + sequence + "\",\"partition\":" + partition + "}");
            sleepQuietly(PUBLISH_GAP_MS);
        }
    }

    /// Slots the node's consensus log advanced per second over `windowMs`.
    private static double slotsPerSecond(AetherNode node, long windowMs) {
        var before = node.kvStore().committedRevision();
        var started = System.nanoTime();

        sleepQuietly(windowMs);

        var slots = node.kvStore().committedRevision() - before;

        return slots / (StreamTripwireSupport.elapsedMs(started) / 1000.0);
    }

    private void attachAllPairs(AetherNode node, StreamConsumerRuntime runtime, AtomicLong delivered) {
        var clock = StreamTripwireSupport.component(node, "hlcClock", HlcClock.class);

        for (var group : GROUPS) {
            for (var partition = 0; partition < PARTITIONS; partition++) {
                var epoch = node.currentGenerationEpoch().withCounter(1L);
                var key = AetherKey.ConsumerAssignmentKey.consumerAssignmentKey(ENGINE_KEY, partition, group);
                var record = ConsumerAssignmentValue.consumerAssignmentValue(node.self(), epoch, 1L, clock.now());
                var written = node.<Object> apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, record))).await(TimeSpan.timeSpan(30).seconds());

                assertThat(written.isSuccess()).as("assignment of %s[%d] to %s committed: %s", group, partition, node.self().id(), written).isTrue();
                attach(runtime, group, partition, epoch, delivered);
            }
        }
    }

    private static void attach(StreamConsumerRuntime runtime, String group, int partition, Epoch epoch, AtomicLong delivered) {
        var fence = new ConsumerFence() {
            @Override
            public Epoch epoch() {
                return epoch;
            }

            @Override
            public boolean admitted() {
                return true;
            }
        };
        var result = runtime.subscribe(ENGINE_KEY,
                                       partition,
                                       ConsumerConfig.consumerConfig(group),
                                       (_, _, _) -> countDelivery(delivered),
                                       IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED,
                                       fence);

        assertThat(result.isSuccess()).as("subscribe %s[%d]: %s", group, partition, result).isTrue();
    }

    private static Promise<Unit> countDelivery(AtomicLong delivered) {
        delivered.incrementAndGet();

        return Promise.success(Unit.unit());
    }

    private int committedCheckpoints(AetherNode node) {
        return (int) GROUPS.stream()
                           .flatMap(group -> IntStream.range(0, PARTITIONS).mapToObj(partition -> AetherKey.StreamCursorCheckpointKey.streamCursorCheckpointKey(ENGINE_KEY, partition, group)))
                           .filter(key -> node.kvStore().getTyped(key, StreamCursorCheckpointValue.class).isPresent())
                           .count();
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("c07-tripwire");

        return StreamTripwireSupport.threeNodes(basePort, "c07", dataDir);
    }
}
