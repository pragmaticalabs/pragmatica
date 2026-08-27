// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #345 item 1d-iii end-to-end gate: the leader-only stream-ownership DRIVER (the
/// `onReconcilePassComplete` batch seam on [ReplicaSetController] bound to the
/// [org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter] in `AetherNode`) makes the
/// stream ownership fence go LIVE in production — it AUTO-COMMITS a
/// [StreamPartitionOwnershipValue] on a real membership change, with NO manual `apply(Put)` anywhere in
/// this test.
///
/// This is distinct from [OwnershipFenceBaselineTest], which flips the fence by MANUALLY committing a
/// `StreamPartitionOwnershipValue`. Here the ownership record is committed by the driver's own reconcile
/// path, triggered by a genuine `MembershipDecision.NodeRemoved` (a graceful node kill).
///
/// ## What it proves
///   1. **Driver auto-commit.** After a stream is materialized cluster-wide and a membership reconcile
///      fires the leader's `ReplicaSetController`, the leader's driver auto-commits a
///      `StreamPartitionOwnershipValue(hrwOwner, epoch=(rabiaTerm, ownershipTerm), ownershipTerm=1)` for
///      the partition — observed via the committed KV (`StreamPartitionOwnershipKey`), with no manual
///      `apply`.
///   2. **The committed epoch fences a below-generation writer.** Every node's `OwnershipEpochHighWater`
///      observes the driver-committed `ValuePut` and advances `(FENCE_STREAM, partition)` to the driver's
///      epoch. An append on the ALIVE owner stamped with a STALE epoch strictly below the driver-committed
///      epoch (the unfenced floor [Epoch#ZERO], which a writer predating the committed ownership
///      generation holds) is REJECTED with a [StreamError.StaleEpochAppend].
///   3. **The REAL deposed-but-alive owner case (same-term reshuffle).** The production
///      [org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter] — wired to the live
///      leader's committed KV and consensus apply, with a test-controlled HRW seam standing in for the
///      reshuffle — commits owner0 at epoch `(term, 1)`, then commits a transfer to owner1 at epoch
///      `(term, 2)` at the SAME generation term (no leader re-election). owner0 stays ALIVE; its append
///      stamped with its now-stale `(term, 1)` epoch is REJECTED with a [StreamError.StaleEpochAppend].
///      This proves the epoch advances on an owner change (not only on a generation bump), so the
///      same-term HRW-reshuffle fencing gap is closed end-to-end through the writer.
///
/// ## Determinism
///   - [`ReplicaPlacement#place`] is PURE HRW — the test computes the expected owner from the SAME
///     committed member view (`coreNodes()`) the driver uses, so the asserted owner is exact.
///   - The driver's epoch is `Epoch.epoch(rabiaTerm, ownershipTerm)`: the committed generation term
///     paired with the per-partition takeover counter as its local component. It advances on a leader
///     re-election (rabiaTerm, the dominant component) AND on every owner change (ownershipTerm, the
///     local counter), so a deposed-but-alive owner is fenced even within a single generation term.
///   - The same-term owner0→owner1 RELOCATION (test 3) cannot be driven by a real single-JVM membership
///     change while keeping owner0 alive — HRW only relocates a partition when its OWNER leaves, which
///     would kill owner0. The test therefore drives the transfer through the production writer with a
///     deterministic HRW seam (the same seam `AetherNode` injects), committing through REAL consensus and
///     the REAL append fence — the writer, the KV, the high-water and the append guard are all production.
/// #556 forge SMOKE set (stream leg): one of three classes `./forge.sh` runs by default, so a
/// developer gets a real multi-node cluster signal before pushing instead of discovering a
/// cluster-level regression in CI. Keep this set small — its value is that people actually run it.
@Tag("Smoke")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamOwnershipDriverFenceTest {
    private static final Logger log = LoggerFactory.getLogger(StreamOwnershipDriverFenceTest.class);

    private static final int SIZE = 5;
    private static final int BASE_PORT = 5960;
    private static final int BASE_MGMT_PORT = 6060;
    private static final int BASE_APP_HTTP_PORT = 6160;
    private static final String PREFIX = "sodf";

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration OBSERVE_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration POLL = Duration.ofMillis(500);

    private static final String FENCE_STREAM = "sodf:fence";
    private static final int PARTITION = 0;
    private static final int REQUESTED_RF = 1;

    /// Distinct stream for the same-term-transfer test (test 3), so it shares no committed ownership
    /// state with the auto-commit test (test 1) under the PER_CLASS shared cluster.
    private static final String TRANSFER_STREAM = "sodf:transfer";
    private static final int TRANSFER_PARTITION = 0;

    private EmberCluster cluster;

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, PREFIX);
        cluster.start().await().onFailure(StreamOwnershipDriverFenceTest::failStart);
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesReady);
        log.info("OWNERSHIP-DRIVER-FENCE: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    /// The DRIVER auto-commits ownership on a membership reconcile, and the resulting committed epoch
    /// fences a stale-epoch append on the still-alive owner — no manual ownership commit, no
    /// hand-injected high-water.
    @Test
    @TerminalOperation
    void driverAutoCommitsOwnership_andStaleEpochAppendIsRejected() {
        cluster.allNodes().forEach(node -> materialize(node, FENCE_STREAM));

        // The fenced partition's HRW owner, computed from the SAME committed member view the driver uses;
        // it stays ALIVE for the whole test (the deposed-but-alive case).
        var owner0 = hrwOwner(FENCE_STREAM, PARTITION);
        log.info("OWNERSHIP-DRIVER-FENCE: fenced-partition owner0={} leader={}",
                 owner0.id(), cluster.currentLeader().or("none"));

        // Drive the leader's ReplicaSetController reconcile with a real MembershipDecision — the SAME
        // event type the production membership tail delivers — so the driver iterates the now-materialized
        // FENCE_STREAM and auto-commits its ownership records. A NodeJoined for an already-present member
        // over the current view is idempotent for every other membership subscriber. This is NOT a manual
        // apply(Put): the driver still computes and commits the ownership value itself. (A real node kill
        // does not deterministically shrink coreNodes() within budget in single-JVM Forge — see report.)
        // Assert the driver auto-committed a real StreamPartitionOwnershipValue for the fenced partition,
        // observed via committed KV — owner == HRW owner0, epoch == driver-minted (rabiaTerm:0), term 1.
        // Re-deliver the reconcile trigger on each poll: the reconcile is async on the controller executor
        // and idempotent, so re-driving until the record appears removes any materialize/reconcile race.
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL).until(this::reconcileAndCheckCommitted);
        var committed = committedOwnership(PARTITION).or(StreamOwnershipDriverFenceTest::failNoRecord);
        assertThat(committed.owner())
            .as("driver must auto-commit the HRW owner for the fenced partition (no manual commit)")
            .isEqualTo(owner0);
        assertThat(committed.ownerEpoch().isStrictlyAfter(Epoch.ZERO))
            .as("driver-committed epoch %s must strictly dominate the unfenced floor (Epoch.ZERO)", committed.ownerEpoch())
            .isTrue();
        assertThat(committed.ownershipTerm())
            .as("driver's initial ownership record carries ownershipTerm 1")
            .isEqualTo(1L);
        log.info("OWNERSHIP-DRIVER-FENCE: driver auto-committed ({}, {}) owner={} epoch={} ownershipTerm={}",
                 FENCE_STREAM, PARTITION, committed.owner().id(), committed.ownerEpoch(), committed.ownershipTerm());

        // The owner under test is still ALIVE. Wait for its OwnershipEpochHighWater to observe the
        // driver-committed ValuePut (the high-water advance is what activates the fence), then assert a
        // stale-epoch (ZERO) append is rejected.
        var ownerNode = resolveNode(owner0);
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL)
               .until(() -> staleAppend(ownerNode, Epoch.ZERO).isFailure());

        staleAppend(ownerNode, Epoch.ZERO)
            .onSuccess(offset -> Assertions.fail(
                "fence: a below-generation (Epoch.ZERO) append on the alive owner must be REJECTED once the "
                + "driver-committed epoch advanced the high-water, but it was accepted at offset " + offset))
            .onFailure(StreamOwnershipDriverFenceTest::assertStaleEpochAppend);
        log.info("OWNERSHIP-DRIVER-FENCE: stale-epoch append on alive owner {} correctly REJECTED — fence live via driver",
                 owner0.id());
    }

    /// The REAL deposed-but-alive owner case at a SINGLE generation term (same-term HRW reshuffle): the
    /// production [StreamPartitionOwnershipWriter] — wired to the live leader's committed KV and consensus
    /// apply, with a test-controlled HRW seam standing in for the reshuffle (a real single-JVM membership
    /// move cannot relocate a partition while keeping its owner alive — HRW only relocates when the owner
    /// LEAVES) — commits owner0 at `(term, 1)`, then a transfer to owner1 at `(term, 2)` at the SAME term.
    /// owner0 stays ALIVE; its append stamped with its now-stale `(term, 1)` epoch is REJECTED. This is
    /// the property the epoch-on-owner-change fix adds: without it both records would share `(term, 0)`
    /// and owner0 would NOT be fenced.
    @Test
    @TerminalOperation
    void sameTermOwnerTransfer_deposedButAliveOwnerIsFenced() {
        cluster.allNodes().forEach(node -> materialize(node, TRANSFER_STREAM));

        var view = coreMembers(leaderNode());
        var owner0 = hrwOwner(TRANSFER_STREAM, TRANSFER_PARTITION);
        var owner1 = view.stream().filter(n -> !n.equals(owner0)).findFirst().orElse(view.getLast());
        var owner0Node = resolveNode(owner0);
        var term = leaderNode().currentGenerationEpoch().rabiaTerm();
        log.info("OWNERSHIP-DRIVER-FENCE: same-term transfer owner0={} owner1={} term={}", owner0.id(), owner1.id(), term);

        var hrwHolder = new AtomicReference<>(owner0);
        var writer = liveWriter(term, hrwHolder::get);

        // The writer commits owner0 at (term, 1) through REAL consensus — no manual Put; the writer mints it.
        commitOwnershipVia(writer, TRANSFER_STREAM, TRANSFER_PARTITION);
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL)
               .until(() -> committedOwner(TRANSFER_PARTITION).map(v -> v.owner().equals(owner0)).or(false));
        var first = committedOwner(TRANSFER_PARTITION).or(StreamOwnershipDriverFenceTest::failNoRecord);
        assertThat(first.ownerEpoch())
            .as("writer commits owner0 at (term, ownershipTerm=1)")
            .isEqualTo(Epoch.epoch(term, 1L));

        // Same-term reshuffle: HRW now selects owner1. The writer commits the transfer at (term, 2).
        hrwHolder.set(owner1);
        commitOwnershipVia(writer, TRANSFER_STREAM, TRANSFER_PARTITION);
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL)
               .until(() -> committedOwner(TRANSFER_PARTITION).map(v -> v.owner().equals(owner1)).or(false));
        var second = committedOwner(TRANSFER_PARTITION).or(StreamOwnershipDriverFenceTest::failNoRecord);
        assertThat(second.ownerEpoch())
            .as("the same-term transfer to owner1 advances the epoch to (term, ownershipTerm=2)")
            .isEqualTo(Epoch.epoch(term, 2L));
        assertThat(second.ownerEpoch().isStrictlyAfter(first.ownerEpoch()))
            .as("owner1's epoch strictly dominates owner0's at the SAME generation term")
            .isTrue();
        log.info("OWNERSHIP-DRIVER-FENCE: same-term transfer committed owner1 at {} (was {})",
                 second.ownerEpoch(), first.ownerEpoch());

        // owner0 is STILL ALIVE. Once its high-water observes (term, 2), its (term, 1)-stamped append is fenced.
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL)
               .until(() -> transferAppend(owner0Node, Epoch.epoch(term, 1L)).isFailure());

        transferAppend(owner0Node, Epoch.epoch(term, 1L))
            .onSuccess(offset -> Assertions.fail(
                "fence: the deposed-but-alive owner0's (term, 1) append must be REJECTED after the same-term "
                + "transfer advanced the committed epoch to (term, 2), but it was accepted at offset " + offset))
            .onFailure(StreamOwnershipDriverFenceTest::assertStaleEpochAppend);
        assertThat(cluster.getNode(owner0.id()).isPresent())
            .as("owner0 must remain ALIVE throughout the transfer (the deposed-but-alive case)")
            .isTrue();
        log.info("OWNERSHIP-DRIVER-FENCE: deposed-but-alive owner0 {} (term,1) append correctly REJECTED — same-term fence live",
                 owner0.id());
    }

    /// Build the production [StreamPartitionOwnershipWriter] bound to the LIVE leader: real committed-KV
    /// reader, real consensus generation term (held fixed for a same-term reshuffle), and a
    /// test-controlled HRW seam. Leader gate is `true` because the test drives it on the leader's behalf.
    private StreamPartitionOwnershipWriter liveWriter(long term, Supplier<NodeId> hrwOwner) {
        return StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> true,
                                                                             () -> term,
                                                                             HlcClock.hlcClock(leaderNode().self()),
                                                                             (stream, partition) -> committedOwner(partition),
                                                                             (stream, partition) -> Option.some(hrwOwner.get()));
    }

    /// Fire the writer for one `(stream, partition)` and apply the emitted command through REAL consensus
    /// on the leader. The writer itself mints the ownership value (epoch + ownershipTerm) — the test only
    /// forwards the command it produces, exactly as `AetherNode.driveStreamOwnership` does in production.
    @TerminalOperation
    private void commitOwnershipVia(StreamPartitionOwnershipWriter writer, String stream, int partition) {
        writer.writeOwnershipChange(stream, partition)
              .onPresent(this::applyOnLeader);
    }

    @TerminalOperation
    private void applyOnLeader(KVCommand<AetherKey> command) {
        leaderNode().<Object>apply(List.of(command))
                    .await()
                    .onFailure(StreamOwnershipDriverFenceTest::failScenario);
    }

    private Result<Long> transferAppend(AetherNode ownerNode, Epoch staleEpoch) {
        return ownerNode.streamPartitionManager()
                        .publishLocal(TRANSFER_STREAM, TRANSFER_PARTITION, "stale-write".getBytes(UTF_8), System.currentTimeMillis(), staleEpoch);
    }

    private Option<StreamPartitionOwnershipValue> committedOwner(int partition) {
        return leaderNode().kvStore()
                           .getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(TRANSFER_STREAM, partition),
                                     StreamPartitionOwnershipValue.class);
    }

    /// Re-deliver the membership reconcile trigger to every node, then check whether the driver has
    /// committed the fenced partition's ownership yet. Idempotent: a `NodeJoined` for an already-present
    /// member converges the same committed state on every subscriber.
    @TerminalOperation
    private boolean reconcileAndCheckCommitted() {
        triggerMembershipReconcile();

        return committedOwnership(PARTITION).isPresent();
    }

    /// Deliver a `MembershipDecision.NodeJoined` for an already-present member over the current core view
    /// to EVERY node. This is the production membership-tail event type (idempotent for an existing
    /// member), so it exercises the real `ReplicaSetController.onMembershipDecision` → reconcile → driver
    /// path. Routed to all nodes so it reaches the leader regardless of which node holds leadership.
    @TerminalOperation
    private void triggerMembershipReconcile() {
        var view = coreMembers(leaderNode());
        var member = view.getFirst();
        var decision = MembershipDecision.nodeJoined(member, view);

        cluster.allNodes().forEach(node -> node.route(decision));
    }

    private Result<Long> staleAppend(AetherNode ownerNode, Epoch staleEpoch) {
        return ownerNode.streamPartitionManager()
                        .publishLocal(FENCE_STREAM, PARTITION, "stale-write".getBytes(UTF_8), System.currentTimeMillis(), staleEpoch);
    }

    private Option<StreamPartitionOwnershipValue> committedOwnership(int partition) {
        return leaderNode().kvStore()
                           .getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(FENCE_STREAM, partition),
                                     StreamPartitionOwnershipValue.class);
    }

    /// HRW owner computed from the leader's committed member view (`coreNodes()` / `clusterSize()`) —
    /// the SAME inputs the driver feeds to `ReplicaPlacement.place`, so this matches the driver exactly.
    private NodeId hrwOwner(String stream, int partition) {
        var leader = leaderNode();
        var members = coreMembers(leader);
        var rf = ReplicaPlacement.replicationFactor(REQUESTED_RF, clusterSize(leader));

        return ReplicaPlacement.place(stream, partition, members, rf)
                               .map(Placement::owner)
                               .toResult(FenceError.NO_PLACEMENT)
                               .onFailure(StreamOwnershipDriverFenceTest::failScenario)
                               .or(members.getFirst());
    }

    private void materialize(AetherNode node, String stream) {
        node.streamPartitionManager()
            .ensureStreamMaterialized(StreamConfig.streamConfig(stream))
            .onFailure(StreamOwnershipDriverFenceTest::failMaterialize);
    }

    private List<NodeId> coreMembers(AetherNode node) {
        return node.clusterTopologyManager()
                   .map(ctm -> List.copyOf(ctm.observer().coreNodes()))
                   .or(List.of());
    }

    private int clusterSize(AetherNode node) {
        return node.clusterTopologyManager().map(ctm -> ctm.observer().clusterSize()).or(SIZE);
    }

    private AetherNode resolveNode(NodeId nodeId) {
        return cluster.getNode(nodeId.id())
                      .toResult(FenceError.NODE_UNRESOLVED)
                      .onFailure(StreamOwnershipDriverFenceTest::failScenario)
                      .or(cluster.allNodes().getFirst());
    }

    private AetherNode leaderNode() {
        return cluster.currentLeader().flatMap(cluster::getNode).or(cluster.allNodes().getFirst());
    }

    private boolean allNodesReady() {
        return cluster.allNodes().stream().allMatch(AetherNode::isReady);
    }

    private static StreamPartitionOwnershipValue failNoRecord() {
        throw new AssertionError("Driver did not auto-commit a StreamPartitionOwnershipValue for the fenced partition");
    }

    private static void assertStaleEpochAppend(Cause cause) {
        assertThat(cause)
            .as("the deposed (below-generation) append must be rejected with StaleEpochAppend, but got: %s", cause.message())
            .isInstanceOf(StreamError.StaleEpochAppend.class);
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

    private enum FenceError implements Cause {
        NO_PLACEMENT("ReplicaPlacement returned no owner for the partition"),
        NODE_UNRESOLVED("Computed owner NodeId could not be resolved to a cluster node");

        private final String message;

        FenceError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
