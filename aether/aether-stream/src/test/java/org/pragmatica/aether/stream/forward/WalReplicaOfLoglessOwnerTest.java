// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.LastSealedOffsetSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.PartitionFlags.PartitionFlag;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.replication.ForwardCatchupTransport.forwardCatchupTransport;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;

/// I-25 (#1938, PR-6a, NT-142): a WAL replica fed by an owner that keeps no log (the non-durable opt-in) is not left with records
/// and no history. The owner vouches an empty history; the replica attributes the catch-up to the committed epochs, so a second
/// backfill compares equal and ends caught up, and a live append lands. The wire is in-process, as in `CatchUpProvenanceTest`.
/// Only the committed owner's answer is attributed so (v2858a R1): an empty history from any other source stays empty.
class WalReplicaOfLoglessOwnerTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final NodeId NEW_PEER = NodeId.randomNodeId();
    private static final NodeId ELSEWHERE = NodeId.randomNodeId();
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E2 = Epoch.epoch(0, 2, 0);
    private static final Epoch E3 = Epoch.epoch(0, 3, 0);

    @TempDir
    Path replicaWal;

    private ReplicaRegistry registry;
    private StreamPartitionManager owner;
    private StreamPartitionManager replica;
    private StreamForwardHandler handler;
    private StreamForwardClient client;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, OWNER);
        registry.registerReplica(STREAM, PARTITION, NEW_PEER);
        ReplicationManager replication = replicationManager(OWNER, registry);
        owner = streamPartitionManager(Long.MAX_VALUE,
                                       (_, _, _) -> Result.unitResult(),
                                       replication,
                                       Option.<Path> none(),
                                       LastSealedOffsetSource.none());
        replica = streamPartitionManager(Long.MAX_VALUE, Option.some(replicaWal));
        replica.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E2, 3L)));
        owner.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        handler = streamForwardHandler(OWNER, owner, AcceptingStreamForwardTransport.accepting((_, message) -> client.onReadForwardResponse((ReadForwardResponse) message)));
        client = streamForwardClient(NEW_PEER, AcceptingStreamForwardTransport.accepting((_, message) -> handler.onReadForward((ReadForward) message)));
    }

    @AfterEach
    void tearDown() {
        owner.close();
        replica.close();
    }

    @Test
    void twoBackfills_endCaughtUp() {
        publish(E1, 3);
        publish(E2, 1);
        backfill(committedOwner(OWNER));
        publish(E2, 2);
        backfill(committedOwner(OWNER));

        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(6L);
        assertThat(replica.epochHistory(STREAM, PARTITION).unwrap()).as("the committed epochs' provenance, not an empty history")
                                                                    .containsExactly(at(E1, 0L), at(E2, 3L));
        assertThat(replica.quarantinedAt(STREAM, PARTITION)).isEqualTo(Option.none());
    }

    /// v2858a R1: the source answering vouched-empty is NOT the committed owner (the record names another node), so its records
    /// may be of an older lineage and the committed epochs do not label them: the history stays empty.
    @Test
    void sourceThatIsNotTheCommittedOwner_answeringVouchedEmpty_isNotAttributed() {
        publish(E1, 3);
        publish(E2, 1);
        backfill(committedOwner(ELSEWHERE));

        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).as("the page itself is applied").isEqualTo(4L);
        assertThat(replica.epochHistory(STREAM, PARTITION).unwrap()).as("no committed-epoch provenance for a non-owner's answer")
                                                                    .isEmpty();
    }

    /// v2858c R1: the owner read names OWNER at E2 while the starts read already sees E3@4 committed by another node (two reads
    /// of one KV record). OWNER's records 4..5 are E2 lineage that E3 re-assigned, so they must not be labelled E3: only the
    /// starts of the owner read's epoch and below attribute the page, and the copy is doubted from 4 under the current E3.
    @Test
    void ownerRead_isTheOnlySnapshot_aStartCommittedAfterItDoesNotLabelTheDeposedOwnersRecords() {
        publish(E1, 3);
        publish(E2, 3);
        replica.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E2, 3L), new EpochStart(E3, 4L)));
        backfill(committedOwner(OWNER));
        replica.ownerEpochSource((_, _) -> E3);
        replica.placementRoleSupplier((_, _) -> Role.REPLICA);

        var from = replica.unverifiedFrom(STREAM, PARTITION);

        assertThat(from.isPresent() && from.unwrap() <= 4L).as("history=%s unverifiedFrom=%s",
                                                              replica.epochHistory(STREAM, PARTITION),
                                                              from)
                                                          .isTrue();
    }

    /// #1938 "by reading" step: after the catch-up the next LIVE append from the log-less owner lands attributed (the history
    /// is not empty), raises no durable flag, and a third backfill still ends caught up. Red under "catch-up installs nothing".
    @Test
    void liveAppendAfterCatchUp_raisesNoFlag_andAThirdBackfillStaysCaughtUp() {
        var raised = new CopyOnWriteArrayList<AetherValue.PartitionRecoveryReason>();
        replica.partitionFlags(recordingFlags(raised));
        publish(E1, 3);
        publish(E2, 1);
        backfill(committedOwner(OWNER));
        publish(E2, 1);
        backfill(committedOwner(OWNER));

        replica.appendRecovered(STREAM, PARTITION, "e".getBytes(UTF_8), 1L, E2).onFailure(cause -> fail(cause.message()));
        publish(E2, 1);
        backfill(committedOwner(OWNER));

        assertThat(raised).as("no HISTORY_MISSING or MARKED_DIVERGED").isEmpty();
        assertThat(replica.epochHistory(STREAM, PARTITION).unwrap()).containsExactly(at(E1, 0L), at(E2, 3L));
        assertThat(replica.quarantinedAt(STREAM, PARTITION)).isEqualTo(Option.none());
    }

    private static PartitionFlags recordingFlags(List<AetherValue.PartitionRecoveryReason> raised) {
        return new PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, AetherValue.PartitionRecoveryReason reason) {
                raised.add(reason);

                return Promise.success(new PartitionFlag(AetherValue.StreamPartitionRecoveryValue.raised(Option.none(), reason), "digest"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return Option.none();
            }

            @Override
            public AetherValue.PartitionRecoveryReason local(AetherValue.PartitionRecoveryReasonKind kind, String evidence) {
                return AetherValue.PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("self"), evidence);
            }
        };
    }

    private static CommittedStreamOwnerSource committedOwner(NodeId node) {
        return (_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(node, E2));
    }

    private void backfill(CommittedStreamOwnerSource committedOwnerSource) {
        partitionBackfill(registry,
                          replica.alignedRecovery(),
                          forwardCatchupTransport(client, 100),
                          ReplicationTransport.NOOP,
                          (_, _, _) -> Promise.success(owner.nextExpectedOffset(STREAM, PARTITION) - 1),
                          (stream, partition) -> replica.nextExpectedOffset(stream, partition) - 1,
                          NEW_PEER,
                          TimeSpan.timeSpan(0).millis(),
                          () -> List.of(OWNER),
                          committedOwnerSource).backfill(STREAM, PARTITION)
                                                            .await()
                                                            .onFailure(cause -> fail("backfill failed: " + cause.message()));
    }

    private void publish(Epoch epoch, int count) {
        for (var i = 0; i < count; i++) {
            owner.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1L, epoch).onFailure(cause -> fail(cause.message()));
        }
    }

    private static ProvenanceEntry at(Epoch epoch, long start) {
        return ProvenanceEntry.provenanceEntry(epoch, Option.none(), start);
    }
}
