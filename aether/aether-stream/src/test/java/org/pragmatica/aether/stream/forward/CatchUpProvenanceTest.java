// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.LastSealedOffsetSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.AlignedRecovery;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

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

/// #1596 end to end over the production catch-up path: the owner's forward handler ships its owner-epoch history with
/// a replica catch-up read, `ForwardCatchupTransport` keeps the last page's, and `PartitionBackfill` installs it (N13
/// first) before applying the records -- so a replica that caught up by backfill carries the owner's history, not an
/// empty one. Both sides keep a real WAL. The wire is in-process, as in `ReplicaCatchupTierFallbackTest`.
///
/// Red under "the handler attaches no history" and under "the backfill applies without installing".
class CatchUpProvenanceTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final NodeId NEW_PEER = NodeId.randomNodeId();
    private static final Epoch E1 = Epoch.epoch(1, 0);
    private static final Epoch E2 = Epoch.epoch(2, 0);

    @TempDir
    Path ownerWal;

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
                                       Option.some(ownerWal),
                                       LastSealedOffsetSource.none());
        replica = streamPartitionManager(Long.MAX_VALUE, Option.some(replicaWal));
        owner.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        handler = streamForwardHandler(OWNER, owner, (_, message) -> client.onReadForwardResponse((ReadForwardResponse) message));
        client = streamForwardClient(NEW_PEER, (_, message) -> handler.onReadForward((ReadForward) message));
    }

    @AfterEach
    void tearDown() {
        owner.close();
        replica.close();
    }

    @Test
    void backfilledReplica_carriesTheOwnersHistory() {
        publish(E1, 3);
        publish(E2, 2);

        backfill().backfill(STREAM, PARTITION)
                  .await()
                  .onFailure(cause -> fail("backfill failed: " + cause.message()));

        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(5L);
        assertThat(replica.epochHistory(STREAM, PARTITION).unwrap()).containsExactly(at(E1, 0), at(E2, 3));
        assertThat(replica.epochHistory(STREAM, PARTITION).unwrap()).isEqualTo(owner.epochHistory(STREAM, PARTITION).unwrap());
    }

    /// A replica whose own prefix disagrees with the owner's history (it holds e1 records where the owner's e2 began)
    /// is refused and quarantined at the first differing offset; nothing of the page lands.
    @Test
    void replicaWithADivergentPrefix_isRefusedAndQuarantined() {
        publish(E1, 1);
        publish(E2, 3);
        replica.appendRecovered(STREAM, PARTITION, 0, "x".getBytes(UTF_8), 1L, E1).onFailure(cause -> fail(cause.message()));
        replica.appendRecovered(STREAM, PARTITION, 1, "y".getBytes(UTF_8), 1L, E1).onFailure(cause -> fail(cause.message()));

        backfill().backfill(STREAM, PARTITION)
                  .await()
                  .onSuccess(_ -> fail("a divergent prefix must refuse the catch-up"));

        assertThat(replica.quarantinedAt(STREAM, PARTITION)).isEqualTo(Option.some(1L));
        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).as("nothing of the page landed").isEqualTo(2L);
    }

    /// #1638 B1 through the real backfill: the page's apply fails at offset 3 (the first e2 record) after its slice was
    /// installed. The backfill trims what no record reaches -- e2@3 above head 2 -- and the retry catches up to the
    /// owner's history. Red under "no trim on a failed apply".
    @Test
    void failedApply_isTrimmed_andTheRetryCatchesUp() {
        publish(E1, 3);
        publish(E2, 3);
        var failAt3Once = new AtomicBoolean(true);

        backfill(failingOnceAt(3, failAt3Once)).backfill(STREAM, PARTITION)
                                               .await()
                                               .onSuccess(_ -> fail("the apply was injected to fail at offset 3"));

        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(3L);
        assertThat(replica.epochHistory(STREAM, PARTITION).unwrap()).as("e2@3 trimmed: no record reaches it")
                                                                    .containsExactly(at(E1, 0));

        backfill().backfill(STREAM, PARTITION).await().onFailure(cause -> fail("the retry: " + cause.message()));

        assertThat(replica.epochHistory(STREAM, PARTITION).unwrap()).isEqualTo(owner.epochHistory(STREAM, PARTITION).unwrap());
    }

    private AlignedRecovery failingOnceAt(long offset, AtomicBoolean armed) {
        var real = replica.alignedRecovery();

        return AlignedRecovery.alignedRecovery((stream, partition, at, payload, timestamp) -> at == offset && armed.getAndSet(false)
                                                                                           ? Causes.cause("injected apply failure").<Long> result()
                                                                                           : real.appendRecovered(stream, partition, at, payload, timestamp),
                                               real::applyAttributed,
                                               real::applyUnattributed);
    }

    private PartitionBackfill backfill() {
        return backfill(replica.alignedRecovery());
    }

    private PartitionBackfill backfill(AlignedRecovery recovery) {
        return partitionBackfill(registry,
                                 recovery,
                                 forwardCatchupTransport(client, 100),
                                 ReplicationTransport.NOOP,
                                 (_, _, _) -> Promise.success(owner.nextExpectedOffset(STREAM, PARTITION) - 1),
                                 (stream, partition) -> replica.nextExpectedOffset(stream, partition) - 1,
                                 NEW_PEER,
                                 TimeSpan.timeSpan(0).millis(),
                                 () -> List.of(OWNER),
                                 CommittedStreamOwnerSource.none());
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
