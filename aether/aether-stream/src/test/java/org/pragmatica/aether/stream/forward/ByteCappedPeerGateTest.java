// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.OwnerPeerReads;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1431, adapted from v-str's probes K and L (verification of #1908): the owner gate (real OwnerActivation) probing a
/// peer through the production probe (OwnerPeerReads.appendedWatermark over StreamForwardClient::readRemoteCatchup),
/// answered by the real handler with a byte cap of 100 bytes. The candidate holds 0..3. The catch-up is recorded, not
/// pulled.
class ByteCappedPeerGateTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final NodeId CANDIDATE = NodeId.randomNodeId();
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long CANDIDATE_HEAD = 3L;
    private final java.util.concurrent.atomic.AtomicLong local = new java.util.concurrent.atomic.AtomicLong(CANDIDATE_HEAD);

    private StorageInstance peerStorage;
    private StreamPartitionManager peer;
    private StreamForwardHandler handler;
    private StreamForwardClient client;
    private final List<String> catchUps = new CopyOnWriteArrayList<>();
    private final List<OwnerActivation.ActivationBlock> alarms = new CopyOnWriteArrayList<>();

    private void wire(long capBytes) {
        peerStorage = StorageInstance.storageInstance("peer", List.of(MemoryTier.memoryTier(ONE_GB)));
        ReplicaRegistry registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, PEER);
        registry.registerReplica(STREAM, PARTITION, CANDIDATE);
        ReplicationManager replication = replicationManager(PEER, registry);
        peer = streamPartitionManager(Long.MAX_VALUE,
                                      segmentSealer(storageSegmentSink(peerStorage, new SegmentIndex())),
                                      replication);
        handler = streamForwardHandler(PEER,
                                       peer,
                                       (_, message) -> client.onReadForwardResponse((ReadForwardResponse) message),
                                       capBytes,
                                       StreamReadForwardMetrics.NOOP);
        client = streamForwardClient(CANDIDATE, (_, message) -> handler.onReadForward((ReadForward) message));
        peer.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        peer.close();
        peerStorage.shutdown();
    }

    private void publish(int count, int size) {
        for (var i = 0; i < count; i++) {
            peer.publishLocal(STREAM, PARTITION, "e".repeat(size).getBytes(UTF_8), 1L)
                .onFailure(cause -> fail("publish failed: " + cause.message()));
        }
    }

    private OwnerActivation gate() {
        return gateWith((target, stream, partition) -> OwnerPeerReads.appendedWatermark(client::readRemoteCatchup,
                                                                                        target,
                                                                                        stream,
                                                                                        partition,
                                                                                        1024));
    }

    private OwnerActivation gateWith(org.pragmatica.aether.stream.replication.ReplicaWatermarkProbe probe) {
        OwnerActivation.RecordRange ranges = (node, stream, partition, from, to) ->
            OwnerPeerReads.appendedRange(OwnerPeerReads.localPages(peer, Option.none()), node, stream, partition, from, to, 1024);
        return OwnerActivation.ownerActivation(CANDIDATE,
                                               (_, _) -> Option.none(),
                                               (_, _) -> true,
                                               Option.some((_, _) -> Promise.success(Unit.unit())),
                                               () -> List.of(CANDIDATE, PEER),
                                               probe,
                                               (_, _) -> local.get(),
                                               (_, _, source, tail) -> {
                                                   catchUps.add(source + "@" + tail);
                                                   local.set(tail);
                                                   return Promise.success(tail);
                                               },
                                               () -> true,
                                               ranges,
                                               block -> {
                                                   alarms.add(block);
                                                   return Unit.unit();
                                               },
                                               TimeSpan.timeSpan(0).millis());
    }

    /// Claim 2's consequence. Base: the probe reads the first cut page ([0]) as the peer's head, 0 < 3, the gate
    /// activates at 3 with NO catch-up while the peer holds 9. Head: the probe reads 9 and the gate catches up to 9.
    @Test
    void probeK_byteCappedPeer_gateCatchesUpToThePeersRealHead() {
        wire(100L);
        publish(10, 1);
        assertThat(peer.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::headOffset).or(-1L)).isEqualTo(9L);

        var outcome = gate().activate(STREAM, PARTITION).await();

        assertThat(outcome.isSuccess()).as("activation outcome %s", outcome).isTrue();
        assertThat(catchUps).as("the gate must catch up to the peer's real head, not activate below it")
                            .containsExactly(PEER + "@9");
    }

    /// Control: no cap. Both base and head catch up to 9.
    @Test
    void probeK_control_uncappedPeer_gateCatchesUpTo9() {
        wire(StreamForwardHandler.DEFAULT_MAX_READ_RESPONSE_BYTES);
        publish(10, 1);

        var outcome = gate().activate(STREAM, PARTITION).await();

        assertThat(outcome.isSuccess()).as("activation outcome %s", outcome).isTrue();
        assertThat(catchUps).containsExactly(PEER + "@9");
    }

    /// Probe L as a pin: every event of the peer (200 bytes) alone exceeds its cap (100). The handler admits the first
    /// event of each page, so the probe reads the peer's real head and the gate catches up to it. Red under "a page
    /// may be cut before its first event": the peer's answer fails the probe and the gate raised the false CRITICAL
    /// "did not answer the watermark probe" for a peer that answered.
    @Test
    void probeL_oversizedEventAtPeer_gateCatchesUpToThePeersHead_noAlarm() {
        wire(100L);
        publish(6, 200);

        var outcome = gate().activate(STREAM, PARTITION).await();

        assertThat(outcome.isSuccess()).as("activation outcome %s", outcome).isTrue();
        assertThat(catchUps).as("caught up to the peer's head through one-event pages").containsExactly(PEER + "@5");
        assertThat(alarms).isEmpty();
    }

    /// The backstop: a peer on a handler that still cuts a page before its first event ANSWERS with an empty cut
    /// page. Its own block, raised once per distinct block and never the "did not answer" alert, even past the
    /// unreachable-alarm window (zero here). Red under "any failed probe counts as unreachable".
    @Test
    void backstop_peerAnswersWithAnEmptyCutPage_raisesItsOwnBlock_neverHoldersUnreachable() {
        wire(StreamForwardHandler.DEFAULT_MAX_READ_RESPONSE_BYTES);
        var gate = gateWith((_, _, _) -> new OwnerPeerReads.EventExceedsReadCap(4L).promise());

        var first = gate.activate(STREAM, PARTITION).await();
        var second = gate.activate(STREAM, PARTITION).await();

        assertThat(first.isFailure()).as("refused: %s", first).isTrue();
        assertThat(second.isFailure()).isTrue();
        assertThat(catchUps).isEmpty();
        assertThat(alarms).as("raised once, on the transition").singleElement()
                          .isInstanceOfSatisfying(OwnerActivation.ActivationBlock.PeerEventExceedsReadCap.class,
                                                  block -> {
                                                      assertThat(block.peer()).isEqualTo(PEER);
                                                      assertThat(block.offset()).isEqualTo(4L);
                                                      assertThat(block.message()).contains("orders[0]")
                                                                                 .contains("offset 4")
                                                                                 .doesNotContain("did not answer");
                                                  });
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(10, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         2,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
