// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

/// #2201 PR-2a, the second named exception (know `e0035a56ef`): a stream removed and re-created under the same name must not
/// inherit the quarantine of the life it replaces. The removal deletes the old life's records, so a divergence recorded against
/// them describes nothing the new life holds; a leftover marker fences the new copy's fresh log, and the backfill then refuses it
/// for ever (no committed owner here, so no repair can lift it either) and the replica never reaches CAUGHT_UP.
///
/// The quarantine is seeded through the real divergence path: the replica is offered a different record at an offset it already
/// holds, and the ring's ordered section records it ([StreamPartitionManager#quarantinedAt]). RED at `15ac4ef4da` on the
/// config-remove and reaper arms, which skip `forgetQuarantine`; the destroy arm is the CONTROL, green at every commit, because
/// destroy already clears it.
class RecreatedStreamReachesCaughtUpTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
    private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
    private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
    private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
    private static final NodeId REPLICA = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();
    private static final int OWNER_RECORDS = 5;

    private ReplicaRegistry registry;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        manager = streamPartitionManager(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void onStreamConfigRemove_recreatedStream_isNotQuarantined_andReachesCaughtUp() {
        quarantinedRemovedAndRecreated(_ -> manager.onStreamConfigRemove(removal()));
    }

    @Test
    void reapIdleStreams_recreatedStream_isNotQuarantined_andReachesCaughtUp() {
        quarantinedRemovedAndRecreated(_ -> reap());
    }

    /// CONTROL, green by design: destroy has cleared the quarantine since #1505 F2.
    @Test
    void destroyStream_recreatedStream_isNotQuarantined_andReachesCaughtUp() {
        quarantinedRemovedAndRecreated(_ -> manager.destroyStream(STREAM).onFailure(cause -> fail(cause.message())));
    }

    private void quarantinedRemovedAndRecreated(Consumer<Unit> removal) {
        create();
        for (var offset = 0; offset < 10; offset++) {
            manager.appendRecovered(STREAM, PARTITION, ("c" + offset).getBytes(UTF_8), 1000L + offset)
                   .onFailure(cause -> fail(cause.message()));
        }
        manager.appendRecovered(STREAM, PARTITION, 5, "different".getBytes(UTF_8), 1005L, Epoch.ZERO);
        assertThat(manager.quarantinedAt(STREAM, PARTITION).or(-1L)).as("fixture: the divergence quarantined offset 5").isEqualTo(5L);

        removal.accept(Unit.unit());
        assertThat(manager.streamInfo(STREAM).isEmpty()).as("fixture: the stream was removed").isTrue();
        create();

        assertThat(manager.quarantinedAt(STREAM, PARTITION).isEmpty()).as("the re-created stream inherits no quarantine").isTrue();
        backfill();
        assertThat(selfState()).as("the re-created replica reaches CAUGHT_UP").isEqualTo(ReplicationState.CAUGHT_UP);
    }

    private void create() {
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    /// The reaper takes an expired, idle stream whose rings hold nothing: the age tick hands the (old-stamped) records over first.
    private void reap() {
        manager.evictRingsByAge();
        var deadline = System.nanoTime() + 5_000_000L;

        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(manager.reapIdleStreams()).as("fixture: the idle, emptied stream is reaped").isEqualTo(1);
    }

    private void backfill() {
        registry.registerReplica(STREAM, PARTITION, REPLICA);
        SelfWatermark local = (stream, partition) -> manager.partitionInfo(stream, partition)
                                                            .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                            .or(-1L);
        ReplicaWatermarkProbe probe = (_, _, _) -> Promise.success(OWNER_RECORDS - 1L);
        CommittedStreamOwnerSource noCommittedOwner = (_, _) -> Option.none();
        ReplicationTransport toOwner = (_, _) -> {};
        var orchestrator = partitionBackfill(registry,
                                             manager.alignedRecovery(),
                                             new Owner(),
                                             toOwner,
                                             probe,
                                             local,
                                             REPLICA,
                                             TimeSpan.timeSpan(10).seconds(),
                                             () -> MEMBERS,
                                             noCommittedOwner,
                                             manager::syncReplicated,
                                             manager.quarantineView());

        orchestrator.backfill(STREAM, PARTITION).await();
    }

    private ReplicationState selfState() {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(REPLICA))
                       .findFirst()
                       .map(ReplicaDescriptor::state)
                       .orElseThrow();
    }

    /// A `time` retention with a 1 ms age: old records age out on the tick, and an idle stream expires at once.
    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 1).withDeclared(RetentionDimensions.AGE),
                                         "earliest");
    }

    private static ValueRemove<StreamConfigKey, StreamConfigValue> removal() {
        return new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty());
    }

    /// The owner holds `c0`..`c4`.
    private static final class Owner implements CatchupTransport {
        @Override
        public Promise<ReplicationMessage.CatchupResponse> requestCatchup(NodeId target, ReplicationMessage.CatchupRequest request) {
            var payloads = new ArrayList<byte[]>();
            var stamps = new ArrayList<Long>();

            for (var offset = request.fromOffset(); offset < OWNER_RECORDS; offset++) {
                payloads.add(("c" + offset).getBytes(UTF_8));
                stamps.add(1000L + offset);
            }

            var to = payloads.isEmpty()
                     ? request.fromOffset() - 1
                     : OWNER_RECORDS - 1L;

            return Promise.success(catchupResponse(target,
                                                   request.streamName(),
                                                   request.partition(),
                                                   request.fromOffset(),
                                                   to,
                                                   payloads,
                                                   stamps,
                                                   List.of()));
        }
    }
}
