// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;

/// M-9a0 plus the handler it is sent to: the client's `readRemoteLinearizable` against the REAL [StreamForwardHandler],
/// wired in a loop (the client's transport delivers to the handler, the handler's transport delivers to the client), with
/// no fixture response in between.
///
/// Today the handler does not honour the life of a linearizable read: `linearizable=true` goes `serveRead` ->
/// `LinearizableOwnerServe#serveForwarded`, which takes no epoch, and `onReadForward` answers [Epoch#ZERO]
/// (StreamForwardHandler.java:400), neither the epoch the read was served under nor a validation of the consumer's one.
/// That half belongs to M-9a2 (lane plan stream-mem, M-9a0 is client-only), so this class pins the CURRENT answer in an
/// enabled tripwire and keeps the intended one beside it, disabled.
class StreamForwardLifeReadsHandlerTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final String STREAM = "life-stream";
    private static final int PARTITION = 0;
    private static final Epoch OWNER_EPOCH = Epoch.epoch(4L, 5L, 6L);

    private StreamForwardClient client;

    @BeforeEach
    void setUp() {
        var partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
        partitionManager.publishLocal(STREAM, PARTITION, "e0".getBytes(), 1L);
        var replicaRegistry = ReplicaRegistry.replicaRegistry();
        replicaRegistry.registerReplica(STREAM, PARTITION, OWNER);
        replicaRegistry.updateWatermark(STREAM, PARTITION, OWNER, 0L, ReplicationState.CAUGHT_UP);
        var handler = streamForwardHandler(OWNER,
                                           partitionManager,
                                           AcceptingStreamForwardTransport.accepting((_, message) -> answer(message)),
                                           StreamForwardHandler.DEFAULT_MAX_READ_RESPONSE_BYTES,
                                           StreamReadForwardMetrics.NOOP,
                                           Option.some(ownerServe(partitionManager, replicaRegistry)));

        client = streamForwardClient(SELF,
                                     AcceptingStreamForwardTransport.accepting((_, message) -> handler.onReadForward((ReadForward) message)),
                                     TimeSpan.timeSpan(5).seconds());
    }

    /// TRIPWIRE, enabled: a real owner serves the linearizable life read (one event, success) but answers ZERO, not the epoch
    /// it was served under. When M-9a2 lands this fails: replace it with the assertion below.
    @Test
    void linearizableLifeRead_throughTheRealHandler_servesTheEvent_butAnswersZeroEpochToday() {
        var result = client.readRemoteLinearizable(OWNER, STREAM, PARTITION, 0L, 10, OWNER_EPOCH).await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(served -> {
            assertThat(served.events()).hasSize(1);
            assertThat(served.ownerEpoch()).as("M-9a2 landed: replace this tripwire with the served-epoch assertion")
                                           .isEqualTo(Epoch.ZERO);
        });
    }

    /// The intended contract, disabled until the owner honours the life of a linearizable read: the answer carries the epoch
    /// the read was served under.
    @Disabled("M-9a2: LinearizableOwnerServe/StreamForwardHandler must honour the consumer epoch of a linearizable read and stamp the served epoch (handler answers Epoch.ZERO today, StreamForwardHandler.java:400)")
    @Test
    void linearizableLifeRead_throughTheRealHandler_resolvesWithTheEpochItWasServedUnder() {
        var result = client.readRemoteLinearizable(OWNER, STREAM, PARTITION, 0L, 10, OWNER_EPOCH).await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(served -> {
            assertThat(served.events()).hasSize(1);
            assertThat(served.ownerEpoch()).isEqualTo(OWNER_EPOCH);
        });
    }

    private void answer(StreamForwardMessage message) {
        if (message instanceof ReadForwardResponse response) {
            client.onReadForwardResponse(response);
        }
    }

    private static LinearizableOwnerServe<OffHeapRingBuffer.RawEvent> ownerServe(StreamPartitionManager partitionManager,
                                                                                 ReplicaRegistry replicaRegistry) {
        CommittedStreamOwnerSource committed = (_, _) -> Option.some(new CommittedOwner(OWNER, OWNER_EPOCH));

        return LinearizableOwnerServe.linearizableOwnerServe(OWNER,
                                                             Option.some(replicaRegistry),
                                                             Option.some(committed),
                                                             Option.none(),
                                                             Option.none(),
                                                             (stream, partition, fromOffset, maxEvents) -> partitionManager.readLocal(stream,
                                                                                                                                       partition,
                                                                                                                                       fromOffset,
                                                                                                                                       maxEvents)
                                                                                                                            .async());
    }
}
