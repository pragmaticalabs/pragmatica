// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;


class StreamForwardHandlerTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REQUESTER = NodeId.randomNodeId();
    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "hello".getBytes();
    private static final long TIMESTAMP = 42L;
    private static final String CORRELATION_ID = "corr-001";

    private StreamPartitionManager partitionManager;
    private List<SentMessage> sentMessages;
    private StreamForwardHandler handler;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
        sentMessages = new ArrayList<>();
        StreamForwardTransport capturingTransport = (target, message) -> sentMessages.add(new SentMessage(target, message));
        handler = streamForwardHandler(GOVERNOR, partitionManager, capturingTransport);
    }

    @Nested
    class SuccessTests {

        @Test
        void onPublishForward_publishesLocallyAndRespondsWithOffset() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var sent = sentMessages.getFirst();
            assertThat(sent.target()).isEqualTo(REQUESTER);
            var response = (PublishForwardResponse) sent.message();
            assertThat(response.success()).isTrue();
            assertThat(response.offset()).isGreaterThanOrEqualTo(0L);
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(response.sender()).isEqualTo(GOVERNOR);
        }

        @Test
        void onPublishForward_eventIsStoredLocally() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            var events = partitionManager.readLocal(STREAM, PARTITION, 0L, 10);
            assertThat(events.isSuccess()).isTrue();
            events.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat(list.getFirst().data()).isEqualTo(PAYLOAD);
            });
        }
    }

    @Nested
    class FailureTests {

        @Test
        void onPublishForward_configNotVisible_respondsRetryable() {
            // No committed config visible on this owner (default source) — the forwarder just committed it,
            // so the owner treats the absent-config publish as the retryable config-visibility race.
            var request = publishForward(REQUESTER, CORRELATION_ID, "nonexistent", PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (PublishForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.retryable()).isTrue();
            assertThat(response.errorMessage()).contains("not yet visible");
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        void onPublishForward_partitionOutOfRange_respondsWithError() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, 99, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (PublishForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.retryable()).isFalse();
            assertThat(response.errorMessage()).contains("out of range");
        }
    }

    @Nested
    class LazyMaterializationTests {

        @Test
        void onPublishForward_materializesFromCommittedConfig_whenConfigVisibleButNotMaterialized() {
            // Config committed-visible on this owner but the stream is not yet in the local map (the owner's
            // onStreamConfigPut notification lagged the forward): the owner lazily materializes and appends.
            partitionManager.committedConfigSource(name -> Option.some(streamConfig(name)));
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (PublishForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isTrue();
            assertThat(response.offset()).isGreaterThanOrEqualTo(0L);

            var events = partitionManager.readLocal(STREAM, PARTITION, 0L, 10);
            assertThat(events.isSuccess()).isTrue();
            events.onSuccess(list -> assertThat(list).hasSize(1));
        }
    }

    // SPEC: §11.2 ReadForward handler tests
    @Nested
    class ReadForwardTests {
        private static final long FROM_OFFSET = 0L;
        private static final int MAX_EVENTS = 10;

        @Test
        void onReadForward_success_sendsResponseWithEvents() {
            partitionManager.createStream(streamConfig(STREAM));
            partitionManager.publishLocal(STREAM, PARTITION, "first".getBytes(), 100L);
            partitionManager.publishLocal(STREAM, PARTITION, "second".getBytes(), 101L);

            var request = readForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);
            handler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isTrue();
            assertThat(response.truncated()).isFalse();
            assertThat(response.events()).hasSize(2);
            assertThat(response.events().get(0).data()).isEqualTo("first".getBytes());
            assertThat(response.events().get(1).data()).isEqualTo("second".getBytes());
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(response.sender()).isEqualTo(GOVERNOR);
        }

        @Test
        void onReadForward_partitionNotLocal_sendsFailureResponse() {
            // No stream exists on this node; readLocal returns a StreamNotFound failure.
            var request = readForward(REQUESTER, CORRELATION_ID, "nonexistent", PARTITION, FROM_OFFSET, MAX_EVENTS);
            handler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("not found");
        }

        @Test
        void onReadForward_readLocalFailure_sendsFailureResponse() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = readForward(REQUESTER, CORRELATION_ID, STREAM, 99, FROM_OFFSET, MAX_EVENTS);
            handler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("out of range");
        }

        // SPEC: §10.5 defensive cap → truncated flag
        @Test
        void onReadForward_oversizedResponse_truncatesAndSetsFlag() {
            partitionManager.createStream(streamConfig(STREAM));
            var bigPayload = new byte[256];
            for (int i = 0; i < 10; i++) {
                partitionManager.publishLocal(STREAM, PARTITION, bigPayload, 100L + i);
            }
            // Handler with tiny cap (500 bytes). Each event is ~256 + 24 = 280 bytes; envelope 64.
            // So only 1 event fits (64 + 280 = 344), second would hit 624 > 500.
            var tinyHandler = streamForwardHandler(GOVERNOR,
                                                   partitionManager,
                                                   (target, message) -> sentMessages.add(new SentMessage(target, message)),
                                                   500L,
                                                   StreamReadForwardMetrics.NOOP);
            var request = readForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);
            tinyHandler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isTrue();
            assertThat(response.truncated()).isTrue();
            assertThat(response.events()).hasSizeLessThan(10);
        }
    }

    /// The forwarded-publish ack must carry the SAME `min-sync-replicas` guarantee a local publish carries.
    /// Before the owner-side barrier, `onPublishForward` acked on the owner's local append alone, so every
    /// write routed to an owner silently ran at min-sync 1. Live evidence (02y-stream-crash, 2026-08-16):
    /// 80/80 ACKED, then one SIGKILL took two whole partitions with it — 41 acked events lost.
    ///
    /// These wire a REAL [org.pragmatica.aether.stream.replication.ReplicationManager] over an EMPTY
    /// [org.pragmatica.aether.stream.replication.ReplicaRegistry] deliberately: the NOOP manager's
    /// `awaitReplication` returns success unconditionally, so a test on the default bare manager would
    /// pass whether or not the barrier exists.
    @Nested
    class MinSyncBarrierTests {
        private StreamPartitionManager barrierManager;

        private StreamForwardHandler handlerFor(int minSyncReplicas) {
            barrierManager = streamPartitionManager(Long.MAX_VALUE,
                                                    EvictionListener.NOOP,
                                                    replicationManager(GOVERNOR, replicaRegistry()));
            barrierManager.createStream(configWithMinSync(minSyncReplicas));

            return streamForwardHandler(GOVERNOR,
                                        barrierManager,
                                        (target, message) -> sentMessages.add(new SentMessage(target, message)));
        }

        private static StreamConfig configWithMinSync(int minSyncReplicas) {
            return StreamConfig.streamConfig(STREAM,
                                             1,
                                             RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000),
                                             "latest",
                                             1_048_576L,
                                             ConsistencyMode.EVENTUAL,
                                             2,
                                             minSyncReplicas,
                                             StreamCompression.NONE,
                                             Option.none());
        }

        @Test
        void onPublishForward_minSyncTwoWithNoInSyncReplica_doesNotAck() {
            var handler = handlerFor(2);
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (PublishForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success())
                .as("min-sync-replicas=2 with no in-sync replica must NOT ack — acking here is the data-loss bug")
                .isFalse();
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        void onPublishForward_minSyncOne_acksWithoutAwaitingReplication() {
            var handler = handlerFor(1);
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (PublishForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success())
                .as("min-sync-replicas<=1 carries no peer-ack barrier and must stay a plain local-append ack")
                .isTrue();
            assertThat(response.offset()).isGreaterThanOrEqualTo(0L);
        }
    }

    record SentMessage(NodeId target, StreamForwardMessage message) {}
}
