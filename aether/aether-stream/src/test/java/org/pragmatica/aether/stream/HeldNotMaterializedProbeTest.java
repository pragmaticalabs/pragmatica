// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;

/// F1a (cloud run 1, `Concurrent_deploy`): a node that HOLDS a partition it has not materialized (paced by
/// `reshuffle_concurrency`) is REACHABLE, and says so — the serving side of the probe, end to end. The real
/// partition manager and the real forward handler answer; the probe's read is the handler's own response, carried
/// as a remote failure travels (its message only), into the real [OwnerPeerReads#replicaWatermark].
///
/// Setup: `reshuffle_concurrency` slots (2) are held by `busy[0]` and `busy[1]`, so `fresh[0]` and `restarted[0]`
/// are held-but-paced. `restarted` has three events in its WAL from a previous run; `fresh` has nothing durable.
class HeldNotMaterializedProbeTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final int PAGE = 16;

    @TempDir
    Path walDir;

    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();
    private StreamPartitionManager manager;
    private StreamForwardHandler handler;
    private final List<ReadForwardResponse> answers = new CopyOnWriteArrayList<>();

    private static StreamConfig config(String name, int partitions) {
        return StreamConfig.streamConfig(name,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L),
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    @BeforeEach
    void startNodeWithBothSlotsBusy() {
        writeThreeEventsInAPreviousRun();
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), LastSealedOffsetSource.none());

        manager.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
        List.of(config("busy", 2), config("fresh", 1), config("restarted", 1), config("elsewhere", 1))
            .forEach(config -> manager.onStreamConfigPut(configPut(config)));
        List.of("busy", "fresh", "restarted").forEach(stream -> roles.put(stream, Role.REPLICA));
        manager.materializePartition("busy", 0).onFailure(_ -> fail("first slot should materialize"));
        manager.materializePartition("busy", 1).onFailure(_ -> fail("second slot should materialize"));
        manager.materializePartition("fresh", 0).onSuccess(_ -> fail("fresh[0] must be paced"));
        manager.materializePartition("restarted", 0).onSuccess(_ -> fail("restarted[0] must be paced"));
        handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                            manager,
                                                            (_, message) -> record(message));
    }

    private void writeThreeEventsInAPreviousRun() {
        var previous = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), LastSealedOffsetSource.none());

        previous.createStream(config("restarted", 1)).onFailure(_ -> fail("previous run should create"));
        for (var i = 0; i < 3; i++) {
            previous.publishLocal("restarted", 0, ("event-" + i).getBytes(), 1000L + i).onFailure(cause -> fail(cause.message()));
        }
        previous.close();
    }

    private void record(Object message) {
        if (message instanceof ReadForwardResponse response) {
            answers.add(response);
        }
    }

    /// The peer's replication-class read, as the prober sees it: the handler's answer, a failure as its message only.
    private OwnerPeerReads.PageRead servedByHandler() {
        return (_, stream, partition, from, max) -> answerTo(readForward(PEER, "corr", stream, partition, from, max, false, true));
    }

    private Promise<StreamForwardClient.ReadForwardResult> answerTo(ReadForward request) {
        handler.onReadForward(request);

        var response = answers.getLast();

        return response.success()
               ? Promise.success(StreamForwardClient.ReadForwardResult.readForwardResult(response.events(), response.truncated()))
               : new StreamForwardError.ReadForwardFailed(response.errorMessage()).promise();
    }

    @Test
    void replicaWatermark_heldPartitionWithNothingDurable_isReachableAtMinusOne() {
        var probe = OwnerPeerReads.replicaWatermark(servedByHandler(), SELF, "fresh", 0, PAGE).await();

        assertThat(probe.isSuccess()).as("a paced holder is reachable: %s", probe).isTrue();
        assertThat(probe.unwrap()).isEqualTo(-1L);
    }

    @Test
    void replicaWatermark_heldPartitionWithWalHistory_reportsItsDurableHead() {
        var probe = OwnerPeerReads.replicaWatermark(servedByHandler(), SELF, "restarted", 0, PAGE).await();

        assertThat(probe.isSuccess()).as("a paced holder is reachable: %s", probe).isTrue();
        assertThat(probe.unwrap()).as("the WAL holds offsets 0..2").isEqualTo(2L);
    }

    @Test
    void replicaWatermark_partitionThisNodeDoesNotHold_staysAFailure() {
        var probe = OwnerPeerReads.replicaWatermark(servedByHandler(), SELF, "elsewhere", 0, PAGE).await();

        assertThat(probe.isFailure()).as("a genuine non-holder is no information: %s", probe).isTrue();
        assertThat(answers.getLast().errorMessage()).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL.message());
    }

    @Test
    void replicaWatermark_materializedPartition_isServedFromItsRing() {
        var probe = OwnerPeerReads.replicaWatermark(servedByHandler(), SELF, "busy", 0, PAGE).await();

        assertThat(probe.unwrap()).isEqualTo(-1L);
        assertThat(answers.getLast().success()).isTrue();
    }

    /// The refinement is for replication-class reads only: a consumer read of the same partition keeps the
    /// `PARTITION_NOT_LOCAL` the read routers forward on.
    @Test
    void consumerRead_ofAHeldUnmaterializedPartition_keepsPartitionNotLocal() {
        handler.onReadForward(readForward(PEER, "corr", "fresh", 0, 0L, PAGE));

        assertThat(answers.getLast().success()).isFalse();
        assertThat(answers.getLast().errorMessage()).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL.message());
    }

    /// A held partition whose sealed tier is past its WAL head (WAL compacted after a seal) reports the
    /// sealed bound -- the durable max -- never the lower WAL head.
    @Test
    void heldPartitionSealedPastItsWal_reportsTheSealedBound() {
        manager.close();
        var sealed = streamPartitionManager(Long.MAX_VALUE,
                                            Option.some(walDir),
                                            (stream, _) -> "restarted".equals(stream) ? 7L : -1L);
        var roles = new ConcurrentHashMap<String, Role>();

        sealed.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
        List.of(config("busy", 2), config("restarted", 1)).forEach(config -> sealed.onStreamConfigPut(configPut(config)));
        List.of("busy", "restarted").forEach(stream -> roles.put(stream, Role.REPLICA));
        sealed.materializePartition("busy", 0).onFailure(_ -> fail("first slot should materialize"));
        sealed.materializePartition("busy", 1).onFailure(_ -> fail("second slot should materialize"));
        sealed.materializePartition("restarted", 0).onSuccess(_ -> fail("restarted[0] must be paced"));

        assertThat(sealed.heldNotMaterializedWatermark("restarted", 0)).isEqualTo(Option.some(7L));
        sealed.close();
    }

    @Test
    void heldNotMaterializedWatermark_reportsOnlyForAHeldPartitionWithoutARing() {
        assertThat(manager.heldNotMaterializedWatermark("fresh", 0)).isEqualTo(Option.some(-1L));
        assertThat(manager.heldNotMaterializedWatermark("restarted", 0)).isEqualTo(Option.some(2L));
        assertThat(manager.heldNotMaterializedWatermark("busy", 0)).as("materialized").isEqualTo(Option.none());
        assertThat(manager.heldNotMaterializedWatermark("elsewhere", 0)).as("not held").isEqualTo(Option.none());
        assertThat(manager.heldNotMaterializedWatermark("fresh", 7)).as("out of range").isEqualTo(Option.none());
        assertThat(manager.heldNotMaterializedWatermark("unknown", 0)).as("no such stream").isEqualTo(Option.none());
    }

    /// F1f: with every slot busy, a replicate append at offset 0 for a partition with NOTHING durable materializes
    /// outside pacing and is applied, so a confirmation_factor 2 publish to a new stream can be acked.
    @Test
    void appendRecovered_atOffsetZeroOfAnEmptyPartition_isNotPacedWhileSlotsAreBusy() {
        var applied = manager.appendRecovered("fresh", 0, 0L, "first".getBytes(), 1000L);

        assertThat(applied.isSuccess()).as("empty partition, nothing to backfill: %s", applied).isTrue();
        assertThat(manager.partitionBuffer("fresh", 0).isPresent()).isTrue();
    }

    /// The other side: a partition that has durable data has a backfill to do, so it stays paced.
    @Test
    void appendRecovered_atOffsetZeroOfAPartitionWithWalHistory_staysPaced() {
        var applied = manager.appendRecovered("restarted", 0, 0L, "first".getBytes(), 1000L);

        assertThat(applied.isFailure()).isTrue();
        applied.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
    }

    /// The held watermark is cached because reading it scans the log. The cache is dropped when the ring is installed:
    /// `restarted[0]` is answered (2, cached), then materialized and appended to (WAL head 3), then released (WAL kept);
    /// the next answer must be the WAL's 3, not the cached 2.
    @Test
    void heldNotMaterializedWatermark_afterRingInstallAndRelease_isNotTheCachedValue() {
        assertThat(manager.heldNotMaterializedWatermark("restarted", 0)).isEqualTo(Option.some(2L));

        manager.replicaCatchupSource((stream, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(Integer.MAX_VALUE,
                                                                                                             true));
        manager.clusterSizeSupplier(() -> 3);
        manager.ownerReleaseGuard((_, _) -> true);
        manager.reconcileReshuffle();
        assertThat(manager.partitionBuffer("restarted", 0).isPresent()).as("drained from the queue").isTrue();
        manager.appendRecovered("restarted", 0, 3L, "fourth".getBytes(), 1003L).onFailure(cause -> fail(cause.message()));

        roles.put("restarted", Role.NONE);
        for (var tick = 0; tick < 4; tick++) {
            manager.reconcileReshuffle();
        }
        assertThat(manager.partitionBuffer("restarted", 0).isPresent()).as("ring released, WAL kept").isFalse();
        roles.put("restarted", Role.REPLICA);

        assertThat(manager.heldNotMaterializedWatermark("restarted", 0)).isEqualTo(Option.some(3L));
    }

    /// The production naming path: the owner is a REGISTERED replica on the peer, so its catch-up probe is served by
    /// `readAppended`, not `readVisible`. The unregistered sender used elsewhere in this class exercises only the
    /// latter.
    @Test
    void catchupRead_fromARegisteredReplica_ofAHeldUnmaterializedPartition_isNamed() {
        var registry = org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry();

        registry.registerReplica("fresh", 0, PEER);
        var registered = streamPartitionManager(Long.MAX_VALUE,
                                                EvictionListener.NOOP,
                                                org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager(SELF, registry),
                                                Option.some(walDir.resolve("registered")),
                                                LastSealedOffsetSource.none());

        try {
            roles.clear();
            registered.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
            List.of(config("busy", 2), config("fresh", 1)).forEach(config -> registered.onStreamConfigPut(configPut(config)));
            roles.put("busy", Role.REPLICA);
            roles.put("fresh", Role.REPLICA);
            registered.materializePartition("busy", 0).onFailure(_ -> fail("first slot should materialize"));
            registered.materializePartition("busy", 1).onFailure(_ -> fail("second slot should materialize"));
            registered.materializePartition("fresh", 0).onSuccess(_ -> fail("fresh[0] must be paced"));
            var registeredHandler = StreamForwardHandler.streamForwardHandler(SELF, registered, (_, message) -> record(message));

            registeredHandler.onReadForward(readForward(PEER, "corr", "fresh", 0, 0L, PAGE, false, true));

            assertThat(answers.getLast().success()).isFalse();
            assertThat(StreamError.PartitionHeldNotMaterialized.watermarkOf(new StreamForwardError.ReadForwardFailed(answers.getLast().errorMessage())))
                .isEqualTo(Option.some(-1L));
        } finally {
            registered.close();
        }
    }

    /// A slow WAL read that started before a ring install and release must not put its pre-install value back after the
    /// drop: `restarted[0]` is read (head 2) and held inside the inspect, the partition is then materialized, appended
    /// to (head 3) and released, and only then does the slow read return. The next answer is the WAL's 3.
    @Test
    void heldNotMaterializedWatermark_slowReadSpanningInstallAndRelease_doesNotCacheItsStaleValue() throws Exception {
        manager.close();
        var inspecting = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var directory = AppendLog.Opener.directory(walDir);
        AppendLog.Opener slow = AppendLog.Opener.opener(directory::open, name -> slowInspect(directory, name, armed, inspecting, proceed));
        var slowManager = StreamPartitionManager.streamPartitionManagerOverLogs(Long.MAX_VALUE, Option.some(slow), LastSealedOffsetSource.none());

        try {
            roles.clear();
            slowManager.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
            List.of(config("restarted", 1)).forEach(config -> slowManager.onStreamConfigPut(configPut(config)));
            roles.put("restarted", Role.REPLICA);
            var read = new java.util.concurrent.atomic.AtomicReference<Option<Long>>();
            var reader = new Thread(() -> read.set(slowManager.heldNotMaterializedWatermark("restarted", 0)));

            reader.start();
            assertThat(inspecting.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("the slow read is inside inspect").isTrue();

            slowManager.replicaCatchupSource((_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(Integer.MAX_VALUE, true));
            slowManager.clusterSizeSupplier(() -> 3);
            slowManager.ownerReleaseGuard((_, _) -> true);
            slowManager.materializePartition("restarted", 0).onFailure(cause -> fail(cause.message()));
            slowManager.appendRecovered("restarted", 0, 3L, "fourth".getBytes(), 1003L).onFailure(cause -> fail(cause.message()));
            roles.put("restarted", Role.NONE);
            for (var tick = 0; tick < 4; tick++) {
                slowManager.reconcileReshuffle();
            }
            assertThat(slowManager.partitionBuffer("restarted", 0).isPresent()).as("ring released, WAL kept").isFalse();
            roles.put("restarted", Role.REPLICA);

            proceed.countDown();
            reader.join(10_000L);

            assertThat(read.get()).as("the slow read itself saw the pre-install head").isEqualTo(Option.some(2L));
            assertThat(slowManager.heldNotMaterializedWatermark("restarted", 0)).isEqualTo(Option.some(3L));
        } finally {
            proceed.countDown();
            slowManager.close();
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static org.pragmatica.lang.Result<AppendLog.LogExtent> slowInspect(AppendLog.Opener directory,
                                                                               String name,
                                                                               java.util.concurrent.atomic.AtomicBoolean armed,
                                                                               java.util.concurrent.CountDownLatch inspecting,
                                                                               java.util.concurrent.CountDownLatch proceed) {
        var extent = directory.inspect(name);

        if (armed.compareAndSet(true, false)) {
            inspecting.countDown();
            try {
                proceed.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return extent;
    }
}
