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
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CommitAdvance;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

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
    private final List<CommitAdvance> announced = new CopyOnWriteArrayList<>();
    private StreamPartitionManager manager;
    private StreamPartitionManager replica;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
        Option.option(replica).onPresent(StreamPartitionManager::close);
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

    /// #3307 (F-a3): an owner holding the OWNER role and a valid lease whose ownership has NOT activated announces nothing,
    /// neither from the append listener (the ack raised its visible to 0 through the replica-life mark) nor from any tick. A
    /// replica that holds the record durably therefore hears no mark, so [StreamPartitionManager#behindCommittedOwner] reads
    /// `true` (no mark announced in its life: the `or(true)` arm) and its GOVERNOR reads go to the owner instead of its own copy.
    @Test
    void notActivatedOwner_announcesNothingOverSeveralTicks_soAReplicaHoldingTheRecordStillForwards() {
        ownerVisibleAtZero(false);
        replicaHoldingOneRecord();
        manager.repeatVisible();
        manager.repeatVisible();
        manager.repeatVisible();
        deliverAnnouncementsToTheReplica();
        assertThat(announced).as("a not-yet-activated owner announces no visible mark").isEmpty();
        assertThat(replica.behindCommittedOwner(STREAM, PARTITION)).as("the replica heard nothing: it forwards").isTrue();
    }

    /// Control for the test above: the same owner, activated, announces its visible mark on the tick, and the same replica then
    /// reads itself caught up (`false`) and serves locally -- the outcome the not-activated owner must not cause.
    @Test
    void activatedOwner_announcesItsVisibleMark_andTheReplicaHoldingTheRecordServesLocally() {
        ownerVisibleAtZero(true);
        replicaHoldingOneRecord();
        manager.repeatVisible();
        deliverAnnouncementsToTheReplica();
        assertThat(announced).as("an activated owner announces visible on the tick")
                             .extracting(CommitAdvance::committedThrough)
                             .contains(0L);
        assertThat(replica.behindCommittedOwner(STREAM, PARTITION)).as("the replica heard the mark: it serves locally").isFalse();
    }

    /// #3307 (F-c1): the empty-log `-1` is a mark too. An owner holding the OWNER role, a valid lease and the record, with an
    /// EMPTY ring and an ownership NOT activated, announces nothing over several ticks.
    @Test
    void notActivatedOwner_withAnEmptyLog_announcesNothingOverSeveralTicks() {
        ownerWithAnEmptyLog(false);
        manager.repeatVisible();
        manager.repeatVisible();
        manager.repeatVisible();
        assertThat(announced).as("a not-yet-activated owner announces no mark, not even the empty log's -1").isEmpty();
    }

    /// Control for the test above, and the never-written partition's path through activation: the same owner, once activated,
    /// announces `-1` on the tick, and an empty replica of the never-written partition stops forwarding -- as before #3307,
    /// only delayed to the activation.
    @Test
    void ownerWithAnEmptyLog_onceActivated_announcesMinusOne_andAnEmptyReplicaStopsForwarding() {
        ownerWithAnEmptyLog(false);
        emptyReplica();
        manager.repeatVisible();
        deliverAnnouncementsToTheReplica();
        assertThat(replica.behindCommittedOwner(STREAM, PARTITION)).as("before activation the replica heard nothing: it forwards")
                                                                   .isTrue();
        activated.set(true);
        manager.repeatVisible();
        deliverAnnouncementsToTheReplica();
        assertThat(announced).as("the activated owner announces the empty log")
                             .extracting(CommitAdvance::committedThrough)
                             .containsOnly(-1L)
                             .isNotEmpty();
        assertThat(replica.behindCommittedOwner(STREAM, PARTITION)).as("the replica heard -1: it answers the never-written partition")
                                                                   .isFalse();
    }

    /// #3307 (F-c1): a replica lagging behind the mark it heard from the previous owner (epoch ZERO, through 0; its own copy
    /// is empty) keeps that mark while the new owner (epoch of [#RECORD]) has not activated: no `-1` arrives to replace it
    /// under the newer epoch ([StreamPartitionManager] `newerMark`), so `behindCommittedOwner` keeps reading `true`.
    @Test
    void laggingReplica_keepsItsMark_whileTheNewOwnerWithAnEmptyLogIsNotActivated() {
        ownerWithAnEmptyLog(false);
        laggingReplica();
        manager.repeatVisible();
        manager.repeatVisible();
        manager.repeatVisible();
        deliverAnnouncementsToTheReplica();
        assertThat(announced).as("a not-yet-activated owner announces nothing").isEmpty();
        assertThat(replica.behindCommittedOwner(STREAM, PARTITION)).as("the lagging replica still forwards").isTrue();
    }

    /// Control for the test above: the same owner, activated, announces `-1` under its newer epoch, which replaces the lagging
    /// replica's mark -- the outcome a not-activated owner must not cause, so the arms above end in different states.
    @Test
    void laggingReplica_markReplacedByMinusOne_onceTheNewOwnerIsActivated() {
        ownerWithAnEmptyLog(true);
        laggingReplica();
        manager.repeatVisible();
        deliverAnnouncementsToTheReplica();
        assertThat(announced).as("the activated owner announces the empty log")
                             .extracting(CommitAdvance::committedThrough)
                             .containsOnly(-1L)
                             .isNotEmpty();
        assertThat(replica.behindCommittedOwner(STREAM, PARTITION)).as("the -1 under the newer epoch replaced the mark").isFalse();
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

    /// #3307 (F-c1): an owner's mark leaves the manager only through `sendMark`, and `sendMark` is called only from the one
    /// gated `announceMark`. Read by method body (declaration line to the closing `    }`), not through [#callersOf], whose
    /// declaration pattern also matches a bare indented call such as `sendMark(...)`. A second announce site fails here.
    @Test
    void announcements_leaveOnlyThroughTheOneGatedMethod() throws IOException {
        var source = codeLines(Files.readAllLines(SPM_SOURCE));

        assertThat(count(source, ".announceCommitted(")).as("one send of the announce message in the manager").isEqualTo(1);
        assertThat(count(bodyOf(source, "    private void sendMark("), ".announceCommitted(")).as("... inside sendMark").isEqualTo(1);
        assertThat(count(source, "sendMark(")).as("sendMark: its declaration and one call").isEqualTo(2);
        assertThat(count(bodyOf(source, "    private void announceMark("), "sendMark(")).as("... the call inside announceMark")
                                                                                  .isEqualTo(1);
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------------

    /// An owner (CF 2, lease valid, record naming it) whose visible reaches 0 with or without activation: a mark of 5 from its
    /// replica life bounds the not-activated fallback `min(bound, durable, mark)` above the acked record, so both arms expose it.
    private void ownerVisibleAtZero(boolean ownerActivated) {
        var role = new AtomicReference<>(ReplicaSetController.Role.OWNER);
        var replication = replicationWithPeer(this::recordAnnouncement);

        manager = wired(streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication));
        manager.placementRoleSupplier((_, _) -> role.get());
        createStream(2);
        role.set(ReplicaSetController.Role.NONE);
        manager.commitAdvanced(STREAM, PARTITION, 5L, Epoch.ZERO);
        role.set(ReplicaSetController.Role.OWNER);
        publish("e0");
        activated.set(ownerActivated);
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
        assertThat(visible()).as("control: the owner's visible reached the acked record").isEqualTo(0L);
    }

    private void replicaHoldingOneRecord() {
        replica = streamPartitionManager(Long.MAX_VALUE);
        replica.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        replica.createStream(streamConfig(2))
               .onFailure(cause -> fail(cause.message()));
        replica.appendRecovered(STREAM, PARTITION, "r0".getBytes(UTF_8), 1L)
               .onFailure(cause -> fail("recovered append failed: " + cause.message()));
        assertThat(replica.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::durableOffset)
                          .or(Long.MIN_VALUE)).as("control: the replica holds the record durably")
                                              .isEqualTo(0L);
    }

    /// An owner (CF 2, lease valid, record naming it, announcing under the record's epoch) whose ring is materialized and EMPTY.
    private void ownerWithAnEmptyLog(boolean ownerActivated) {
        manager = wired(streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replicationWithPeer(this::recordAnnouncement)));
        manager.ownerEpochSource((_, _) -> RECORD.ownerEpoch());
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        activated.set(ownerActivated);
        createStream(2);
        assertThat(manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::durableOffset)
                          .or(Long.MIN_VALUE)).as("control: the owner's ring exists and is empty")
                                              .isEqualTo(-1L);
    }

    private void emptyReplica() {
        replica = streamPartitionManager(Long.MAX_VALUE);
        replica.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        replica.createStream(streamConfig(2))
               .onFailure(cause -> fail(cause.message()));
    }

    /// An empty replica that heard the previous owner's mark (epoch ZERO, through 0): it lags, so it forwards.
    private void laggingReplica() {
        emptyReplica();
        replica.commitAdvanced(STREAM, PARTITION, 0L, Epoch.ZERO);
        assertThat(replica.behindCommittedOwner(STREAM, PARTITION)).as("control: the replica lags the mark it heard").isTrue();
        assertThat(RECORD.ownerEpoch().isStrictlyAfter(Epoch.ZERO)).as("control: the new owner's epoch is newer").isTrue();
    }

    private Promise<WriteOutcome> recordAnnouncement(NodeId target, ReplicationMessage message) {
        if (message instanceof CommitAdvance advance) {
            announced.add(advance);
        }
        return Promise.success(new WriteOutcome.Sent(target));
    }

    private void deliverAnnouncementsToTheReplica() {
        announced.forEach(advance -> replica.commitAdvanced(advance.streamName(),
                                                            advance.partition(),
                                                            advance.committedThrough(),
                                                            advance.ownerEpoch()));
    }

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
        manager.createStream(streamConfig(confirmationFactor))
               .onFailure(cause -> fail(cause.message()));
    }

    private static StreamConfig streamConfig(int confirmationFactor) {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         3,
                                         confirmationFactor,
                                         StreamCompression.NONE,
                                         Option.none());
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
        return replicationWithPeer(ReplicationTransport.NOOP);
    }

    private static ReplicationManager replicationWithPeer(ReplicationTransport transport) {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        return replicationManager(SELF, registry, transport);
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

    private static List<String> codeLines(List<String> lines) {
        return lines.stream()
                    .filter(line -> !line.stripLeading().startsWith("///"))
                    .toList();
    }

    private static long count(List<String> lines, String needle) {
        return lines.stream()
                    .filter(line -> line.contains(needle))
                    .count();
    }

    /// The lines of the top-level member whose declaration starts with `declaration`, through its closing `    }`; empty if absent.
    private static List<String> bodyOf(List<String> lines, String declaration) {
        return lines.stream()
                    .dropWhile(line -> !line.startsWith(declaration))
                    .takeWhile(line -> !line.equals("    }"))
                    .toList();
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
