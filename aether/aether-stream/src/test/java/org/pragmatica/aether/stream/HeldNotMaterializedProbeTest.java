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

        var roles = new ConcurrentHashMap<String, Role>();

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

    @Test
    void heldNotMaterializedWatermark_reportsOnlyForAHeldPartitionWithoutARing() {
        assertThat(manager.heldNotMaterializedWatermark("fresh", 0)).isEqualTo(Option.some(-1L));
        assertThat(manager.heldNotMaterializedWatermark("restarted", 0)).isEqualTo(Option.some(2L));
        assertThat(manager.heldNotMaterializedWatermark("busy", 0)).as("materialized").isEqualTo(Option.none());
        assertThat(manager.heldNotMaterializedWatermark("elsewhere", 0)).as("not held").isEqualTo(Option.none());
        assertThat(manager.heldNotMaterializedWatermark("fresh", 7)).as("out of range").isEqualTo(Option.none());
        assertThat(manager.heldNotMaterializedWatermark("unknown", 0)).as("no such stream").isEqualTo(Option.none());
    }
}
