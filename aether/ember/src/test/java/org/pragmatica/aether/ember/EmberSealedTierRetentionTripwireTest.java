// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul tripwire, step 2 (#2201), retention: E-01/D-08 - per-stream retention never reaches the sealed tier.
///
/// The sealed tier is reclaimed by the node's one `RetentionEnforcer`, built with the node-wide
/// `AetherNode.DEFAULT_STREAM_RETENTION_MS` (24 h) and a policy that never reads the stream's own `RetentionPolicy`.
/// Waiting 24 h is not an option, so the test runs the CONVERSE direction: the stream declares a max age of
/// [#MAX_AGE_MS] (3 s), its ring holds 10 events so that 40 published events seal 30 of them, and after the declared age
/// has passed - twice over - a retention pass is run on every node's real enforcer (`enforceNow`, the same method the
/// five-minute tick calls, reached by reflection). A stream whose policy was honoured has no sealed segment older than
/// its max age after that pass; today they are all still there.
///
/// - The ENABLED tripwire asserts today's behaviour: every sealed segment survives the pass, and the pass dropped none.
/// - The `@Disabled` assertion is the acceptance criterion: no sealed segment of the stream survives the pass.
///
/// The instrument is validated by mutation, not by a control inside the test: with `DEFAULT_STREAM_RETENTION_MS` lowered
/// to 1 s the same pass reclaims the segments and the tripwire reddens (recorded in the report).
@PortBudget
class EmberSealedTierRetentionTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long RING_EVENTS = 10L;
    private static final long MAX_AGE_MS = 3_000L;
    private static final int PUBLISHED = 40;
    private static final long SEALED_THROUGH = PUBLISHED - RING_EVENTS - 1;
    private static final long SEAL_BUDGET_MS = 60_000L;
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final String STREAM = "e01";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private EmberCluster cluster;
    private Path dataDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(600)
    void sealedSegments_tripwire_surviveTheStreamsDeclaredMaxAge() {
        fillAndSeal();

        var before = segmentCounts();

        sleepQuietly(2 * MAX_AGE_MS);

        var dropped = cluster.allNodes().stream().mapToInt(StreamTripwireSupport::enforceRetentionPass).sum();

        assertThat(before).as("control: every node sealed the evicted offsets, so there is something to reclaim").allSatisfy(count -> assertThat(count).isPositive());
        assertThat(dropped).as("E-01 fixed? A retention pass dropped sealed segments older than the stream's %d ms max age. "
                               + "Delete this tripwire and enable sealedSegments_areReclaimedAtTheStreamsDeclaredMaxAge (#2201)",
                               MAX_AGE_MS)
                           .isZero();
        assertThat(segmentCounts()).as("every sealed segment survived twice the stream's declared max age plus a retention pass").isEqualTo(before);
    }

    @Test
    @Disabled("E-01/D-08 (#2201): enable when the sealed tier honours the stream's own retention; RED today: all segments survive the pass (node-wide 24 h policy)")
    @Timeout(600)
    void sealedSegments_areReclaimedAtTheStreamsDeclaredMaxAge() {
        fillAndSeal();
        sleepQuietly(2 * MAX_AGE_MS);
        cluster.allNodes().forEach(StreamTripwireSupport::enforceRetentionPass);

        assertThat(segmentCounts()).as("no sealed segment of a stream with a %d ms max age survives a retention pass run at twice that age", MAX_AGE_MS)
                                   .allSatisfy(count -> assertThat(count).isZero());
    }

    /// Per node, the number of sealed segments this node's index holds for the stream's only partition.
    private java.util.List<Integer> segmentCounts() {
        return cluster.allNodes().stream().map(this::segmentCount).toList();
    }

    private int segmentCount(AetherNode node) {
        return StreamTripwireSupport.component(node, "streamSegmentIndex", SegmentIndex.class).listSegments(ENGINE_KEY, PARTITION).size();
    }

    private void fillAndSeal() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);

        var creator = cluster.allNodes().getFirst();

        assertThat(creator.streamPartitionManager().createStream(StreamTripwireSupport.smallRingStream(STREAM, 1, RING_EVENTS, MAX_AGE_MS)).isSuccess())
            .as("stream created").isTrue();

        var owner = cluster.getNode(StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS).owner().id()).unwrap();

        for (var i = 0; i < PUBLISHED; i++) {
            var response = StreamTripwireSupport.publishVia(cluster.getLeaderManagementPort().or(-1), STREAM, "event-" + i);

            assertThat(response.acked()).as("publish %d: %s", i, response.body()).isTrue();
        }
        // Every node holds a copy of the partition (RF 3), so each evicts and seals its own ring: wait for all three.
        awaitUntil(() -> cluster.allNodes().stream().allMatch(node -> sealedThrough(node) >= SEALED_THROUGH),
                   SEAL_BUDGET_MS,
                   "offsets 0.." + SEALED_THROUGH + " are sealed on every node (owner " + owner.self().id() + ")");
    }

    private static long sealedThrough(AetherNode node) {
        return StreamTripwireSupport.component(node, "streamSegmentIndex", SegmentIndex.class).lastSealedOffset(ENGINE_KEY, PARTITION);
    }

    private EmberCluster threeNodes(int basePort) {
        dataDir = StreamTripwireSupport.newDataDir("e01-tripwire");

        return StreamTripwireSupport.threeNodes(basePort, "e01", dataDir);
    }
}
