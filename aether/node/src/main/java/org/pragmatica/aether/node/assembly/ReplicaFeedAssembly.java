// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.aether.worker.metadata.WorkerMetadataMessage;
import org.pragmatica.cluster.metrics.ClusterDirectory.DhtReplication;
import org.pragmatica.cluster.replica.ReplicaFeedLimits;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.replica.ReplicaFeedServer;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;

import static org.pragmatica.aether.node.AetherNode.metadataDirectory;
import static org.pragmatica.aether.node.assembly.WorkerHierarchyAssembly.workerDhtReplication;


/// W-OBS W2 wiring on a core: the replica feed server (subscribe and snapshot routes, drain and heartbeat ticks, a dedicated serve pool) and
/// the sources of the cluster directory the leader attaches to its authority ping. Nothing on a worker reads either yet.
public sealed interface ReplicaFeedAssembly {
    /// Serve pool: snapshot capture and serve never run on a network I/O thread. A saturated pool answers `BUSY`.
    int SERVE_THREADS = 4;
    int SERVE_QUEUE = 64;
    TimeSpan DRAIN_INTERVAL = TimeSpan.timeSpan(50).millis();
    TimeSpan HEARTBEAT_INTERVAL = TimeSpan.timeSpan(1).seconds();

    /// A core serves the replica feed iff it takes part in consensus (`isActive`) and is not behind (`isPendingCatchUp`): a source that is itself
    /// behind is excluded here, and a heartbeat carries this flag so a subscriber notices a source that stopped serving. A worker serves only
    /// when its [org.pragmatica.cluster.replica.ReplicaServeGate] says it may serve members (`governorGate`).
    static boolean servesReplicaFeed(boolean worker, boolean active, boolean pendingCatchUp, boolean governorGate) {
        return worker
               ? governorGate
               : active && !pendingCatchUp;
    }

    /// Whether `peer` may be fed by this node: it is tracked and alive, and a worker (a governor) feeds only members of its own community.
    static boolean mayFeed(boolean worker,
                           boolean trackedAndAlive,
                           Option<String> selfCommunity,
                           Option<String> peerCommunity) {
        return trackedAndAlive
               && (!worker || selfCommunity.isPresent() && selfCommunity.equals(peerCommunity));
    }

    /// The announced, undissolved governor of the community this node belongs to AND that this node has a link to; empty without a community, an
    /// announcement or a link. A member must not pick a governor it cannot reach: a subscribe sent over a link that is not up is lost without a
    /// refusal and costs the full silence timeout, so the governor is followed only once the hierarchy link to it exists (the link rules are
    /// unchanged; `rebalance` moves the member over when the link comes up).
    static Option<NodeId> announcedGovernor(NodeId self,
                                            Function<NodeId, Option<String>> communityOf,
                                            Function<String, Option<AetherValue.GovernorAnnouncementValue>> governors,
                                            Predicate<NodeId> connected) {
        return communityOf.apply(self)
                          .flatMap(governors::apply)
                          .filter(announcement -> !announcement.dissolved())
                          .map(AetherValue.GovernorAnnouncementValue::governorId)
                          .filter(connected);
    }

    /// Builds the server, routes its two inbound messages and schedules its drain and heartbeat ticks. `serving` is [#servesReplicaFeed]
    /// over the node's state; `eligible` admits a subscriber (a tracked, live member) and `connected` says whether its link is up.
    static ReplicaFeedServer<AetherKey, AetherValue> replicaFeed(NodeId self,
                                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                                 BiConsumer<NodeId, ProtocolMessage> send,
                                                                 BooleanSupplier serving,
                                                                 Predicate<NodeId> eligible,
                                                                 Predicate<NodeId> connected,
                                                                 List<MessageRouter.Entry<?>> routes,
                                                                 Consumer<Supplier<ScheduledFuture<?>>> defer) {
        var server = ReplicaFeedServer.replicaFeedServer(self,
                                                         kvStore,
                                                         ReplicaFilter.replicaFilter(),
                                                         send,
                                                         serving,
                                                         eligible,
                                                         connected,
                                                         servePool(),
                                                         System::nanoTime,
                                                         ReplicaFeedLimits.DEFAULT);

        routes.add(MessageRouter.Entry.route(ReplicaSubscribe.class, server::onSubscribe));
        routes.add(MessageRouter.Entry.route(ReplicaSnapshotRequest.class, server::onSnapshotRequest));
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(server::tick, DRAIN_INTERVAL));
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(server::heartbeat, HEARTBEAT_INTERVAL));

        return server;
    }

    /// The dedicated serve pool: bounded threads and queue, daemon threads named for the diagnostics.
    private static Executor servePool() {
        var counter = new AtomicInteger();

        return new ThreadPoolExecutor(SERVE_THREADS,
                                      SERVE_THREADS,
                                      60L,
                                      TimeUnit.SECONDS,
                                      new ArrayBlockingQueue<>(SERVE_QUEUE),
                                      task -> serveThread(task, counter.incrementAndGet()),
                                      new ThreadPoolExecutor.AbortPolicy());
    }

    private static Thread serveThread(Runnable task, int index) {
        return Thread.ofPlatform()
                     .daemon()
                     .name("replica-feed-serve-" + index)
                     .unstarted(task);
    }

    /// The nodes the directory lists: every tracked, live member that has a dial address (cores and workers alike).
    static List<NodeInfo> directoryNodes(MembershipFsm membershipFsm) {
        var members = membershipFsm.memberStates()
                                   .keySet()
                                   .stream()
                                   .filter(membershipFsm::isTrackedAndNotDead)
                                   .collect(java.util.stream.Collectors.toSet());

        return metadataDirectory(membershipFsm, members);
    }

    /// The DHT replication the directory carries, derived from the leader's own committed configuration (read outside any ring lock).
    static Result<DhtReplication> directoryDhtReplication(KVStore<AetherKey, AetherValue> kvStore) {
        return directoryDhtReplication(kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                        AetherValue.ClusterConfigValue.class),
                                       kvStore.getTyped(AetherKey.DhtReplicationChangeKey.dhtReplicationChangeKey(),
                                                        AetherValue.DhtReplicationChangeValue.class));
    }

    /// The same, from the committed configuration and the latest replication change themselves.
    static Result<DhtReplication> directoryDhtReplication(Option<AetherValue.ClusterConfigValue> committed,
                                                          Option<AetherValue.DhtReplicationChangeValue> change) {
        return workerDhtReplication(committed, change).map(ReplicaFeedAssembly::directoryReplication);
    }

    private static DhtReplication directoryReplication(WorkerMetadataMessage.DhtReplication replication) {
        return new DhtReplication(replication.replicationFactor(),
                                  replication.confirmationFactor(),
                                  replication.cacheReplicationFactor(),
                                  replication.cacheConfirmationFactor(),
                                  replication.configVersion(),
                                  replication.changeVersion(),
                                  replication.floorWriteQuorum(),
                                  replication.floorReadQuorum(),
                                  replication.changeSettled());
    }

    /// True iff `sender` is the announced, undissolved governor of the community this node belongs to: the third sender the pong path
    /// accepts a directory from (beside a core and the leader).
    static boolean isOwnGovernor(NodeId self,
                                 NodeId sender,
                                 Function<NodeId, Option<String>> communityOf,
                                 Function<String, Option<AetherValue.GovernorAnnouncementValue>> governors) {
        return communityOf.apply(self)
                          .flatMap(governors::apply)
                          .filter(announcement -> !announcement.dissolved())
                          .map(AetherValue.GovernorAnnouncementValue::governorId)
                          .filter(sender::equals)
                          .isPresent();
    }

    record unused() implements ReplicaFeedAssembly {}
}
