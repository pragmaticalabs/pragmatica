// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher.StreamPublisherError;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.aether.stream.consensus.ConsensusProposer;
import org.pragmatica.aether.stream.consensus.ConsensusPublishPath;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;


/// #1342: a batch is not atomic, so its result must say per event what happened. An EVENTUAL batch writes its
/// partition groups concurrently and each group in order; the min-sync barrier is awaited AFTER the local
/// append (#1236), so a refused event may be durably in the ring. `Promise<Unit>` had either acknowledged a
/// batch with refused events, or failed it and hidden the offsets that DID land. The fixture is the rev1305
/// reviewer probe: a 2-partition stream with `min-sync = 2` whose barrier refuses on partition 1.
class BatchPublishOutcomeTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final String STREAM = "batch-outcome-stream";
    private static final int PARTITIONS = 2;
    private static final Cause BARRIER_FAILED = Causes.cause("barrier refused on partition 1");
    /// What the write router reports for a barrier that failed AFTER the append (#1236): the refusal wrapped as
    /// outcome-unknown, which is exactly the per-event outcome #1342 carries.
    private static final Cause BARRIER_UNKNOWN = PublishOutcomeUnknown.FACTORY.apply(BARRIER_FAILED);
    private static final Cause PROPOSAL_REFUSED = Causes.cause("proposal refused");

    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE,
                                                  (_, _, _) -> Result.unitResult(),
                                                  barrierFailsOnPartitionOne());
        partitionManager.createStream(config()).onFailureRun(Assertions::fail);
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    /// The partial case: the batch reports partition 0's offset AND partition 1's outcome-unknown, while BOTH
    /// events are durably in the ring — the failure was raised after the append.
    @Test
    void partialBatch_reportsTheOffsetThatLanded_andOutcomeUnknownForTheRefused_bothDurable() {
        var outcomes = eventualPublisher().publishBatch(List.of(keyFor(0), keyFor(1))).await().unwrap();

        assertThat(outcomes).containsExactly(new PublishOutcome.Published(0L),
                                             new PublishOutcome.OutcomeUnknown(BARRIER_UNKNOWN));
        assertThat(appended(0)).as("partition 0's event IS in the log").isEqualTo(1);
        assertThat(appended(1)).as("partition 1's event IS in the log too — the barrier failed AFTER the append")
                  .isEqualTo(1);
    }

    /// Local batches submit the entire run before a cumulative barrier; failure makes every
    /// submitted event uncertain rather than incorrectly reporting a safely retryable suffix.
    @Test
    void failedLocalBatch_reportsEverySubmittedEventAsUnknown() {
        var first = keyFor(1);
        var second = keyFor(1, first);
        var outcomes = eventualPublisher().publishBatch(List.of(first, second)).await().unwrap();

        assertThat(outcomes).containsExactly(new PublishOutcome.OutcomeUnknown(BARRIER_UNKNOWN),
                                             new PublishOutcome.OutcomeUnknown(BARRIER_UNKNOWN));
        assertThat(appended(1)).as("both events reached the ring before the cumulative barrier").isEqualTo(2);
    }

    /// A third local event is also already submitted when the batch barrier fails.
    @Test
    void failedLocalBatch_neverLabelsSubmittedThirdEventNotAttempted() {
        var first = keyFor(1);
        var second = keyFor(1, first);
        var third = keyFor(1, first, second);
        var skipped = new PublishOutcome.OutcomeUnknown(BARRIER_UNKNOWN);

        var outcomes = eventualPublisher().publishBatch(List.of(first, second, third)).await().unwrap();

        assertThat(outcomes).containsExactly(new PublishOutcome.OutcomeUnknown(BARRIER_UNKNOWN), skipped, skipped);
        assertThat(appended(1)).as("all three events were submitted before the cumulative barrier").isEqualTo(3);
    }

    /// Outcomes sit at their INPUT index whatever the partition grouping: a partition-1 event first, then two
    /// partition-0 events that land at offsets 0 and 1 in order.
    @Test
    void outcomes_followInputOrder_acrossPartitionGroups() {
        var p0first = keyFor(0);
        var p0second = keyFor(0, p0first);
        var outcomes = eventualPublisher().publishBatch(List.of(keyFor(1), p0first, p0second)).await().unwrap();

        assertThat(outcomes).containsExactly(new PublishOutcome.OutcomeUnknown(BARRIER_UNKNOWN),
                                             new PublishOutcome.Published(0L),
                                             new PublishOutcome.Published(1L));
        assertThat(appended(0)).isEqualTo(2);
    }

    @Test
    void oversizedRunFallback_preservesSuccessfulPrefixAndNeverAttemptsTheSuffix() {
        var outcomes = StreamWriteRouter.localOnly(partitionManager).publishBatch(STREAM, 0,
            List.of(new byte[]{1}, new byte[2 * 1024 * 1024], new byte[]{3}), 1).await().unwrap();
        assertThat(outcomes.get(0)).isEqualTo(new PublishOutcome.Published(0));
        assertThat(outcomes.get(1)).isInstanceOf(PublishOutcome.OutcomeUnknown.class);
        assertThat(outcomes.get(2)).isInstanceOf(PublishOutcome.NotAttempted.class);
        assertThat(appended(0)).isEqualTo(1);
    }

    @Test
    void emptyBatch_reportsNoOutcomes() {
        assertThat(eventualPublisher().publishBatch(List.of()).await().unwrap()).isEmpty();
    }

    /// R10 (rev1305): the STRONG fold was unpinned because the up-front consensus-path guard returned first. With
    /// a path wired, each proposal's result is its event's outcome — a refused proposal must not be acknowledged.
    @Test
    void strongBatch_withConsensusPath_reportsEachProposalsOwnOutcome() {
        var outcomes = strongPublisher(refusing("e1")).publishBatch(List.of("e0", "e1", "e2")).await().unwrap();

        assertThat(outcomes).containsExactly(new PublishOutcome.Published(offsetFor("e0")),
                                             new PublishOutcome.OutcomeUnknown(PROPOSAL_REFUSED),
                                             new PublishOutcome.Published(offsetFor("e2")));
    }

    private int appended(int partition) {
        return (int) partitionManager.nextExpectedOffset(STREAM, partition);
    }

    /// One key per partition (the stable hash the publisher routes by), skipping `taken` for a second key.
    private static String keyFor(int partition, String... taken) {
        for (var i = 0; i < 1000; i++) {
            var key = "k" + i;

            if (partitionOf(key) == partition && !List.of(taken).contains(key)) {
                return key;
            }
        }

        throw new IllegalStateException("no key for partition " + partition);
    }

    private static int partitionOf(String key) {
        return (int) Math.floorMod(ReplicaPlacement.stableHash64(key), (long) PARTITIONS);
    }

    /// The stub proposer answers with the payload's length, so the expected offset is derived, not hard-coded.
    private static long offsetFor(String event) {
        return event.length();
    }

    private static ConsensusProposer refusing(String refusedEvent) {
        return command -> new String(command.payload(), StandardCharsets.UTF_8).equals(refusedEvent)
                          ? PROPOSAL_REFUSED.promise()
                          : Promise.success((long) command.payload().length);
    }

    private DefaultStreamPublisher<String> strongPublisher(ConsensusProposer proposer) {
        return DefaultStreamPublisher.streamPublisher(partitionManager,
                                                      utf8Serializer(),
                                                      STREAM,
                                                      PARTITIONS,
                                                      Option.<Function<String, Object>> none(),
                                                      ConsistencyMode.STRONG,
                                                      Option.some(ConsensusPublishPath.consensusPublishPath(proposer)));
    }

    private DefaultStreamPublisher<String> eventualPublisher() {
        Function<String, Object> keyExtractor = event -> event;

        return DefaultStreamPublisher.streamPublisher(partitionManager,
                                                      utf8Serializer(),
                                                      STREAM,
                                                      PARTITIONS,
                                                      Option.some(keyExtractor),
                                                      ConsistencyMode.EVENTUAL,
                                                      Option.none(),
                                                      Option.none(),
                                                      Option.<Fn0<Option<NodeId>>> none(),
                                                      Option.some(_ -> Option.some(SELF)),
                                                      Option.some(SELF));
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         PARTITIONS,
                                         RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         3,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static ReplicationManager barrierFailsOnPartitionOne() {
        return new ReplicationManager() {
            @Override
            public void replicateEvent(String streamName,
                                       int partition,
                                       long offset,
                                       byte[] payload,
                                       long timestamp,
                                       Epoch ownerEpoch) {}

            @Override
            public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override
            public ReplicaRegistry registry() {
                return ReplicationManager.NONE.registry();
            }

            @Override
            public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
                return partition == 1
                       ? BARRIER_FAILED.promise()
                       : Promise.unitPromise();
            }

            /// #1235: acknowledgement is decided by the barrier above, so every appended offset counts as acked.
            @Override
            public long replicatedThrough(String streamName, int partition, int minAcks) {
                return Long.MAX_VALUE;
            }

            @Override
            public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
                return Long.MAX_VALUE;
            }

            @Override
            public void observeAcks(AckObserver observer) {}
        };
    }

    private static Serializer utf8Serializer() {
        return new Serializer() {
            @Override
            public <T> byte[] encode(T object) {
                return String.valueOf(object).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                byteBuf.writeBytes(encode(object));
            }
        };
    }
}
