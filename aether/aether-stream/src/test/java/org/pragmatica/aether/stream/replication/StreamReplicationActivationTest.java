// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.PartitionedStreamAccess;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// A6 end-to-end activation: owner-routed publish + live DefaultReplicationManager + replica-side
/// receive/apply. Models two nodes (owner + replica) each with their own StreamPartitionManager; the
/// owner's replication transport loops ReplicateEvents into the replica's ReplicationReceiveHandler,
/// which lands them via appendRecovered (non-replicating, offset-preserving).
class StreamReplicationActivationTest {
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final NodeId REPLICA = new NodeId("replica-node");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    private StreamPartitionManager ownerManager;
    private StreamPartitionManager replicaManager;
    private ReplicaRegistry ownerRegistry;
    private AtomicInteger replicateSendCount;
    private List<ReplicationMessage> acksToOwner;

    @BeforeEach
    void setUp() {
        replicateSendCount = new AtomicInteger(0);
        acksToOwner = new ArrayList<>();

        // Replica side: a manager whose appendRecovered the receive handler drives. Its acks are
        // captured (transport back to the owner).
        replicaManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        replicaManager.createStream(StreamConfig.streamConfig(STREAM));
        ReplicationTransport ackTransport = (target, message) -> acksToOwner.add(message);
        var receiveHandler = replicationReceiveHandler(REPLICA, replicaManager::appendRecovered, ackTransport);

        // Owner side: real ReplicationManager whose transport delivers ReplicateEvents straight into
        // the replica's receive handler (loopback). The send is counted to prove replicate-once.
        ownerRegistry = replicaRegistry();
        ownerRegistry.registerReplica(STREAM, PARTITION, REPLICA);
        ReplicationTransport loopback = (target, message) -> {
            replicateSendCount.incrementAndGet();
            if (message instanceof ReplicationMessage.ReplicateEvents events) {
                receiveHandler.onReplicateEvents(events);
            }
        };
        var ownerReplication = replicationManager(OWNER, ownerRegistry, loopback);
        ownerManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, ownerReplication);
        ownerManager.createStream(StreamConfig.streamConfig(STREAM));
    }

    @AfterEach
    void tearDown() {
        ownerManager.close();
        replicaManager.close();
    }

    private PartitionedStreamAccess<byte[]> ownerAccess(Option<Fn0<Option<NodeId>>> ownerResolver,
                                                        Option<StreamForwardClient> forwardClient) {
        Option<Function<Integer, Option<NodeId>>> noHrwResolver = Option.none();
        return PartitionedStreamAccess.streamAccess(ownerManager,
                                                    BytePassthrough.SER,
                                                    BytePassthrough.DESER,
                                                    STREAM,
                                                    1,
                                                    Option.none(),
                                                    forwardClient,
                                                    OWNER,
                                                    ownerResolver,
                                                    noHrwResolver);
    }

    @Test
    void ownerPublish_replicatesToReplicaPartition_offsetPreserving() {
        var access = ownerAccess(resolver(OWNER), Option.none());

        var off0 = access.publish("e0".getBytes()).await();
        var off1 = access.publish("e1".getBytes()).await();

        assertThat(off0.isSuccess()).isTrue();
        assertThat(off1.isSuccess()).isTrue();
        off0.onSuccess(o -> assertThat(o).isEqualTo(0L));
        off1.onSuccess(o -> assertThat(o).isEqualTo(1L));

        // Read the REPLICA's partition directly — replicated events landed offset-preserving.
        var replicaEvents = replicaManager.readLocal(STREAM, PARTITION, 0L, 10).unwrap();
        assertThat(replicaEvents).hasSize(2);
        assertThat(replicaEvents.get(0).offset()).isEqualTo(0L);
        assertThat(replicaEvents.get(0).data()).isEqualTo("e0".getBytes());
        assertThat(replicaEvents.get(1).offset()).isEqualTo(1L);
        assertThat(replicaEvents.get(1).data()).isEqualTo("e1".getBytes());
    }

    @Test
    void replicatedEvent_doesNotReReplicate_oneSendPerPublish() {
        var access = ownerAccess(resolver(OWNER), Option.none());

        access.publish("only".getBytes()).await();

        // Exactly one ReplicateEvents send for the single owner publish. The replica applied via
        // appendRecovered (non-replicating) so there is no second send — no replicate→apply→replicate
        // loop.
        assertThat(replicateSendCount.get()).isEqualTo(1);
        // The replica acked the highest applied offset back to the owner.
        assertThat(acksToOwner).hasSize(1);
        assertThat(acksToOwner.getFirst()).isInstanceOf(ReplicationMessage.ReplicateAck.class);
        var ack = (ReplicationMessage.ReplicateAck) acksToOwner.getFirst();
        assertThat(ack.replicaId()).isEqualTo(REPLICA);
        assertThat(ack.confirmedOffset()).isEqualTo(0L);
    }

    @Test
    void nonOwnerPublish_forwardsToOwner() {
        var forwardClient = new RecordingForwardClient();
        // Self is OWNER constant for the access object, but the resolver names REPLICA as the owner,
        // so self != owner → write-forward.
        Option<Function<Integer, Option<NodeId>>> noHrwResolver = Option.none();
        var access = PartitionedStreamAccess.streamAccess(ownerManager,
                                                          BytePassthrough.SER,
                                                          BytePassthrough.DESER,
                                                          STREAM,
                                                          1,
                                                          Option.none(),
                                                          Option.some(forwardClient),
                                                          OWNER,
                                                          resolver(REPLICA),
                                                          noHrwResolver);

        var result = access.publish("forwarded".getBytes()).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(forwardClient.publishes).hasSize(1);
        assertThat(forwardClient.publishes.getFirst().target()).isEqualTo(REPLICA);
        // The owner did NOT write locally — the publish went to the owner via the forward client.
        var localOnOwner = ownerManager.readLocal(STREAM, PARTITION, 0L, 10).unwrap();
        assertThat(localOnOwner).isEmpty();
    }

    @Test
    void bootstrapNoOwner_failSoftLocalPublish() {
        // Resolver returns none() (no placement known yet) → fail-soft: publish lands locally on the
        // owner manager rather than failing.
        var access = ownerAccess(Option.some(Option::none), Option.none());

        var result = access.publish("bootstrap".getBytes()).await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(o -> assertThat(o).isEqualTo(0L));
        var local = ownerManager.readLocal(STREAM, PARTITION, 0L, 10).unwrap();
        assertThat(local).hasSize(1);
        assertThat(local.getFirst().data()).isEqualTo("bootstrap".getBytes());
    }

    @Test
    void noResolver_publishesLocally() {
        // No owner-resolver wired at all → local publish (legacy behaviour preserved).
        var access = ownerAccess(Option.none(), Option.none());

        var result = access.publish("legacy".getBytes()).await();

        assertThat(result.isSuccess()).isTrue();
        var local = ownerManager.readLocal(STREAM, PARTITION, 0L, 10).unwrap();
        assertThat(local).hasSize(1);
    }

    // #47: the partition-aware HRW resolver names self (OWNER) → publish lands locally + replicates.
    @Test
    void hrwResolverNamesSelf_publishesLocally() {
        var access = hrwAccess(hrwResolver(OWNER), Option.none());

        var result = access.publish("self-owned".getBytes()).await();

        assertThat(result.isSuccess()).isTrue();
        var local = ownerManager.readLocal(STREAM, PARTITION, 0L, 10).unwrap();
        assertThat(local).hasSize(1);
        assertThat(local.getFirst().data()).isEqualTo("self-owned".getBytes());
    }

    // #47: the partition-aware HRW resolver names a remote node → write-forward to the HRW owner.
    @Test
    void hrwResolverNamesRemote_forwardsToHrwOwner() {
        var forwardClient = new RecordingForwardClient();
        var access = hrwAccess(hrwResolver(REPLICA), Option.some(forwardClient));

        var result = access.publish("hrw-forwarded".getBytes()).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(forwardClient.publishes).hasSize(1);
        assertThat(forwardClient.publishes.getFirst().target()).isEqualTo(REPLICA);
        // The owner did NOT write locally — the publish was forwarded to the HRW owner.
        var local = ownerManager.readLocal(STREAM, PARTITION, 0L, 10).unwrap();
        assertThat(local).isEmpty();
    }

    // #47: HRW resolver undetermined (none()) → fail-soft local publish, same as the arg-less path.
    @Test
    void hrwResolverUndetermined_failSoftLocalPublish() {
        var access = hrwAccess(_ -> Option.none(), Option.none());

        var result = access.publish("hrw-bootstrap".getBytes()).await();

        assertThat(result.isSuccess()).isTrue();
        var local = ownerManager.readLocal(STREAM, PARTITION, 0L, 10).unwrap();
        assertThat(local).hasSize(1);
    }

    private PartitionedStreamAccess<byte[]> hrwAccess(Function<Integer, Option<NodeId>> partitionOwnerResolver,
                                                      Option<StreamForwardClient> forwardClient) {
        return PartitionedStreamAccess.streamAccess(ownerManager,
                                                    BytePassthrough.SER,
                                                    BytePassthrough.DESER,
                                                    STREAM,
                                                    1,
                                                    Option.none(),
                                                    forwardClient,
                                                    OWNER,
                                                    Option.none(),
                                                    Option.some(partitionOwnerResolver));
    }

    private static Function<Integer, Option<NodeId>> hrwResolver(NodeId owner) {
        return _ -> Option.some(owner);
    }

    private static Option<Fn0<Option<NodeId>>> resolver(NodeId owner) {
        return Option.some(() -> Option.some(owner));
    }

    /// Byte-passthrough codec so a PartitionedStreamAccess<byte[]> round-trips payloads verbatim.
    private static final class BytePassthrough implements Serializer, Deserializer {
        static final BytePassthrough INSTANCE = new BytePassthrough();
        static final Serializer SER = INSTANCE;
        static final Deserializer DESER = INSTANCE;

        @Override @SuppressWarnings("unchecked") public <T> byte[] encode(T object) {
            return (byte[]) object;
        }

        @Override @SuppressWarnings("unchecked") public <T> T decode(byte[] bytes) {
            return (T) bytes;
        }

        @Override public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("byte passthrough uses encode/decode");
        }

        @Override public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("byte passthrough uses encode/decode");
        }
    }

    /// Records publishRemote calls; succeeds with a synthetic offset.
    static final class RecordingForwardClient implements StreamForwardClient {
        final List<PublishCall> publishes = new ArrayList<>();

        @Override public Promise<Long> publishRemote(NodeId governorId,
                                                     String streamName,
                                                     int partition,
                                                     byte[] payload,
                                                     long timestamp) {
            publishes.add(new PublishCall(governorId, streamName, partition));
            return Promise.success(0L);
        }

        @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                               String streamName,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
            return Promise.success(new ReadForwardResult(List.<RawEventDto>of(), false));
        }

        @Override @SuppressWarnings("JBCT-RET-01") public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override @SuppressWarnings("JBCT-RET-01") public void onReadForwardResponse(ReadForwardResponse response) {}

        record PublishCall(NodeId target, String streamName, int partition) {}
    }
}
