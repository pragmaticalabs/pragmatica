// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.node.ReplicaCutover;
import org.pragmatica.aether.node.health.HierarchyPeerPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.metadata.WorkerMetadataMessage;
import org.pragmatica.cluster.metrics.ClusterDirectory;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.replica.ReplicaApplier;
import org.pragmatica.cluster.replica.ReplicaClientLimits;
import org.pragmatica.cluster.replica.ReplicaFeedClient;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaReadiness;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;


/// W-OBS W3 wiring of the replica feed client on a worker, DARK: [#replicaClient] builds and starts nothing unless the node was constructed
/// with [ReplicaCutover#LIVE], which production never is (`ReplicaCutover.production()` is `DARK`, pinned by `ReplicaCutoverGateTest`). When LIVE
/// the client follows a core uplink, installs the cluster directory it caches from ClusterSync into the three consumers the projection fed, and
/// drives the engine's readiness flag and the node's freshness gate from the one [ReplicaReadiness] computation (I-W6).
public sealed interface ReplicaClientAssembly {
    TimeSpan TICK = TimeSpan.timeSpan(100).millis();
    int QUEUE_CAPACITY = 1024;

    /// The three consumers a worker's cluster directory fed through the projection, now fed from the ClusterSync directory.
    record DirectoryInstall(Function<List<NodeInfo>, Result<Unit>> peers,
                            Consumer<List<NodeInfo>> endpoints,
                            Consumer<WorkerMetadataMessage.DhtReplication> dhtReplication) {}

    /// What `replicaClient` reads from the assembly built so far.
    record Inputs(NodeId self,
                  KVStore<AetherKey, AetherValue> kvStore,
                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                  Supplier<Option<ClusterDirectory>> heldDirectory,
                  Supplier<java.util.Set<NodeId>> coreIds,
                  DirectoryInstall directoryInstall,
                  Runnable workerReady,
                  AtomicReference<BooleanSupplier> workerProjectionFreshRef,
                  List<MessageRouter.Entry<?>> routes,
                  Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The client, when the node is a worker built LIVE; empty otherwise (nothing is constructed, nothing is routed, nothing is scheduled).
    static Option<ReplicaFeedClient<AetherKey, AetherValue>> replicaClient(ReplicaCutover cutover,
                                                                           boolean worker,
                                                                           Inputs in) {
        return cutover.isLive() && worker
               ? Option.some(startClient(in))
               : Option.none();
    }

    private static ReplicaFeedClient<AetherKey, AetherValue> startClient(Inputs in) {
        var limits = ReplicaClientLimits.DEFAULT;
        var serial = serialExecutor();
        var installer = DirectoryInstaller.directoryInstaller(in.heldDirectory(), in.directoryInstall());
        var applier = ReplicaApplier.replicaApplier(in.kvStore(), limits.bufferBytes());
        var readiness = ReplicaReadiness.replicaReadiness(System::nanoTime,
                                                          applier::position,
                                                          () -> assignedWorker(in.kvStore(), in.self()),
                                                          installer::isInstalled,
                                                          ready -> publishReadiness(in.clusterNode(),
                                                                                    in.workerReady(),
                                                                                    ready),
                                                          limits);
        var client = ReplicaFeedClient.replicaFeedClient(in.self(),
                                                         applier,
                                                         readiness,
                                                         in.clusterNode().network()::send,
                                                         () -> uplinks(in.self(),
                                                                       in.coreIds().get()),
                                                         serial,
                                                         System::nanoTime,
                                                         limits,
                                                         () -> in.clusterNode()
                                                                 .completePassiveRestore());

        in.workerProjectionFreshRef().set(readiness::ready);
        in.routes().add(MessageRouter.Entry.route(ReplicaEntries.class, client::onEntries));
        in.routes().add(MessageRouter.Entry.route(ReplicaHeartbeat.class, client::onHeartbeat));
        in.routes().add(MessageRouter.Entry.route(ReplicaRefused.class, client::onRefused));
        in.routes().add(MessageRouter.Entry.route(ReplicaSnapshotChunk.class, client::onSnapshotChunk));
        in.defer().accept(() -> SharedScheduler.scheduleAtFixedRate(client::tick, TICK));
        in.defer().accept(() -> SharedScheduler.scheduleAtFixedRate(() -> serial.execute(installer::tick), TICK));

        return client;
    }

    /// One thread, so the client's state and the directory installs never race; it dies when idle.
    private static Executor serialExecutor() {
        var executor = new ThreadPoolExecutor(1,
                                              1,
                                              5L,
                                              TimeUnit.SECONDS,
                                              new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                                              task -> Thread.ofPlatform()
                                                            .daemon()
                                                            .name("replica-feed-client")
                                                            .unstarted(task),
                                              new ThreadPoolExecutor.DiscardPolicy());

        executor.allowCoreThreadTimeOut(true);

        return executor;
    }

    /// The node's two deterministic core uplinks, in a fixed order.
    static List<NodeId> uplinks(NodeId self, java.util.Set<NodeId> cores) {
        return HierarchyPeerPolicy.coreUplinks(self, cores)
                                  .stream()
                                  .sorted(Comparator.comparing(NodeId::id))
                                  .toList();
    }

    /// Readiness (d): this node's own activation directive names the WORKER role.
    static boolean assignedWorker(KVStore<AetherKey, AetherValue> kvStore, NodeId self) {
        return kvStore.getTyped(new AetherKey.ActivationDirectiveKey(self),
                                AetherValue.ActivationDirectiveValue.class)
                      .map(directive -> AetherValue.ActivationDirectiveValue.WORKER.equals(directive.role()))
                      .or(false);
    }

    /// The only publisher of what readiness drives (I-W6): the engine's flag follows `ready` both ways; the first ready edge also runs the
    /// worker-ready step the projection ran (switch to forwarding mode, reconcile activation).
    @Contract
    static void publishReadiness(RabiaNode<KVCommand<AetherKey>> clusterNode, Runnable workerReady, boolean ready) {
        clusterNode.setPassiveClientReady(ready);
        if (ready) {
            workerReady.run();
        }
    }

    /// Installs the directory this node holds (cached from ClusterSync, F-1) into the consumers whenever it is strictly newer than the one
    /// installed; readiness (f) holds once one with generation > 0 is in. Confined to the client's serial executor.
    final class DirectoryInstaller {
        private final Supplier<Option<ClusterDirectory>> held;
        private final DirectoryInstall install;
        private final AtomicReference<DirectoryVersion> installed = new AtomicReference<>(DirectoryVersion.NONE);

        private DirectoryInstaller(Supplier<Option<ClusterDirectory>> held, DirectoryInstall install) {
            this.held = held;
            this.install = install;
        }

        static DirectoryInstaller directoryInstaller(Supplier<Option<ClusterDirectory>> held,
                                                     DirectoryInstall install) {
            return new DirectoryInstaller(held, install);
        }

        @Contract
        void tick() {
            held.get()
                .filter(directory -> directory.version()
                                              .isStrictlyAfter(installed.get()))
                .onPresent(this::installNow);
        }

        private void installNow(ClusterDirectory directory) {
            install.peers().apply(directory.nodes()).onSuccess(_ -> installRest(directory));
        }

        private void installRest(ClusterDirectory directory) {
            install.endpoints().accept(directory.nodes());
            install.dhtReplication().accept(workerReplication(directory.dhtReplication()));
            installed.set(directory.version());
        }

        boolean isInstalled() {
            return installed.get()
                            .generation() > 0L;
        }

        private static WorkerMetadataMessage.DhtReplication workerReplication(ClusterDirectory.DhtReplication replication) {
            return new WorkerMetadataMessage.DhtReplication(replication.replicationFactor(),
                                                            replication.confirmationFactor(),
                                                            replication.cacheReplicationFactor(),
                                                            replication.cacheConfirmationFactor(),
                                                            replication.configVersion(),
                                                            replication.changeVersion(),
                                                            replication.floorWriteQuorum(),
                                                            replication.floorReadQuorum(),
                                                            replication.changeSettled());
        }
    }

    record unused() implements ReplicaClientAssembly {}
}
