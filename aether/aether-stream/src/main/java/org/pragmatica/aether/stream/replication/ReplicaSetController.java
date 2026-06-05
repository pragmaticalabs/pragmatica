// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.StreamClass;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.pragmatica.aether.stream.replication.ReplicaPlacement.StreamClass.APP;
import static org.pragmatica.aether.stream.replication.ReplicaPlacement.StreamClass.SYSTEM;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Per-node controller that keeps the local {@link ReplicaRegistry} in agreement with the
/// HRW-derived desired replica set ({@link ReplicaPlacement}) for every `(stream, partition)`,
/// reconciling on every membership change. This is what finally makes replication live: prior to
/// this the registry was constructed but never populated in production.
///
/// ## What it does
/// On {@link #reconcile()} it snapshots the current core members and cluster size, then for each
/// stream from the {@link StreamCatalog}:
///   1. classifies it APP vs SYSTEM ({@code system:*} namespace ⇒ {@link StreamClass#SYSTEM}),
///   2. computes the effective replication factor (APP ⇒ configured `minSyncReplicas`;
///      SYSTEM ⇒ {@link ReplicaPlacement#systemReplicationFactor(int)}),
///   3. for each partition computes {@link ReplicaPlacement#place} and diffs the desired replica
///      set against {@link ReplicaRegistry#replicasFor}, issuing
///      {@link ReplicaRegistry#registerReplica} for newly-added nodes and
///      {@link ReplicaRegistry#unregisterReplica} for removed ones.
///
/// Reconciliation is **idempotent**: re-running with unchanged inputs issues zero register /
/// unregister calls.
///
/// ## Self-role
/// {@link #roleFor} reports whether THIS node is the {@code OWNER}, a (non-owner) {@code REPLICA},
/// or {@code NONE} for a given `(stream, partition)`, derived from the same placement. The B3
/// emit-gating increment calls {@link #isOwner} to decide whether to emit.
///
/// ## Catch-up seam (A4)
/// Catch-up / backfill is NOT implemented here. When reconcile detects that THIS node newly
/// became a replica for a partition that already holds data locally, it invokes the injected
/// `onBecameReplica` callback `(streamName, partition)`. The default is a no-op; A4 wires the real
/// backfill transport behind this seam. "Newly became a replica" is derived from the registry diff
/// itself (self appears among the to-add set for that partition), so the seam is naturally
/// idempotent — a steady-state reconcile fires it for nothing.
///
/// ## Concurrency & quorum
/// All reconciles are serialized on a single-thread executor, so the registry diff is never
/// interleaved. While the consensus engine is `PASSIVE` (out of quorum) reconciles are suppressed
/// to avoid thrashing placement against a transient minority view; the controller reconciles again
/// on the next `ACTIVE` edge. Wire {@link #onMembershipDecision} via the AetherNode membership tail
/// and {@link #onQuorumStateChange} to the `ClusterStateNotification` route.
public final class ReplicaSetController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReplicaSetController.class);

    private static final String SYSTEM_NAMESPACE_PREFIX = "system:";

    /// Self-role for a single `(stream, partition)` under the current placement.
    public enum Role {
        OWNER,
        REPLICA,
        NONE
    }

    private final ReplicaRegistry registry;
    private final NodeId self;
    private final Supplier<List<NodeId>> membersSupplier;
    private final IntSupplier clusterSizeSupplier;
    private final StreamCatalog catalog;
    private final BiConsumer<String, Integer> onBecameReplica;
    private final Executor executor;
    private final boolean ownsExecutor;

    private final AtomicBoolean passive = new AtomicBoolean(false);

    /// Membership snapshot captured by the LAST completed reconcile. `roleFor` / `isOwner` (the B3
    /// emit-gate) compute placement from THIS snapshot — the same generation the registry was
    /// reconciled against — so emit-ownership and replica-ownership never read the live topology at
    /// two different instants during a membership transition (which could make two nodes both-emit or
    /// both-drop an advisory event). Until the first reconcile lands it is `none()` and the gate falls
    /// back to a fresh supplier read (bootstrap window; a transient dup/drop of advisory,
    /// retention-bounded cluster-events there is acceptable).
    private final AtomicReference<Option<ReconciledView>> reconciledView = new AtomicReference<>(none());

    private record ReconciledView(List<NodeId> members, int clusterSize) {}

    private ReplicaSetController(ReplicaRegistry registry,
                                 NodeId self,
                                 Supplier<List<NodeId>> membersSupplier,
                                 IntSupplier clusterSizeSupplier,
                                 StreamCatalog catalog,
                                 BiConsumer<String, Integer> onBecameReplica,
                                 Executor executor,
                                 boolean ownsExecutor) {
        this.registry = registry;
        this.self = self;
        this.membersSupplier = membersSupplier;
        this.clusterSizeSupplier = clusterSizeSupplier;
        this.catalog = catalog;
        this.onBecameReplica = onBecameReplica;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /// Production factory: owns a dedicated single-thread executor for serialized reconciles and
    /// uses a no-op catch-up seam (A4 replaces it).
    public static ReplicaSetController replicaSetController(ReplicaRegistry registry,
                                                            NodeId self,
                                                            Supplier<List<NodeId>> membersSupplier,
                                                            IntSupplier clusterSizeSupplier,
                                                            StreamCatalog catalog) {
        return replicaSetController(registry, self, membersSupplier, clusterSizeSupplier, catalog, (_, _) -> {});
    }

    /// Production factory with an explicit catch-up seam. Owns a dedicated single-thread executor.
    public static ReplicaSetController replicaSetController(ReplicaRegistry registry,
                                                            NodeId self,
                                                            Supplier<List<NodeId>> membersSupplier,
                                                            IntSupplier clusterSizeSupplier,
                                                            StreamCatalog catalog,
                                                            BiConsumer<String, Integer> onBecameReplica) {
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "replica-set-controller");
            thread.setDaemon(true);
            return thread;
        });
        return new ReplicaSetController(registry, self, membersSupplier, clusterSizeSupplier, catalog, onBecameReplica, executor, true);
    }

    /// Test/advanced factory: caller supplies the executor (e.g. a same-thread executor for
    /// deterministic unit tests) and is responsible for its lifecycle.
    public static ReplicaSetController replicaSetController(ReplicaRegistry registry,
                                                            NodeId self,
                                                            Supplier<List<NodeId>> membersSupplier,
                                                            IntSupplier clusterSizeSupplier,
                                                            StreamCatalog catalog,
                                                            BiConsumer<String, Integer> onBecameReplica,
                                                            Executor executor) {
        return new ReplicaSetController(registry, self, membersSupplier, clusterSizeSupplier, catalog, onBecameReplica, executor, false);
    }

    /// Membership-tail hook: any {@link MembershipDecision} variant triggers a reconcile. Wire via
    /// `wireMembershipDecisionTail` in AetherNode.
    @Contract public void onMembershipDecision(MembershipDecision decision) {
        log.debug("ReplicaSetController: membership decision {} — scheduling reconcile", decision.getClass().getSimpleName());
        reconcile();
    }

    /// Quorum-state hook: track ACTIVE/PASSIVE. On a fresh ACTIVE edge, reconcile (the cluster view
    /// may have changed while we were suppressing). PASSIVE just flips the suppression flag.
    @Contract public void onQuorumStateChange(ClusterStateNotification notification) {
        var nowPassive = notification.state() == ClusterStateNotification.State.PASSIVE;
        var wasPassive = passive.getAndSet(nowPassive);
        if (wasPassive && !nowPassive) {
            log.debug("ReplicaSetController: PASSIVE -> ACTIVE — scheduling reconcile");
            reconcile();
        }
    }

    /// Schedule a serialized reconcile. Returns immediately; the work runs on the controller's
    /// executor. Safe to call from any thread and any number of times (coalesces naturally because
    /// each run recomputes from a fresh snapshot).
    @Contract public void reconcile() {
        executor.execute(this::reconcileNow);
    }

    private void reconcileNow() {
        if (passive.get()) {
            log.debug("ReplicaSetController: PASSIVE — skipping reconcile");
            return;
        }

        var members = List.copyOf(membersSupplier.get());
        if (members.isEmpty()) {
            log.debug("ReplicaSetController: no members yet — skipping reconcile");
            return;
        }

        var clusterSize = clusterSizeSupplier.getAsInt();

        // B3: publish the exact membership generation this reconcile is about to apply, so the
        // emit-gate (roleFor/isOwner) computes ownership from the SAME snapshot the registry is
        // reconciled against — not an independent live read taken at a different instant.
        reconciledView.set(some(new ReconciledView(members, clusterSize)));

        for (var spec : catalog.streams()) {
            reconcileStream(spec, members, clusterSize);
        }
    }

    private void reconcileStream(StreamCatalog.StreamSpec spec, List<NodeId> members, int clusterSize) {
        var streamClass = classify(spec.name());
        var rf = ReplicaPlacement.replicationFactor(streamClass, spec.minSyncReplicas(), clusterSize);

        for (var partition = 0; partition < spec.partitions(); partition++) {
            var p = partition;
            ReplicaPlacement.place(spec.name(), partition, members, rf)
                            .onPresent(placement -> reconcilePartition(spec.name(), p, placement));
        }
    }

    private void reconcilePartition(String streamName, int partition, Placement placement) {
        var desired = Set.copyOf(placement.replicas());

        var current = new HashSet<NodeId>();
        registry.replicasFor(streamName, partition).forEach(descriptor -> current.add(descriptor.nodeId()));

        // Removed: in registry but no longer desired.
        for (var node : current) {
            if (!desired.contains(node)) {
                registry.unregisterReplica(streamName, partition, node);
            }
        }

        // Added: desired but not yet in registry.
        for (var node : desired) {
            if (!current.contains(node)) {
                registry.registerReplica(streamName, partition, node);
                if (node.equals(self) && catalog.partitionHasData(streamName, partition)) {
                    // A4 seam: this node newly became a replica for a non-empty partition.
                    onBecameReplica.accept(streamName, partition);
                }
            }
        }
    }

    /// Self-role for `(stream, partition)` under the membership snapshot the controller last
    /// reconciled from (B3). Computed from the same placement the registry is reconciled against, so
    /// the emit-gate and the replica-set share one generation. Before the first reconcile the snapshot
    /// is absent and we fall back to a fresh supplier read (bootstrap window).
    public Role roleFor(String streamName, int partition) {
        var members = currentMembers();
        var streamClass = classify(streamName);
        var rf = rfFor(streamName, streamClass);

        return ReplicaPlacement.place(streamName, partition, members, rf)
                               .map(placement -> roleFrom(placement))
                               .or(Role.NONE);
    }

    /// Members of the last-reconciled snapshot, or a fresh supplier read before the first reconcile.
    private List<NodeId> currentMembers() {
        return reconciledView.get()
                             .map(ReconciledView::members)
                             .or(membersSupplier::get);
    }

    /// Cluster size of the last-reconciled snapshot, or a fresh supplier read before the first
    /// reconcile.
    private int currentClusterSize() {
        return reconciledView.get()
                             .map(ReconciledView::clusterSize)
                             .or(clusterSizeSupplier::getAsInt);
    }

    private Role roleFrom(Placement placement) {
        if (placement.owner().equals(self)) {
            return Role.OWNER;
        }
        return placement.replicas().contains(self) ? Role.REPLICA : Role.NONE;
    }

    /// True when THIS node is the owner of `(stream, partition)`. Called by the B3 emit-gating
    /// increment.
    public boolean isOwner(String streamName, int partition) {
        return roleFor(streamName, partition) == Role.OWNER;
    }

    private int rfFor(String streamName, StreamClass streamClass) {
        var clusterSize = currentClusterSize();
        var requested = catalog.streams()
                               .stream()
                               .filter(spec -> spec.name().equals(streamName))
                               .mapToInt(StreamCatalog.StreamSpec::minSyncReplicas)
                               .findFirst()
                               .orElse(0);
        return ReplicaPlacement.replicationFactor(streamClass, requested, clusterSize);
    }

    private static StreamClass classify(String streamName) {
        return streamName.startsWith(SYSTEM_NAMESPACE_PREFIX) ? SYSTEM : APP;
    }

    @Override public void close() {
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
