// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.FailoverRecovery.failoverRecovery;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

/// #1244 (backfill-commit ruling, know 801a8b54e): replica WAL frames carry no per-record fsync, so a backfill run —
/// the catch-up that precedes promotion — commits what it re-appended before it completes. Runs against
/// a REAL WAL and asserts exactly ONE fsync with NO later live batch: one proves the run is durable on a
/// quiet partition, and not more than one proves the commit is per run, never per record. Since #1235 a
/// WAL-backed replica record is visible only at that barrier, so the same run also proves the records
/// are readable on this replica once it completes. The CTO's 2026-09-19 waiver of the failover paths was
/// replaced on 2026-09-20 (#1235 × #1244): `DefaultFailoverRecovery` commits once per recovered partition
/// (pinned here, same catch-up fixture) and `GovernorFailoverHandler` once per replay run (pinned in
/// `GovernorFailoverHandlerTest`).
class CatchUpWalDurabilityTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int EVENTS = 5;
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId SOURCE = NodeId.nodeId("source").unwrap();

    @TempDir
    Path walDir;

    private StreamPartitionManager replica;
    private ReplicaRegistry registry;

    @BeforeEach
    void setUp() {
        replica = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, SOURCE);
        registry.updateWatermark(STREAM, PARTITION, SOURCE, EVENTS - 1);
    }

    @AfterEach
    void tearDown() {
        replica.close();
    }

    @Test
    void partitionBackfill_completedRun_isFsyncedOnce_withoutALaterLiveBatch() {
        registry.registerReplica(STREAM, PARTITION, SELF);
        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           replica::appendRecovered,
                                                           CatchUpWalDurabilityTest::sourceRange,
                                                           ReplicationTransport.NOOP,
                                                           (_, _, _) -> Causes.cause("no probe").promise(),
                                                           (_, _) -> -1L,
                                                           SELF,
                                                           TimeSpan.timeSpan(3600).seconds(),
                                                           List::of,
                                                           CommittedStreamOwnerSource.none(),
                                                           replica::syncReplicated,
                                                           replica.quarantineView());
        var before = fsyncCount();

        backfill.backfill(STREAM, PARTITION).await().onFailure(cause -> fail(cause.message()));

        assertOneCommitCovering(before);
    }

    /// The catch-up-transport failover path: a recovery run commits its recovered partition through the
    /// barrier once, after its last append. Without that barrier the run's WAL frames were never
    /// committed, so its records stayed invisible on this replica until an unrelated live batch's barrier
    /// happened to cover them.
    @Test
    void failoverRecovery_completedRun_isFsyncedOnce_andItsRecordsAreVisible_withoutALaterLiveBatch() {
        var recovery = failoverRecovery(registry,
                                        replica::appendRecovered,
                                        CatchUpWalDurabilityTest::sourceRange,
                                        replica::syncReplicated);
        var before = fsyncCount();

        var replayed = recovery.recover(STREAM, 1)
                               .await()
                               .onFailure(cause -> fail(cause.message()))
                               .map(FailoverRecovery.RecoveryResult::eventsReplayed)
                               .or(-1L);

        assertThat(replayed).isEqualTo(EVENTS);
        assertOneCommitCovering(before);
    }

    /// Exactly one fsync, and it covers every re-appended frame: the ring serves all EVENTS records — a
    /// replica-local read is bounded by the visible offset (#1235), so this is the visibility check — and
    /// the WAL's last written offset is the last of them.
    private void assertOneCommitCovering(long fsyncsBefore) {
        assertThat(replica.readLocal(STREAM, PARTITION, 0, 100).unwrap()).as("every record is visible on this replica after the run, with no live batch")
                                                                          .hasSize(EVENTS);
        assertThat(walStats().lastOffset()).isEqualTo(EVENTS - 1);
        assertThat(fsyncCount() - fsyncsBefore).as("one commit per catch-up run — durable without a later live batch,"
                                                     + " and never one fsync per record")
                                               .isEqualTo(1);
    }

    private long fsyncCount() {
        return walStats().fsyncCount();
    }

    private PartitionWal.WalStats walStats() {
        return replica.walSnapshot()
                      .streams()
                      .stream()
                      .flatMap(view -> view.partitions().stream())
                      .filter(view -> view.partition() == PARTITION)
                      .flatMap(view -> view.wal().stream())
                      .findFirst()
                      .orElseThrow();
    }

    private static Promise<ReplicationMessage.CatchupResponse> sourceRange(NodeId target,
                                                                          ReplicationMessage.CatchupRequest request) {
        return Promise.success(catchupResponse(target,
                                               STREAM,
                                               PARTITION,
                                               0L,
                                               EVENTS - 1,
                                               IntStream.range(0, EVENTS).mapToObj(i -> payload(i)).toList(),
                                               IntStream.range(0, EVENTS).mapToObj(i -> 1000L + i).toList()));
    }

    private static byte[] payload(int i) {
        return ("event-" + i).getBytes(UTF_8);
    }
}
