// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;

/// Streams overhaul tripwires, step 2 (#2201), consumer path: C-16/A-06 and B-10.
///
/// **C-16/A-06 - a consumer whose cursor falls behind the ring tail stalls forever, silently.** The node's real
/// `StreamConsumerRuntime` (the one `StreamConsumerManager` drives for declarative consumers: the node's routed
/// reader, cluster cursor store and operator-warning sink) is subscribed to a stream whose ring holds 20 events and
/// whose partition has published 60, so offsets 0..39 were evicted and sealed. The group's first cursor is 0, below
/// the ring tail, so the ring read answers `CursorExpired`; before PR-0a `ConsumerRuntimeState.pollFailed` handled only
/// `EpochDiverged`, and `afterFailedPass` logged at DEBUG and re-drove, forever. The default-budget form of the same defect (C-03's frozen rings
/// holding about 250 events) needs no different mechanism; a small `maxCount` reaches the same state in seconds.
///
/// - Fixed by PR-0a (#2201 C-16): the read below the ring tail is served from the sealed tier
///   (`StreamPartitionManager.readServingTiered`), so the group receives every event, and a `stream-consumer-behind-ring`
///   WARNING names the group. The tripwire that pinned the stall is deleted; the two acceptance assertions are enabled. A
///   positive control on a stream whose events are all inside the ring shows the same subscription path delivering, and
///   `tierFallbackSeam_isBoundOnEveryBootedNode` (NT-04) pins the node wiring the fix depends on.
///
/// **B-10 - the transactional (Postgres) cursor path has no production wiring.** The node's runtime reports no
/// `TransactionalCursorCommit`; the production factory passes `none()`. The `@Disabled` assertion is the "wire it"
/// outcome; if the ruling is "delete it", delete this class's B-10 pair with the Pg classes.
///
/// The cluster is booted once for the class (first test to run) and torn down in `@AfterAll`.
@PortBudget
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmberConsumerPathTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long RING_EVENTS = 20L;
    private static final int PUBLISHED = 60;
    private static final long EVICTED_THROUGH = PUBLISHED - RING_EVENTS - 1;
    private static final long SEAL_BUDGET_MS = 60_000L;
    private static final long ISR_BUDGET_MS = 90_000L;

    private EmberCluster cluster;
    private AetherNode owner;
    private Path dataDir;
    private Throwable bootFailure;
    private Option<StreamConsumerRuntime> runtime = Option.none();
    private final String stream = "c16";
    private final String engineKey = engineKey(stream);

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(dataDir);
    }

    private static boolean namesTheBehindGroup(String line) {
        return line.contains("c16-behind") || line.contains("c16-warned") || line.contains("CursorExpired");
    }

    @Test
    @Timeout(600)
    void consumerInsideTheRing_control_deliversEveryEvent() {
        var runtime = bootAndFillStream();
        var controlStream = "c16ctl";
        var controlKey = engineKey(controlStream);

        StreamTripwireSupport.createStream(owner, StreamTripwireSupport.smallRingStream(controlStream, 1, RING_EVENTS, 3_600_000L));
        StreamTripwireSupport.awaitFullIsr(cluster, controlKey, ISR_BUDGET_MS);
        for (var i = 0; i < 10; i++) {
            assertThat(StreamTripwireSupport.publishVia(cluster.getLeaderManagementPort().or(-1), controlStream, "ctl-" + i).acked()).isTrue();
        }

        var delivered = new ConcurrentSkipListSet<Long>();

        subscribe(runtime, "c16-control", controlKey, delivered);
        awaitUntil(() -> delivered.size() == 10, 30_000L, "the control group receives all 10 events inside the ring");
    }

    @Test
    @Timeout(600)
    void consumerBehindTheRing_deliversEverythingThroughTheSealedTier() {
        var runtime = bootAndFillStream();
        var delivered = new ConcurrentSkipListSet<Long>();

        subscribe(runtime, "c16-fallback", engineKey, delivered);
        awaitUntil(() -> delivered.size() >= PUBLISHED, 30_000L, "the group behind the ring receives all " + PUBLISHED + " events (0.." + (PUBLISHED - 1) + ") via the sealed tier");
        assertThat(delivered.first()).isZero();
        assertThat(delivered.last()).isEqualTo((long) (PUBLISHED - 1));
    }

    @Test
    @Timeout(600)
    void consumerBehindTheRing_raisesAnOperatorWarning() {
        var runtime = bootAndFillStream();
        var logs = new CopyOnWriteArrayList<String>();
        var detach = StreamTripwireSupport.captureLogs(Level.WARN, logs);

        try {
            subscribe(runtime, "c16-warned", engineKey, new ConcurrentSkipListSet<>());
            awaitUntil(() -> logs.stream().anyMatch(EmberConsumerPathTripwireTest::namesTheBehindGroup),
                       30_000L,
                       "a WARN-level event names the group whose cursor fell behind the ring");
        } finally {
            detach.run();
        }
    }

    /// NT-04 (#2201 C-16): every booted node binds the partition manager's sealed-tier seam. Unbound, a consumer behind the
    /// ring would expire at the ring tail and skip records the tier still holds; this fails if the node wiring drops it.
    @Test
    @Timeout(600)
    void tierFallbackSeam_isBoundOnEveryBootedNode() {
        bootAndFillStream();

        assertThat(cluster.allNodes().stream().filter(node -> !node.streamPartitionManager().tieredReaderBound()).toList())
            .as("nodes whose StreamPartitionManager has no sealed tier bound")
            .isEmpty();
    }

    @Test
    @Timeout(600)
    void transactionalCursorCommit_tripwire_isAbsentOnTheNodesRealRuntime() {
        var runtime = bootAndFillStream();

        assertThat(runtime.transactionalCursorCommit().isEmpty())
            .as("B-10 fixed? The node's consumer runtime now carries a TransactionalCursorCommit. Delete this tripwire, enable "
                + "transactionalCursorCommit_isWiredOnTheNodesRealRuntime (or delete the Pg classes if the ruling was 'delete') (#2201)")
            .isTrue();
    }

    @Test
    @Disabled("B-10 (#2201): enable if the ruling is 'wire it' (a Postgres cursor commit that also moves the resume offset); RED today: none()")
    @Timeout(600)
    void transactionalCursorCommit_isWiredOnTheNodesRealRuntime() {
        var runtime = bootAndFillStream();

        assertThat(runtime.transactionalCursorCommit().isPresent()).as("the node's consumer runtime exposes a TransactionalCursorCommit").isTrue();
    }

    private StreamConsumerRuntime bootAndFillStream() {
        if (bootFailure != null) {
            throw new AssertionError("the shared cluster failed to boot for an earlier test: " + bootFailure, bootFailure);
        }

        if (runtime.isPresent()) {
            return runtime.unwrap();
        }

        try {
            return bootCluster();
        } catch (Throwable failure) {
            bootFailure = failure;

            throw failure;
        }
    }

    private StreamConsumerRuntime bootCluster() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        owner = cluster.allNodes().getFirst();
        StreamTripwireSupport.createStream(owner, StreamTripwireSupport.smallRingStream(stream, 1, RING_EVENTS, 3_600_000L));

        var record = StreamTripwireSupport.awaitFullIsr(cluster, engineKey, ISR_BUDGET_MS);

        owner = cluster.getNode(record.owner().id()).unwrap();
        for (var i = 0; i < PUBLISHED; i++) {
            var response = StreamTripwireSupport.publishVia(cluster.getLeaderManagementPort().or(-1), stream, "event-" + i);

            assertThat(response.acked()).as("publish %d: %s", i, response.body()).isTrue();
        }
        awaitUntil(() -> sealedThrough() >= EVICTED_THROUGH, SEAL_BUDGET_MS, "the evicted offsets 0.." + EVICTED_THROUGH + " are sealed on the owner");
        runtime = Option.some(StreamTripwireSupport.component(owner, "streamConsumerRuntime", StreamConsumerRuntime.class));

        return runtime.unwrap();
    }

    private long sealedThrough() {
        return StreamTripwireSupport.component(owner, "streamSegmentIndex", SegmentIndex.class).lastSealedOffset(engineKey, PARTITION);
    }

    private static void subscribe(StreamConsumerRuntime runtime, String group, String key, ConcurrentSkipListSet<Long> delivered) {
        var result = runtime.subscribe(key,
                                       PARTITION,
                                       ConsumerConfig.consumerConfig(group),
                                       (offset, _, _) -> noteDelivered(delivered, offset),
                                       IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED);

        assertThat(result.isSuccess()).as("subscribe %s: %s", group, result).isTrue();
    }

    private static Promise<Unit> noteDelivered(ConcurrentSkipListSet<Long> delivered, long offset) {
        delivered.add(offset);

        return Promise.success(Unit.unit());
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("c16-tripwire");

        return StreamTripwireSupport.threeNodes(basePort, "c16", dataDir);
    }
}
