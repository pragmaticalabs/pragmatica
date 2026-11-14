// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #2981 step 2 (design §2.2 check 3, §8): every non-replica visibility advance goes through `ownerAdvance`, which advances to
/// the owner bound only while this node's lease is valid, the applied record names it and it is activated; otherwise only to
/// the replica bound (here: nothing, an owner holds no committed mark). One test per entry path, the lapse injected AFTER the
/// admission edge the path hangs off (the SPM's own gate admits regardless of the lease), plus the bypasses and the structural
/// rule. The `raiseToMarker` path is pinned in [StreamPartitionManagerAckedMarkerTest].
class OwnerAdvanceLeaseTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final StreamPartitionOwnershipValue RECORD = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF,
                                                                                                                            Epoch.epoch(1L,
                                                                                                                                        1L,
                                                                                                                                        1L),
                                                                                                                            1L,
                                                                                                                            HlcTimestamp.ZERO);
    private static final Path SPM_SOURCE = Path.of("src/main/java/org/pragmatica/aether/stream/StreamPartitionManager.java");
    private static final Pattern METHOD = Pattern.compile("^ {4}(?:public |private |protected |static |final |synchronized )*[\\w<>\\[\\], ?.]+ (\\w+)\\(");

    @TempDir
    Path walDir;

    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final AtomicBoolean activated = new AtomicBoolean(true);
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.some(RECORD));
    private final AtomicReference<Option<NodeId>> remoteOwner = new AtomicReference<>(Option.none());
    private StreamPartitionManager manager;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    // ---- one test per non-replica entry path ------------------------------------------------------------------------------

    /// `ackedVisible`: an ack under a lapsed lease exposes nothing; the same ack under a valid lease exposes the acked prefix.
    @Test
    void ack_underALapsedLease_staysAtTheReplicaBound_thenAValidLeaseExposesIt() {
        var replication = replicationWithPeer();
        manager = wired(streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication));
        createStream(2);
        publish("e0");
        leaseValid.set(false);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        assertThat(visible()).as("a lapsed owner does not expose an acked record").isEqualTo(-1L);
        leaseValid.set(true);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        assertThat(visible()).as("control: under a valid lease the ack exposes it").isEqualTo(0L);
    }

    /// `refreshVisible` entered from `ownerDurable`: the peer acked ahead of the owner's durability; the owner's fsync under a
    /// lapsed lease exposes nothing, a later one under a valid lease exposes the acked prefix.
    @Test
    void ownerDurable_underALapsedLease_staysAtTheReplicaBound_thenAValidLeaseExposesIt() {
        var replication = replicationWithPeer();
        manager = wired(streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication));
        createStream(2);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        leaseValid.set(false);
        publish("e0");
        assertThat(visible()).as("a lapsed owner's durability exposes nothing").isEqualTo(-1L);
        leaseValid.set(true);
        publish("e1");
        assertThat(visible()).as("control: min(durable 1, acked 0)").isEqualTo(0L);
    }

    /// `refreshVisible` entered from `replicaDurable`'s non-replica arm (a promoted node landing a recovered record).
    @Test
    void replicaDurable_nonReplicaArm_underALapsedLease_staysAtTheReplicaBound_thenAValidLeaseExposesIt() {
        manager = wired(streamPartitionManager(Long.MAX_VALUE));
        createStream(2);
        leaseValid.set(false);
        appendRecovered("r0");
        assertThat(visible()).as("a lapsed non-replica exposes no recovered record").isEqualTo(-1L);
        leaseValid.set(true);
        appendRecovered("r1");
        assertThat(visible()).as("control: no peer barrier in this fixture, visible = durable").isEqualTo(1L);
    }

    /// `exposeVerified`'s non-replica arm.
    @Test
    void markVerified_nonReplicaArm_underALapsedLease_staysAtTheReplicaBound_thenAValidLeaseExposesIt() {
        manager = wired(streamPartitionManager(Long.MAX_VALUE));
        createStream(2);
        leaseValid.set(false);
        publish("e0");
        manager.markVerified(STREAM, PARTITION, 0L);
        assertThat(visible()).as("a lapsed owner's verification exposes nothing").isEqualTo(-1L);
        leaseValid.set(true);
        manager.markVerified(STREAM, PARTITION, 0L);
        assertThat(visible()).as("control: under a valid lease verification exposes it").isEqualTo(0L);
    }

    /// `restoreVisible`'s OWNER arm: a rebuilt ring under a lapsed lease restores nothing; under a valid lease the whole tail
    /// this fixture's barrier-free replication supports.
    @Test
    void restoreVisible_ownerArm_underALapsedLease_staysAtTheReplicaBound_thenAValidLeaseRestoresIt() {
        restartedWithThreeRecords(ReplicaSetController.Role.OWNER, false);
        assertThat(durable()).as("control: the replayed tail is durable").isEqualTo(2L);
        assertThat(visible()).as("a lapsed owner restores nothing").isEqualTo(-1L);
        restartedWithThreeRecords(ReplicaSetController.Role.OWNER, true);
        assertThat(visible()).as("control: under a valid lease the tail is restored").isEqualTo(2L);
    }

    /// `restoreVisible`'s NONE arm (role unresolved): the same rule as the OWNER arm. Only a role that resolves to NONE between the
    /// materialization decision and the restore reaches it, so the role answers NONE only to `restoreVisible` itself.
    @Test
    void restoreVisible_noneArm_underALapsedLease_staysAtTheReplicaBound_thenAValidLeaseRestoresIt() {
        restartedWithThreeRecords(ReplicaSetController.Role.NONE, false);
        assertThat(durable()).as("control: the replayed tail is durable").isEqualTo(2L);
        assertThat(visible()).as("an unresolved role under a lapsed lease restores nothing").isEqualTo(-1L);
        restartedWithThreeRecords(ReplicaSetController.Role.NONE, true);
        assertThat(visible()).as("control: under a valid lease the tail is restored").isEqualTo(2L);
    }

    // ---- the record and activation conjuncts, the bypasses, the fail-closed half-wired manager -----------------------------

    @Test
    void ack_whileTheCommittedRecordNamesAnotherNode_staysAtTheReplicaBound() {
        assertThat(visibleAfterAnAck(() -> remoteOwner.set(Option.some(PEER)))).isEqualTo(-1L);
    }

    @Test
    void ack_withNoCommittedRecord_staysAtTheReplicaBound() {
        assertThat(visibleAfterAnAck(() -> record.set(Option.none()))).isEqualTo(-1L);
    }

    @Test
    void ack_whileNotActivatedForTheHeldOwnership_staysAtTheReplicaBound() {
        assertThat(visibleAfterAnAck(() -> activated.set(false))).isEqualTo(-1L);
    }

    @Test
    void ack_underAValidLease_namingThisNode_activated_exposesTheAckedRecord() {
        assertThat(visibleAfterAnAck(() -> {})).isEqualTo(0L);
    }

    /// Confirmation factor 1 bypasses the owner checks (design §2.2: "CF=1, unchanged").
    @Test
    void confirmationFactorOne_underALapsedLease_isVisibleAtOnce() {
        manager = wired(streamPartitionManager(Long.MAX_VALUE));
        createStream(1);
        leaseValid.set(false);
        publish("e0");
        assertThat(visible()).isEqualTo(0L);
    }

    /// Both the lease and the ownership-record seams unwired: the pre-lease owner rule (unwired fixtures keep their meaning).
    @Test
    void bothSeamsUnwired_keepThePreLeaseOwnerRule() {
        var replication = replicationWithPeer();
        manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        createStream(2);
        publish("e0");
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        assertThat(visible()).isEqualTo(0L);
    }

    /// Half-wired (records wired, lease not): fails closed, as for a lapsed lease (design §2.2 "Half-wired").
    @Test
    void halfWired_recordsWithoutALease_failsClosed() {
        var replication = replicationWithPeer();
        manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        manager.ownershipRecords((_, _) -> record.get());
        createStream(2);
        publish("e0");
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        assertThat(visible()).isEqualTo(-1L);
    }

    // ---- the renewal edge and the announcement (S6) ------------------------------------------------------------------------

    /// Design §2.2 check 3: a renewal edge re-derives `visible` of an owned partition without waiting for the next ack or write.
    @Test
    void renewalEdge_rederivesTheVisiblePositionOfAnOwnedPartition() {
        var replication = replicationWithPeer();
        manager = wired(streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication));
        createStream(2);
        publish("e0");
        leaseValid.set(false);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        assertThat(visible()).as("control: paused under the lapse").isEqualTo(-1L);
        leaseValid.set(true);
        manager.onLeaseRegained();
        assertThat(visible()).isEqualTo(0L);
    }

    /// The renewal edge re-derives only partitions placement names this node OWNER of: a replica's visible stays bounded by
    /// what its owner reported (here nothing), never by the owner rule.
    @Test
    void renewalEdge_leavesAReplicaPartitionAtItsReplicaBound() {
        manager = wired(streamPartitionManager(Long.MAX_VALUE));
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        createStream(2);
        appendRecovered("r0");
        assertThat(durable()).as("control: the replica holds the record durably").isEqualTo(0L);
        manager.onLeaseRegained();
        assertThat(visible()).isEqualTo(-1L);
    }

    /// G8 (CTO ruling): the lapsed-lease fallback never passes the caller's bound. A committed mark outlives the node's replica
    /// life (only `purgePartitionState` removes it), so a node promoted to OWNER can hold a mark above what its peers have
    /// acknowledged; `min(durable, mark)` alone would expose those unacknowledged records under a lapsed lease.
    @Test
    void lapsedLease_staleCommittedMarkFromAReplicaLife_neverPassesTheOwnerBound() {
        var role = new AtomicReference<>(ReplicaSetController.Role.OWNER);

        manager = wired(streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replicationWithPeer()));
        manager.placementRoleSupplier((_, _) -> role.get());
        createStream(2);
        role.set(ReplicaSetController.Role.NONE);
        manager.commitAdvanced(STREAM, PARTITION, 5L, Epoch.ZERO);
        role.set(ReplicaSetController.Role.OWNER);
        leaseValid.set(false);
        publish("e0");
        publish("e1");
        assertThat(durable()).as("control: the owner holds both records durably, below the stale mark").isEqualTo(1L);
        assertThat(visible()).as("no peer acknowledged anything: the fallback stays under the owner bound").isEqualTo(-1L);
    }

    /// F-1a-6: the owner's tick announces nothing (neither the local committed event nor the replica message) while the lease is
    /// not valid, and announces again once it is.
    @Test
    void tick_underALapsedLease_announcesNothing_thenAValidLeaseAnnouncesAgain() throws InterruptedException {
        var committed = new CopyOnWriteArrayList<Long>();
        manager = wired(streamPartitionManager(Long.MAX_VALUE));
        manager.partitionLogEvents(committedInto(committed));
        createStream(2);
        publish("e0");
        awaitFirst(committed);
        committed.clear();
        leaseValid.set(false);
        manager.repeatVisible();
        assertThat(committed).as("a lapsed owner announces nothing").isEmpty();
        leaseValid.set(true);
        manager.repeatVisible();
        assertThat(committed).as("control: under a valid lease the tick announces visible").containsExactly(0L);
    }

    // ---- structural rule ----------------------------------------------------------------------------------------------------

    /// Design §8 (R3-S1): in `StreamPartitionManager`, `advanceVisible(` is called only inside `ownerAdvance` and the REPLICA
    /// arm `exposeReplicaCommitted`, and `seedHead(` only inside `seedRing`. A direct call added anywhere else fails here.
    @Test
    void visibilityWriters_areCalledOnlyFromTheirOneGuardedMethod() throws IOException {
        assertThat(callersOf("advanceVisible(")).containsOnly("ownerAdvance", "exposeReplicaCommitted")
                                                .contains("ownerAdvance", "exposeReplicaCommitted");
        assertThat(callersOf("seedHead(")).containsOnly("seedRing")
                                          .isNotEmpty();
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------------

    private long visibleAfterAnAck(Runnable condition) {
        var replication = replicationWithPeer();
        manager = wired(streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication));
        createStream(2);
        publish("e0");
        condition.run();
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        return visible();
    }

    private static PartitionLogEvents committedInto(List<Long> committed) {
        return new PartitionLogEvents() {
            @Override
            public void committed(String streamName, int partition, long through) {
                committed.add(through);
            }

            @Override
            public void truncated(String streamName, int partition, long keepThrough) {}
        };
    }

    private static void awaitFirst(List<Long> committed) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (committed.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(committed).as("control: the append announced visible once").isNotEmpty();
    }

    private void restartedWithThreeRecords(ReplicaSetController.Role role, boolean leaseOnRestart) {
        Option.option(manager).onPresent(StreamPartitionManager::close);
        clear(walDir);
        leaseValid.set(true);
        manager = wired(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));
        createStream(2);
        publish("e0");
        publish("e1");
        publish("e2");
        manager.close();
        leaseValid.set(leaseOnRestart);
        manager = wired(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));
        manager.placementRoleSupplier((_, _) -> askedByRestoreVisible()
                                                ? role
                                                : ReplicaSetController.Role.OWNER);
        createStream(2);
        assertThat(manager.partitionBuffer(STREAM, PARTITION).isPresent()).as("control: the ring was rebuilt").isTrue();
    }

    private static boolean askedByRestoreVisible() {
        return StackWalker.getInstance()
                          .walk(frames -> frames.anyMatch(frame -> frame.getMethodName()
                                                                        .equals("restoreVisible")));
    }

    private StreamPartitionManager wired(StreamPartitionManager spm) {
        spm.ownerLease(leaseValid::get);
        spm.ownershipRecords((_, _) -> record.get());
        spm.ownerWriteAdmission((_, _) -> remoteOwner.get());
        spm.ownerActivated((_, _) -> activated.get());
        return spm;
    }

    private void createStream(int confirmationFactor) {
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       3,
                                                       confirmationFactor,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail(cause.message()));
    }

    private void publish(String payload) {
        manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
               .onFailure(cause -> fail("publish failed: " + cause.message()));
    }

    private void appendRecovered(String payload) {
        manager.appendRecovered(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
               .onFailure(cause -> fail("recovered append failed: " + cause.message()));
    }

    private long visible() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::visibleOffset).or(Long.MIN_VALUE);
    }

    private long durable() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::durableOffset).or(Long.MIN_VALUE);
    }

    private static ReplicationManager replicationWithPeer() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        return replicationManager(SELF, registry);
    }

    /// The name of the method enclosing every line of the manager's source that contains `call`.
    private static List<String> callersOf(String call) throws IOException {
        var callers = new ArrayList<String>();
        var current = "";

        for (var line : Files.readAllLines(SPM_SOURCE)) {
            var declaration = METHOD.matcher(line);

            if (declaration.find()) {
                current = declaration.group(1);
            }
            if (line.contains(call) && !line.stripLeading().startsWith("///")) {
                callers.add(current);
            }
        }
        return callers;
    }

    private static void clear(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((left, right) -> right.compareTo(left))
                 .filter(path -> !path.equals(dir))
                 .forEach(OwnerAdvanceLeaseTest::delete);
        } catch (IOException e) {
            fail("cannot clear " + dir + ": " + e.getMessage());
        }
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            fail("cannot delete " + path + ": " + e.getMessage());
        }
    }
}
