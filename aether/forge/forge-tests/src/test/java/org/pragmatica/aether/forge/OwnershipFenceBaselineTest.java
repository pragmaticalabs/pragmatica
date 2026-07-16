// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Assertions;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// STEP-0 → 1d-ii regression gate for the #345 stream ownership fence.
///
/// The stream data-plane write path (`StreamPartitionManager.publishLocal` → `appendToPartition` →
/// `OffHeapRingBuffer.append`) now carries a writer-supplied owner [Epoch] and fences each append at
/// the replica's commit point: an append whose epoch is STRICTLY older than the `(stream, partition)`
/// domain high-water is a deposed owner and is REJECTED with a [StreamError.StaleEpochAppend] (spec
/// §5b/§6/§8). A node that WAS the owner of a partition but has since been deposed — ownership
/// relocated AND the generation epoch advanced past it — can no longer commit a stream append.
///
/// This test was the STEP-0 baseline (it asserted the deposed owner's append was ACCEPTED, documenting
/// the pre-fence bug). Phase 1d-ii FLIPS it: the deposed owner's append, stamped with its stale epoch,
/// is now REJECTED. The high-water that does the fencing advances via the REAL committed-ownership
/// observe chain (1d-i): the test commits a genuine `StreamPartitionOwnershipValue(owner1, epoch1)`
/// through the cluster KV, every node's `OwnershipEpochHighWater` observes the resulting `ValuePut`
/// and advances `StreamPartition(FENCE_STREAM, 0)` to `epoch1` — not a hand-injected high-water.
///
/// Determinism comes from using pure primitives rather than a racy kill-based handover:
///
///   - [`ReplicaPlacement#place`] is a PURE HRW function: the same member set always yields the
///     same owner. Ownership is moved deterministically by removing the current owner from the
///     member list and recomputing — HRW relocates the partition to a surviving member.
///   - [`Epoch`] is a PURE `Comparable` record: minting epoch0 (term 7) and epoch1 (term 8)
///     makes `epoch1.isStrictlyAfter(epoch0)` true, modelling the governor-handover term bump
///     that strictly dominates the deposed owner's generation.
///
/// Cluster FORMATION uses the harness's real await (`currentLeader().isPresent()` + per-node
/// health) — there are no `Thread.sleep` calls for correctness anywhere.
///
/// The within-epoch throughput number recorded by
/// [`#withinEpochAppendThroughput_onOwnedPartition_recordsBaseline`] stays a pre/post-fence comparison
/// point: its partition has no committed ownership record, so its high-water is the floor and its
/// floor-stamped appends are never fenced — the fence is inert within a stable epoch.
///
/// See `ownership-fence-spec.md` and `issue-345-implementation-plan.md`.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnershipFenceBaselineTest {
    private static final Logger log = LoggerFactory.getLogger(OwnershipFenceBaselineTest.class);

    private static final int SIZE = 5;
    private static final int BASE_PORT = 5760;
    private static final int BASE_MGMT_PORT = 5860;
    private static final int BASE_APP_HTTP_PORT = 5960;
    private static final String PREFIX = "ofb";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final String FENCE_STREAM = "ofb:fence-baseline";
    private static final String THROUGHPUT_STREAM = "ofb:throughput";
    private static final int PARTITION = 0;
    private static final int REQUESTED_RF = 1;

    private static final int THROUGHPUT_APPENDS = 2000;
    private static final int THROUGHPUT_PAYLOAD_BYTES = 32;

    private EmberCluster cluster;

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, PREFIX);
        cluster.start()
               .await()
               .onFailure(OwnershipFenceBaselineTest::failStart);

        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesReady);
        log.info("OWNERSHIP-FENCE-BASELINE: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    /// 1d-ii fence: a deposed/stale owner's data-plane append, stamped with its now-stale owner epoch,
    /// is REJECTED with a [StreamError.StaleEpochAppend] because the `(stream, partition)` high-water
    /// has advanced past it. The advance happens through the REAL committed-ownership observe chain
    /// (1d-i): a genuine `StreamPartitionOwnershipValue(owner1, epoch1)` is committed through cluster KV
    /// and every node's `OwnershipEpochHighWater` observes it. This is the STEP-0 baseline flipped from
    /// accepted → rejected.
    @Test
    @TerminalOperation
    void staleOwnerAppend_afterOwnershipAndEpochAdvance_isRejected() {
        var members = cluster.allNodes().stream().map(AetherNode::self).toList();
        assertThat(members).hasSize(SIZE);

        var rf = ReplicaPlacement.replicationFactor(REQUESTED_RF, members.size());
        var owner0 = ownerOf(FENCE_STREAM, members, rf);

        var epoch0 = Epoch.epoch(7L, 0L);
        var epoch1 = Epoch.epoch(8L, 0L);
        assertThat(epoch1.isStrictlyAfter(epoch0))
            .as("new owner's generation (term 8) must strictly dominate the deposed owner's (term 7)")
            .isTrue();

        var membersAfter = members.stream().filter(n -> !n.equals(owner0)).toList();
        var owner1 = ownerOf(FENCE_STREAM, membersAfter, ReplicaPlacement.replicationFactor(REQUESTED_RF, membersAfter.size()));
        assertThat(owner1)
            .as("removing owner0 must RELOCATE ownership (HRW) so owner0 is now a STALE owner under epoch1")
            .isNotEqualTo(owner0);

        var staleOwnerNode = resolveNode(owner0);
        materialize(staleOwnerNode, FENCE_STREAM);

        // Advance the partition high-water to epoch1 through the REAL committed-ownership observe chain
        // (1d-i): commit a genuine StreamPartitionOwnershipValue(owner1, epoch1) so every node's
        // OwnershipEpochHighWater observes the ValuePut and advances StreamPartition(FENCE_STREAM, 0).
        commitOwnership(owner1, epoch1);
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> staleAppend(staleOwnerNode, epoch0).isFailure());

        // The deposed owner (owner0) appends stamped with its now-stale epoch0 → rejected everywhere.
        staleAppend(staleOwnerNode, epoch0)
            .onSuccess(offset -> Assertions.fail("fence: deposed owner's stale-epoch append must be REJECTED, but was accepted at offset " + offset))
            .onFailure(OwnershipFenceBaselineTest::assertStaleEpochAppend);
    }

    private Result<Long> staleAppend(AetherNode staleOwnerNode, Epoch staleEpoch) {
        return staleOwnerNode.streamPartitionManager()
                             .publishLocal(FENCE_STREAM, PARTITION, "stale-write".getBytes(UTF_8), System.currentTimeMillis(), staleEpoch);
    }

    /// Commit a real `StreamPartitionOwnershipValue` for `(FENCE_STREAM, PARTITION)` through cluster KV
    /// on the leader, so the consensus-ordered `ValuePut` propagates to every node's high-water (1d-i
    /// observe wiring) — the authoritative epoch source, not a hand-injected value.
    private void commitOwnership(NodeId owner, Epoch epoch) {
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey(FENCE_STREAM, PARTITION);
        var value = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, epoch, 1L, HlcTimestamp.ZERO);
        KVCommand<AetherKey> put = new KVCommand.Put<AetherKey, AetherValue>(key, value);

        leaderNode().<Object>apply(List.of(put))
                    .await()
                    .onFailure(OwnershipFenceBaselineTest::failScenario);
    }

    private AetherNode leaderNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .toResult(BaselineError.NODE_UNRESOLVED)
                      .onFailure(OwnershipFenceBaselineTest::failScenario)
                      .or(cluster.allNodes().getFirst());
    }

    /// Records (does not assert) the within-epoch append throughput on an owned partition, so the
    /// fence's per-append overhead can later be compared against this pre-fence baseline. Asserts
    /// only that all appends succeeded.
    @Test
    @TerminalOperation
    void withinEpochAppendThroughput_onOwnedPartition_recordsBaseline() {
        var members = cluster.allNodes().stream().map(AetherNode::self).toList();
        assertThat(members).hasSize(SIZE);

        var rf = ReplicaPlacement.replicationFactor(REQUESTED_RF, members.size());
        var owner = ownerOf(THROUGHPUT_STREAM, members, rf);
        var ownerNode = resolveNode(owner);
        materialize(ownerNode, THROUGHPUT_STREAM);

        var spm = ownerNode.streamPartitionManager();
        var payload = new byte[THROUGHPUT_PAYLOAD_BYTES];
        var successes = new int[]{0};

        var startNanos = System.nanoTime();
        for (var i = 0; i < THROUGHPUT_APPENDS; i++) {
            appendOnce(spm, THROUGHPUT_STREAM, payload).onSuccess(offset -> successes[0]++);
        }
        var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        var writesPerSec = THROUGHPUT_APPENDS * 1000.0 / Math.max(1L, elapsedMs);

        log.info("OWNERSHIP-FENCE-BASELINE throughput: {} appends in {} ms = {} writes/sec",
                 THROUGHPUT_APPENDS, elapsedMs, writesPerSec);

        assertThat(successes[0])
            .as("all %d within-epoch appends on the owned partition must succeed", THROUGHPUT_APPENDS)
            .isEqualTo(THROUGHPUT_APPENDS);
    }

    private NodeId ownerOf(String stream, List<NodeId> members, int rf) {
        return ReplicaPlacement.place(stream, PARTITION, members, rf)
                               .map(Placement::owner)
                               .toResult(BaselineError.NO_PLACEMENT)
                               .onFailure(OwnershipFenceBaselineTest::failScenario)
                               .or(members.getFirst());
    }

    private AetherNode resolveNode(NodeId nodeId) {
        return cluster.getNode(nodeId.id())
                      .toResult(BaselineError.NODE_UNRESOLVED)
                      .onFailure(OwnershipFenceBaselineTest::failScenario)
                      .or(cluster.allNodes().getFirst());
    }

    private void materialize(AetherNode node, String stream) {
        node.streamPartitionManager()
            .ensureStreamMaterialized(StreamConfig.streamConfig(stream))
            .onFailure(OwnershipFenceBaselineTest::failMaterialize);
    }

    private static Result<Long> appendOnce(StreamPartitionManager spm, String stream, byte[] payload) {
        return spm.publishLocal(stream, PARTITION, payload, System.currentTimeMillis());
    }

    private boolean allNodesReady() {
        return cluster.allNodes().stream().allMatch(AetherNode::isReady);
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario setup failed: " + cause.message());
    }

    private static void failMaterialize(Cause cause) {
        throw new AssertionError("Stream materialization failed: " + cause.message());
    }

    private static void assertStaleEpochAppend(Cause cause) {
        assertThat(cause)
            .as("deposed owner's append must be rejected with a StaleEpochAppend cause, but got: %s", cause.message())
            .isInstanceOf(StreamError.StaleEpochAppend.class);
    }

    private enum BaselineError implements Cause {
        NO_PLACEMENT("ReplicaPlacement returned no owner for the partition"),
        NODE_UNRESOLVED("Computed owner NodeId could not be resolved to a cluster node");

        private final String message;

        BaselineError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
