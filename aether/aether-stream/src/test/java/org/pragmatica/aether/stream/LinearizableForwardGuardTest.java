// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;


/// #345 item 1e-a — the forward-guard fix. A forwarded `LINEARIZABLE`-class read
/// (`ReadForward.linearizable() == true`) MUST re-run the SAME owner-side serve pipeline the local
/// path runs — committed-owner check + epoch fence + catch-up gate — instead of the former unguarded
/// `readLocal`. These tests drive both the shared pipeline directly ({@link LinearizableOwnerServe#serveForwarded})
/// and the {@link StreamForwardHandler} dispatch, proving a forwarded linearizable read to a deposed
/// or not-caught-up owner is rejected rather than served stale, while a plain (non-linearizable)
/// forward still reads local.
class LinearizableForwardGuardTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OTHER = new NodeId("other-node");
    private static final NodeId REQUESTER = new NodeId("requester-node");
    private static final String STREAM = "fwd-guard-stream";
    private static final int PARTITION = 0;
    private static final String CORRELATION_ID = "corr-fwd";

    private StreamPartitionManager partitionManager;
    private ReplicaRegistry replicaRegistry;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
        replicaRegistry = ReplicaRegistry.replicaRegistry();
    }

    @Nested
    class SharedPipeline {
        /// A forwarded linearizable read that landed on a DEPOSED owner (committed epoch below the
        /// partition high-water) is rejected StaleEpochRead — NOT served stale.
        @Test
        void serveForwarded_rejectsStaleEpochRead_whenOwnerDeposed() {
            publish("e0");
            markSelfCaughtUp(0L);
            var highWater = highWaterAt(Epoch.epoch(5, 0));
            var ownerServe = ownerServe(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater));

            ownerServe.serveForwarded(STREAM, PARTITION, 0, 10)
                      .await()
                      .onSuccess(events -> Assertions.fail("expected StaleEpochRead, got " + events.size() + " events"))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StaleEpochRead.class));
        }

        /// A forwarded linearizable read that landed on a still-SYNCING (not-caught-up) owner is
        /// rejected OwnerCatchupPending.
        @Test
        void serveForwarded_rejectsOwnerCatchupPending_whenOwnerNotCaughtUp() {
            publish("e0");
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 0L, ReplicationState.SYNCING);
            var ownerServe = ownerServe(committedOwner(SELF, Epoch.ZERO), Option.none());

            ownerServe.serveForwarded(STREAM, PARTITION, 0, 10)
                      .await()
                      .onSuccess(events -> Assertions.fail("expected OwnerCatchupPending, got " + events.size() + " events"))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerCatchupPending.class));
        }

        /// A forwarded linearizable read that landed on a node that is NOT the committed owner (a
        /// routing race) is rejected NotCurrentOwner so the client re-resolves.
        @Test
        void serveForwarded_rejectsNotCurrentOwner_whenSelfIsNotCommittedOwner() {
            publish("e0");
            markSelfCaughtUp(0L);
            var ownerServe = ownerServe(committedOwner(OTHER, Epoch.ZERO), Option.none());

            ownerServe.serveForwarded(STREAM, PARTITION, 0, 10)
                      .await()
                      .onSuccess(events -> Assertions.fail("expected NotCurrentOwner, got " + events.size() + " events"))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.NotCurrentOwner.class));
        }

        /// The current, caught-up committed owner serves the forwarded read — the guards are not a
        /// blanket reject.
        @Test
        void serveForwarded_serves_whenCurrentCaughtUpOwner() {
            publish("e0", "e1");
            markSelfCaughtUp(1L);
            var ownerServe = ownerServe(committedOwner(SELF, Epoch.ZERO), Option.none());

            ownerServe.serveForwarded(STREAM, PARTITION, 0, 10)
                      .await()
                      .onFailure(LinearizableForwardGuardTest::failUnexpected)
                      .onSuccess(events -> assertThat(events).hasSize(2));
        }
    }

    @Nested
    class HandlerDispatch {
        /// The handler routes a LINEARIZABLE forward through the owner-serve pipeline: a deposed owner
        /// is answered with a FAILURE response (StaleEpochRead), never stale events.
        @Test
        void onReadForward_linearizable_rejectsStaleEpochRead_whenOwnerDeposed() {
            publish("e0");
            markSelfCaughtUp(0L);
            var highWater = highWaterAt(Epoch.epoch(5, 0));
            var sent = new ArrayList<StreamForwardMessage>();
            var handler = handler(sent, ownerServe(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater)));

            handler.onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, 0L, 10, true));

            assertThat(sent).hasSize(1);
            var response = (ReadForwardResponse) sent.getFirst();
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("Stale-epoch linearizable read");
        }

        /// A plain (non-linearizable) forward still reads local and succeeds — the fix is scoped to the
        /// LINEARIZABLE class.
        @Test
        void onReadForward_nonLinearizable_readsLocalAndSucceeds() {
            publish("e0");
            markSelfCaughtUp(0L);
            var highWater = highWaterAt(Epoch.epoch(5, 0));
            var sent = new ArrayList<StreamForwardMessage>();
            var handler = handler(sent, ownerServe(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater)));

            handler.onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, 0L, 10, false));

            assertThat(sent).hasSize(1);
            var response = (ReadForwardResponse) sent.getFirst();
            assertThat(response.success()).isTrue();
            assertThat(response.events()).hasSize(1);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void publish(String... payloads) {
        for (var payload : payloads) {
            partitionManager.publishLocal(STREAM, PARTITION, payload.getBytes(), 1L);
        }
    }

    private void markSelfCaughtUp(long confirmedOffset) {
        replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
        replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, confirmedOffset, ReplicationState.CAUGHT_UP);
    }

    private LinearizableOwnerServe<OffHeapRingBuffer.RawEvent> ownerServe(Option<CommittedStreamOwnerSource> committedOwnerSource,
                                                                          Option<OwnershipEpochHighWater> highWater) {
        return LinearizableOwnerServe.linearizableOwnerServe(SELF,
                                                             Option.some(replicaRegistry),
                                                             committedOwnerSource,
                                                             highWater,
                                                             Option.none(),
                                                             (stream, partition, fromOffset, maxEvents) -> partitionManager.readLocal(stream,
                                                                                                                                       partition,
                                                                                                                                       fromOffset,
                                                                                                                                       maxEvents)
                                                                                                                            .async());
    }

    private StreamForwardHandler handler(List<StreamForwardMessage> sent,
                                         LinearizableOwnerServe<OffHeapRingBuffer.RawEvent> ownerServe) {
        StreamForwardTransport transport = (target, message) -> sent.add(message);

        return streamForwardHandler(SELF,
                                    partitionManager,
                                    transport,
                                    StreamForwardHandler.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                    StreamReadForwardMetrics.NOOP,
                                    Option.some(ownerServe));
    }

    private static Option<CommittedStreamOwnerSource> committedOwner(NodeId owner, Epoch epoch) {
        CommittedStreamOwnerSource source = (_, _) -> Option.some(new CommittedOwner(owner, epoch));

        return Option.some(source);
    }

    private static OwnershipEpochHighWater highWaterAt(Epoch epoch) {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());

        highWater.advance(OwnershipDomain.streamPartition(STREAM, PARTITION), epoch);

        return highWater;
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static void failUnexpected(Cause cause) {
        Assertions.fail("unexpected failure: " + cause.message());
    }
}
