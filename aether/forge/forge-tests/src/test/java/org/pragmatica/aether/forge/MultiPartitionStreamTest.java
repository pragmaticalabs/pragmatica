// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ReadPreference;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #429 — multi-partition stream e2e fixture. Existing forge stream fixtures
/// ([StreamFanoutConsumerTest], [AbstractStreamOwnerFailover]) exercise SINGLE-partition streams only
/// (partitions=1, one globally-ordered log). This deploys the `test-stream-multipart` blueprint
/// (partitions=4, RF=2, min-sync-replicas=2) on a 5-node in-JVM Ember cluster and proves the three
/// coverage gaps the single-partition fixtures leave open:
///
///   (a) **partition→owner distribution.** HRW places the four partitions' owners across the cluster;
///       the four owners span MORE THAN ONE node.
///   (b) **per-partition ordering.** 40 keyless publishes driven through ONE app port round-robin
///       across the four partitions (`DefaultStreamPublisher#resolvePartition` over 40 consecutive
///       counter values → each partition gets exactly 40/4=10), and within each partition the events
///       read back with contiguous offsets and STRICTLY INCREASING embedded sequence numbers (publish
///       order preserved per partition).
///   (c) **read paths.** For one partition: an owner-local `GOVERNOR` read and a `NEAREST` read issued
///       from a node OUTSIDE the replica set (which must forward to a caught-up replica / the HRW
///       owner) return the identical offset set — proving forwarded reads agree with the local read.
///
/// ## Read-preference surface (documented, not faked)
/// The app-HTTP `/api/stream-mp/read` route carries no read-preference selector — the slice's
/// [org.pragmatica.aether.slice.StreamAccess#fetch(int, long, int)] fixes the framework default — so
/// the GOVERNOR / NEAREST arms are NOT reachable over app-HTTP. They are exercised IN-JVM via
/// [org.pragmatica.aether.stream.StreamReadRouter#read] (the same surface [LinearizableReadForgeTest]
/// uses for its `LINEARIZABLE` arm). `ANY_REPLICA` shares NEAREST's replica-routed path;
/// `LINEARIZABLE` (committed-owner routing, distinct from HRW) is covered end-to-end by
/// [LinearizableReadForgeTest] and is not re-exercised here.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiPartitionStreamTest extends AbstractMultiPartitionStream {
    private static final int EVENTS = 40;
    private static final int PER_PARTITION = EVENTS / PARTITIONS;
    private static final int READ_PATH_PARTITION = 0;

    @Override
    int basePort() {
        return 16000;
    }

    @Override
    int baseMgmtPort() {
        return 16100;
    }

    @Override
    int baseAppHttpPort() {
        return 16200;
    }

    @Override
    String nodePrefix() {
        return "mps";
    }

    @Override
    String blueprintId() {
        return "forge.test:stream-multipart:1.0.0";
    }

    @Test
    void multiPartition_ownersSpreadAndPerPartitionOrdered_localAndForwardedReadsAgree() {
        var port = appPort();

        // The RF=2 replica sets of ALL four partitions must be PLACED (owner + >=1 non-owner) before
        // publishing, so owner distribution is stable and each min-sync-2 publish has a replica to await.
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allPartitionsPlaced);

        // 40 keyless publishes through ONE app port -> round-robin over 40 consecutive counter values ->
        // each partition receives exactly 10, spread deterministically regardless of the warm-up prefix.
        publishSequence(port, EVENTS);

        var partitionEvents = drainAllPartitions(port);

        // (a) distribution + (b) per-partition ordering.
        assertEvenDistribution(partitionEvents);
        assertOwnersSpanMultipleNodes();
        for (int partition = 0; partition < PARTITIONS; partition++) {
            assertPerPartitionOrdered(partitionEvents.get(partition), partition);
        }

        // (c) read paths: owner-local GOVERNOR read vs forwarded NEAREST read from outside the replica set.
        assertLocalAndForwardedReadsAgree(READ_PATH_PARTITION);
    }

    // --- publishing ---------------------------------------------------------

    private void publishSequence(int port, int count) {
        for (long seq = 0; seq < count; seq++) {
            assertThat(publish(port, seq))
                .describedAs("publish seq %d must be ACKED (min-sync-2 replica ack)", seq)
                .isTrue();
        }
    }

    // --- assertions ---------------------------------------------------------

    /// 40 events / 4 partitions: round-robin over 40 consecutive counter values hits each residue class
    /// exactly 10 times, so every partition holds exactly 10 of the test's events and the four together
    /// account for all 40 (no loss, no partition starved).
    private void assertEvenDistribution(List<List<Event>> partitionEvents) {
        var total = 0;

        for (int partition = 0; partition < PARTITIONS; partition++) {
            var events = partitionEvents.get(partition);

            assertThat(events)
                .describedAs("partition %d receives exactly %d of the %d round-robin events", partition, PER_PARTITION, EVENTS)
                .hasSize(PER_PARTITION);
            total += events.size();
        }

        assertThat(total)
            .describedAs("every published event lands in exactly one of the %d partitions", PARTITIONS)
            .isEqualTo(EVENTS);
    }

    private void assertOwnersSpanMultipleNodes() {
        var owners = new HashSet<String>();

        for (int partition = 0; partition < PARTITIONS; partition++) {
            owners.add(ownerId(partition));
        }

        assertThat(owners)
            .describedAs("the %d partitions' HRW owners span more than one node", PARTITIONS)
            .doesNotContain("")
            .hasSizeGreaterThan(1);
    }

    /// The owner serves its own log under `GOVERNOR` (local read); a node OUTSIDE the partition's
    /// replica set serves the SAME offsets under `NEAREST` by forwarding to a caught-up replica / the
    /// HRW owner. Both reads go through the in-JVM [org.pragmatica.aether.stream.StreamReadRouter] — the
    /// only surface exposing read preference. Polls until the forwarded read has caught up to the local
    /// read's size (forwarding may lag), then asserts the offset sets are identical.
    private void assertLocalAndForwardedReadsAgree(int partition) {
        var ownerNode = ownerNode(partition);
        var forwarder = forwarderOutsideReplicaSet(partition);
        var localOffsets = inJvmOffsets(ownerNode, partition, ReadPreference.GOVERNOR);

        assertThat(localOffsets)
            .describedAs("owner-local GOVERNOR read of partition %d returns its log", partition)
            .isNotEmpty();

        await().atMost(FAILOVER_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> inJvmOffsets(forwarder, partition, ReadPreference.NEAREST).size() == localOffsets.size());

        assertThat(inJvmOffsets(forwarder, partition, ReadPreference.NEAREST))
            .describedAs("forwarded NEAREST read from a non-replica node agrees with the owner-local read")
            .isEqualTo(localOffsets);
    }

    // --- in-JVM read helpers ------------------------------------------------

    private List<Long> inJvmOffsets(AetherNode node, int partition, ReadPreference preference) {
        return node.streamReadRouter()
                   .read(STREAM_NAME, partition, 0, 500, preference)
                   .await()
                   .map(events -> events.stream().map(event -> event.offset()).sorted().toList())
                   .or(List.of());
    }

    private AetherNode ownerNode(int partition) {
        var ownerId = ownerId(partition);

        return cluster.allNodes()
                      .stream()
                      .filter(node -> node.self().id().equals(ownerId))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("owner node not resolvable for partition " + partition));
    }

    private AetherNode forwarderOutsideReplicaSet(int partition) {
        var replicaIds = ownerView(partition).map(view -> view.replicas().stream().map(replica -> replica.nodeId()).toList())
                                             .or(List.of());

        return cluster.allNodes()
                      .stream()
                      .filter(node -> !replicaIds.contains(node.self().id()))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("no node outside the replica set of partition " + partition));
    }
}
