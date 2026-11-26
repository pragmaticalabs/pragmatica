// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.replication.BackfillError;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.aether.stream.replication.StreamCatalog;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.OwnerLifetime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/// #3754: the node stop's end of the stream replication ([StreamAssembly#stopStreamReplication], what `streamReplicationShutdown`
/// runs) ends the backfill's timers. A flight the transport refused holds its partition's slot on a hold timer; that timer's body
/// re-runs the backfill of a joined flight. The timer is armed through an [OwnerLifetime] on a scheduler the test fires by hand, so
/// the test never waits on a clock (F3: the earlier real 250-300 ms hold raced the test thread). The same sequence without the stop
/// is the control: the fired timer re-runs, so the stopped run's absence of a re-run is the stop's work and not a timer that
/// never fired. That the node's real `streamReplicationShutdown` Runnable reaches this helper is pinned by
/// `StreamReplicationStopBootTest`, which stops a booted node.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StreamAssemblyStopTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId SOURCE = NodeId.randomNodeId();
    private static final StreamCatalog CATALOG = List::of;
    private static final TimeSpan HOUR = TimeSpan.timeSpan(1).hours();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private final CopyOnWriteArrayList<Promise<ReplicationMessage.CatchupResponse>> pulls = new CopyOnWriteArrayList<>();
    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Armed> armed = new CopyOnWriteArrayList<>();

    private record Armed(Runnable wrapper, TimeSpan delay) {}

    /// Records each wrapper the lifetime enqueues; the test runs it by hand. The returned future only has to be cancellable.
    private ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan delay, Option<TimeSpan> period) {
        armed.add(new Armed(wrapper, delay));

        return mock(ScheduledFuture.class);
    }

    /// A backfill whose one flight was joined and then refused: it holds its slot on the hold timer.
    private PartitionBackfill refusedJoinedFlight() {
        var registry = ReplicaRegistry.replicaRegistry();
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        registry.registerReplica(STREAM, PARTITION, SOURCE);
        registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
        registry.registerReplica(STREAM, PARTITION, SELF);

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           manager.alignedRecovery(),
                                                           this::gated,
                                                           ReplicationTransport.NOOP,
                                                           SELF,
                                                           HOUR,
                                                           OwnerLifetime.ownerLifetime(this::enqueue));

        var first = backfill.backfill(STREAM, PARTITION);

        backfill.backfill(STREAM, PARTITION);
        pulls.getFirst().fail(StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(SOURCE)));
        // The landing runs on the run's own chain and resolves the flight's result after the hold is armed: wait for it, so
        // the hold timer is on the seam before the test reads it.
        assertThat(first.await(AWAIT).isFailure()).as("the refused flight landed, its hold is armed").isTrue();

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

    /// The refusal hold: the one timer armed with a delay below the idle watch's bound.
    private Runnable holdTimer() {
        var holds = armed.stream()
                         .filter(entry -> entry.delay().nanos() < HOUR.nanos())
                         .map(Armed::wrapper)
                         .toList();

        assertThat(holds).as("the hold was armed through the lifetime's seam, once").hasSize(1);

        return holds.getFirst();
    }

    @Test
    void refusedJoinedFlight_withoutTheStop_rerunsAtTheHoldsEnd_control() {
        refusedJoinedFlight();

        holdTimer().run();

        assertThat(pulls).as("the hold's end re-ran the joined flight").hasSize(2);

        backfillExecutor.shutdownNow();
    }

    @Test
    void refusedJoinedFlight_nodeStopBeforeTheHoldsEnd_startsNoRerun() {
        var backfill = refusedJoinedFlight();
        var timer = holdTimer();

        StreamAssembly.stopStreamReplication(controller(), backfill, backfillExecutor);
        timer.run();

        assertThat(pulls).as("the hold's timer was ended by the stop: no re-run at the hold's end").hasSize(1);
        assertThat(backfillExecutor.isShutdown()).as("the stop still shuts the backfill executor").isTrue();
        assertThat(refusedAsStopped(backfill)).as("the stop closed the backfill: a call after it is refused as stopped").isTrue();
    }

    private static boolean refusedAsStopped(PartitionBackfill backfill) {
        return backfill.backfill(STREAM, PARTITION)
                       .await(AWAIT)
                       .fold(cause -> cause == BackfillError.General.STOPPED, _ -> false);
    }
}
