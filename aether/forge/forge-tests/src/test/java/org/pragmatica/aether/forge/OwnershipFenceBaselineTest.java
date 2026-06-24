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
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.consensus.NodeId;
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

/// STEP-0 regression baseline for the #345 stream ownership fence.
///
/// The stream data-plane write path (`StreamPartitionManager.publishLocal` →
/// `appendToPartition` → `OffHeapRingBuffer.append`) carries NO owner-epoch today, so it
/// performs NO owner-epoch check. A node that WAS the owner of a partition but has since been
/// deposed — ownership relocated AND the generation epoch advanced past it — can still commit a
/// stream append. This test characterizes that behaviour: it constructs a deposed/stale owner
/// using the REAL deterministic placement + epoch primitives and asserts that its append is
/// CURRENTLY ACCEPTED (documenting the bug to be fixed).
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
/// Phase 1d of the fence flips the SINGLE baseline assertion in
/// [`#staleOwnerAppend_afterOwnershipAndEpochAdvance_isCurrentlyAccepted`] from "accepted" to
/// "rejected with a `StaleEpochAppend` cause"; the within-epoch throughput number recorded by
/// [`#withinEpochAppendThroughput_onOwnedPartition_recordsBaseline`] lets the fence's per-append
/// overhead be compared against this pre-fence baseline.
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

    /// Baseline: a deposed/stale owner's data-plane append is CURRENTLY ACCEPTED because the write
    /// path takes no epoch and so cannot fence on a stale generation. Phase 1d flips the single
    /// assertion below.
    @Test
    @TerminalOperation
    void staleOwnerAppend_afterOwnershipAndEpochAdvance_isCurrentlyAccepted() {
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

        var staleAppend = staleOwnerNode.streamPartitionManager()
                                        .publishLocal(FENCE_STREAM, PARTITION, "stale-write".getBytes(UTF_8), System.currentTimeMillis());

        // FENCE (P1): flip to assertRejected (StaleEpochAppend) when the epoch fence lands — see ownership-fence-spec.md §5b/§8, issue-345 Phase 1d
        staleAppend.onFailure(OwnershipFenceBaselineTest::failStaleAppendRejected)
                   .onSuccess(offset -> assertThat(offset).isGreaterThanOrEqualTo(0L));
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

    private static void failStaleAppendRejected(Cause cause) {
        Assertions.fail("baseline: stale-owner append should be ACCEPTED today (no epoch fence yet), but was rejected: "
                        + cause.message());
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
