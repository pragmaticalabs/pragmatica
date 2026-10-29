// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// Streams overhaul E-01/D-08 (#2201), fixed by spec 3.2 (Q6 = B): the sealed tier honours the stream's own retention.
///
/// The sealed tier is reclaimed by each node's own `RetentionEnforcer`, which reads every stream's committed `RetentionPolicy`
/// and bounds the dimensions it declares. Waiting for the node default age (24 h) is not an option, so the stream declares a
/// max age of [#MAX_AGE_MS] (3 s), its ring holds 10 events so that 40 published events seal 30 of them, and after the
/// declared age has passed - twice over - a retention pass is run on every node's real enforcer (`enforceNow`, the same
/// method the five-minute tick calls, reached by reflection). Every node holds a copy (RF 3), leader or not, and each
/// reclaims its own: no sealed segment of the stream survives the pass on any node.
///
/// Before the fix this was a tripwire asserting that every segment survived (a node-wide 24 h policy that never read the
/// stream's own); the instrument was validated by mutation (`DEFAULT_STREAM_RETENTION_MS` lowered to 1 s reclaimed them).
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
        StreamTripwireSupport.deleteTree(dataDir);
    }

    @Test
    @Timeout(600)
    void sealedSegments_areReclaimedAtTheStreamsDeclaredMaxAge() {
        fillAndSeal();
        sleepQuietly(2 * MAX_AGE_MS);
        cluster.allNodes().forEach(StreamTripwireSupport::enforceRetentionPass);

        assertThat(segmentCounts()).as("no sealed segment of a stream with a %d ms max age survives a retention pass run at twice that age", MAX_AGE_MS)
                                   .allSatisfy(count -> assertThat(count).isZero());
    }

    /// The stream DECLARES its max age (#2201 Q6 = B): a policy built by a factory declares nothing and keeps the node
    /// default age, so the age is declared explicitly, as a `retention = "time"` section does.
    private static StreamConfig declaringItsAge(StreamConfig config) {
        return StreamConfig.streamConfig(config.name(),
                                         config.partitions(),
                                         config.retention().withDeclared(RetentionDimensions.AGE),
                                         config.autoOffsetReset());
    }

    /// Per node, the number of sealed segments this node's index holds for the stream's only partition.
    private List<Integer> segmentCounts() {
        return cluster.allNodes().stream().map(this::segmentCount).toList();
    }

    private int segmentCount(AetherNode node) {
        return StreamTripwireSupport.component(node, "streamSegmentIndex", SegmentIndex.class).listSegments(ENGINE_KEY, PARTITION).size();
    }

    private void fillAndSeal() {
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS, this::threeNodes, START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);

        var creator = cluster.allNodes().getFirst();

        StreamTripwireSupport.createStream(creator, declaringItsAge(StreamTripwireSupport.smallRingStream(STREAM, 1, RING_EVENTS, MAX_AGE_MS)));

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
