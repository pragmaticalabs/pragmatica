// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.StreamCatalog;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #3754: the node stop's end of the stream replication ([StreamAssembly#stopStreamReplication], what `streamReplicationShutdown`
/// runs) ends the backfill's timers. A flight the transport refused holds its partition's slot on a timer on the shared
/// scheduler (250 ms, jittered); that timer's body re-runs the backfill of a joined flight. The same sequence without the stop
/// is the control: it re-runs, so the stopped run's absence of a re-run is the stop's work and not a timer that never fired.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StreamAssemblyStopTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId SOURCE = NodeId.randomNodeId();
    private static final StreamCatalog CATALOG = List::of;
    /// Several times the first hold's upper bound (300 ms).
    private static final long PAST_THE_HOLD_MS = 1500L;

    private final CopyOnWriteArrayList<Promise<ReplicationMessage.CatchupResponse>> pulls = new CopyOnWriteArrayList<>();
    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor();

    /// A backfill whose one flight was joined and then refused: it holds its slot on the hold timer.
    private PartitionBackfill refusedJoinedFlight() {
        var registry = ReplicaRegistry.replicaRegistry();
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        registry.registerReplica(STREAM, PARTITION, SOURCE);
        registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
        registry.registerReplica(STREAM, PARTITION, SELF);

        var backfill = PartitionBackfill.partitionBackfill(registry, manager.alignedRecovery(), this::gated, SELF);

        backfill.backfill(STREAM, PARTITION);
        backfill.backfill(STREAM, PARTITION);
        pulls.getFirst().fail(StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(SOURCE)));

        return backfill;
    }

    private Promise<ReplicationMessage.CatchupResponse> gated(NodeId target, ReplicationMessage.CatchupRequest request) {
        var response = Promise.<ReplicationMessage.CatchupResponse>promise();

        pulls.add(response);

        return response;
    }

    private static ReplicaSetController controller() {
        return ReplicaSetController.replicaSetController(ReplicaRegistry.replicaRegistry(), SELF, List::of, () -> 1, CATALOG);
    }

    private int awaitPulls(int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (pulls.size() < expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(5_000_000L);
        }

        return pulls.size();
    }

    @Test
    void refusedJoinedFlight_withoutTheStop_rerunsAtTheHoldsEnd_control() {
        refusedJoinedFlight();

        assertThat(awaitPulls(2)).as("the hold's end re-ran the joined flight").isEqualTo(2);

        backfillExecutor.shutdownNow();
    }

    @Test
    void refusedJoinedFlight_nodeStopBeforeTheHoldsEnd_startsNoRerun() {
        var backfill = refusedJoinedFlight();

        StreamAssembly.stopStreamReplication(controller(), backfill, backfillExecutor);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PAST_THE_HOLD_MS));

        assertThat(pulls).as("the hold's timer was ended by the stop: no re-run past the hold's end").hasSize(1);
        assertThat(backfillExecutor.isShutdown()).as("the stop still shuts the backfill executor").isTrue();
    }
}
