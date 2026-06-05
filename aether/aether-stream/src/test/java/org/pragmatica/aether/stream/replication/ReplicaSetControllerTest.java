// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaSetControllerTest {
    private static final String APP_STREAM = "orders.v1:events:1.0.0";
    private static final int PARTITIONS = 3;

    private static NodeId node(String id) {
        return new NodeId(id);
    }

    private static List<NodeId> nodes(String... ids) {
        var list = new ArrayList<NodeId>();
        for (var id : ids) {
            list.add(node(id));
        }
        return list;
    }

    // ---- fakes ---------------------------------------------------------------------------------

    private record FakeCatalog(List<StreamCatalog.StreamSpec> specs, Set<PartitionKey> nonEmpty) implements StreamCatalog {
        @Override public List<StreamSpec> streams() {
            return specs;
        }

        @Override public boolean partitionHasData(String streamName, int partition) {
            return nonEmpty.contains(new PartitionKey(streamName, partition));
        }
    }

    /// Build a controller wired to a real ReplicaRegistry, driven on the calling thread (the
    /// `Runnable::run` executor) so reconciles complete synchronously for deterministic assertions.
    private static ReplicaSetController controller(ReplicaRegistry registry,
                                                   NodeId self,
                                                   AtomicReference<List<NodeId>> members,
                                                   StreamCatalog catalog,
                                                   java.util.function.BiConsumer<String, Integer> onBecameReplica) {
        return ReplicaSetController.replicaSetController(registry,
                                                        self,
                                                        members::get,
                                                        () -> members.get().size(),
                                                        catalog,
                                                        onBecameReplica,
                                                        Runnable::run);
    }

    private static StreamCatalog appCatalog(int minSyncReplicas, Set<PartitionKey> nonEmpty) {
        return new FakeCatalog(List.of(new StreamCatalog.StreamSpec(APP_STREAM, PARTITIONS, minSyncReplicas)), nonEmpty);
    }

    /// Counting assignment store: register => assigned=true, unregister => assigned=false.
    private static final class CountingStore implements ReplicaAssignmentStore {
        final AtomicInteger registers = new AtomicInteger();
        final AtomicInteger unregisters = new AtomicInteger();

        @Override public void persistAssignment(String streamName, int partition, NodeId nodeId, boolean assigned) {
            if (assigned) {
                registers.incrementAndGet();
            } else {
                unregisters.incrementAndGet();
            }
        }
    }

    private static Set<NodeId> desiredReplicas(String stream, int partition, List<NodeId> members, int rf) {
        return ReplicaPlacement.place(stream, partition, members, rf)
                               .map(Placement::replicas)
                               .map(Set::copyOf)
                               .or(Set.of());
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    void reconcilePopulatesRegistryToMatchPlacement() {
        var registry = ReplicaRegistry.replicaRegistry();
        var members = new AtomicReference<>(nodes("n0", "n1", "n2", "n3"));
        var rf = 2;
        var ctrl = controller(registry, node("n0"), members, appCatalog(rf, Set.of()), (_, _) -> {});

        ctrl.reconcile();

        for (var p = 0; p < PARTITIONS; p++) {
            var actual = new HashSet<NodeId>();
            registry.replicasFor(APP_STREAM, p).forEach(d -> actual.add(d.nodeId()));
            assertThat(actual).isEqualTo(desiredReplicas(APP_STREAM, p, members.get(), rf));
        }
    }

    @Test
    void membershipChangeRemovesAndAddsReplicasViaDiff() {
        var registry = ReplicaRegistry.replicaRegistry();
        var members = new AtomicReference<>(nodes("n0", "n1", "n2", "n3", "n4"));
        var rf = 3;
        var ctrl = controller(registry, node("n0"), members, appCatalog(rf, Set.of()), (_, _) -> {});
        ctrl.reconcile();

        // Pick a partition whose replica set includes n2, and one that does not.
        Integer involved = null;
        Integer uninvolved = null;
        var before = new java.util.HashMap<Integer, Set<NodeId>>();
        for (var p = 0; p < PARTITIONS; p++) {
            var set = registry.replicasFor(APP_STREAM, p).stream().map(ReplicaDescriptor::nodeId).collect(java.util.stream.Collectors.toSet());
            before.put(p, set);
            if (set.contains(node("n2")) && involved == null) {
                involved = p;
            }
            if (!set.contains(node("n2")) && uninvolved == null) {
                uninvolved = p;
            }
        }
        assertThat(involved).as("expected a partition whose replica set includes n2").isNotNull();

        // Remove n2 and reconcile.
        members.set(nodes("n0", "n1", "n3", "n4"));
        ctrl.reconcile();

        // n2 is gone everywhere it was, replaced by the next-ranked surviving node.
        var afterInvolved = registry.replicasFor(APP_STREAM, involved).stream().map(ReplicaDescriptor::nodeId).collect(java.util.stream.Collectors.toSet());
        assertThat(afterInvolved).doesNotContain(node("n2"));
        assertThat(afterInvolved).isEqualTo(desiredReplicas(APP_STREAM, involved, members.get(), rf));

        if (uninvolved != null) {
            var afterUninvolved = registry.replicasFor(APP_STREAM, uninvolved).stream().map(ReplicaDescriptor::nodeId).collect(java.util.stream.Collectors.toSet());
            assertThat(afterUninvolved).isEqualTo(before.get(uninvolved));
        }
    }

    @Test
    void secondReconcileWithSameInputsIssuesZeroMutations() {
        var store = new CountingStore();
        var registry = ReplicaRegistry.replicaRegistry(WatermarkStore.NOOP, store);
        var members = new AtomicReference<>(nodes("n0", "n1", "n2", "n3"));
        var ctrl = controller(registry, node("n0"), members, appCatalog(2, Set.of()), (_, _) -> {});

        ctrl.reconcile();
        var registersAfterFirst = store.registers.get();
        var unregistersAfterFirst = store.unregisters.get();
        assertThat(registersAfterFirst).isGreaterThan(0);

        ctrl.reconcile();
        assertThat(store.registers.get()).isEqualTo(registersAfterFirst);
        assertThat(store.unregisters.get()).isEqualTo(unregistersAfterFirst);
    }

    @Test
    void selfRoleOwnerReplicaNone() {
        var members = nodes("n0", "n1", "n2", "n3", "n4");
        var rf = 3;

        for (var p = 0; p < PARTITIONS; p++) {
            var placement = ReplicaPlacement.place(APP_STREAM, p, members, rf).or(() -> {
                throw new AssertionError("placement");
            });
            var owner = placement.owner();
            var replicaSet = Set.copyOf(placement.replicas());

            // Owner node sees OWNER and isOwner true.
            var ownerCtrl = controller(ReplicaRegistry.replicaRegistry(), owner, new AtomicReference<>(members), appCatalog(rf, Set.of()), (_, _) -> {});
            assertThat(ownerCtrl.roleFor(APP_STREAM, p)).isEqualTo(Role.OWNER);
            assertThat(ownerCtrl.isOwner(APP_STREAM, p)).isTrue();

            // A non-owner replica sees REPLICA, isOwner false.
            var replicaNode = replicaSet.stream().filter(n -> !n.equals(owner)).findFirst().orElseThrow();
            var replicaCtrl = controller(ReplicaRegistry.replicaRegistry(), replicaNode, new AtomicReference<>(members), appCatalog(rf, Set.of()), (_, _) -> {});
            assertThat(replicaCtrl.roleFor(APP_STREAM, p)).isEqualTo(Role.REPLICA);
            assertThat(replicaCtrl.isOwner(APP_STREAM, p)).isFalse();

            // A node outside the replica set sees NONE.
            var outsider = members.stream().filter(n -> !replicaSet.contains(n)).findFirst().orElseThrow();
            var outsiderCtrl = controller(ReplicaRegistry.replicaRegistry(), outsider, new AtomicReference<>(members), appCatalog(rf, Set.of()), (_, _) -> {});
            assertThat(outsiderCtrl.roleFor(APP_STREAM, p)).isEqualTo(Role.NONE);
            assertThat(outsiderCtrl.isOwner(APP_STREAM, p)).isFalse();
        }
    }

    @Test
    void onBecameReplicaFiresOnlyForNewlyAddedNonEmptySelfReplicaPartitions() {
        var members = nodes("n0", "n1", "n2", "n3", "n4");
        var rf = 3;

        // Choose self = a node that is a replica of at least one partition. Find such a node.
        NodeId self = null;
        int selfPartition = -1;
        for (var candidate : members) {
            for (var p = 0; p < PARTITIONS; p++) {
                if (desiredReplicas(APP_STREAM, p, members, rf).contains(candidate)) {
                    self = candidate;
                    selfPartition = p;
                    break;
                }
            }
            if (self != null) {
                break;
            }
        }
        assertThat(self).isNotNull();

        // Mark the partition where self is a replica as non-empty so the seam fires.
        var nonEmpty = Set.of(new PartitionKey(APP_STREAM, selfPartition));
        var fired = new ArrayList<PartitionKey>();
        var registry = ReplicaRegistry.replicaRegistry();
        var ctrl = controller(registry,
                              self,
                              new AtomicReference<>(members),
                              appCatalog(rf, nonEmpty),
                              (stream, partition) -> fired.add(new PartitionKey(stream, partition)));

        ctrl.reconcile();

        // Fired for the non-empty partition where self newly became a replica.
        assertThat(fired).contains(new PartitionKey(APP_STREAM, selfPartition));
        // Did NOT fire for empty partitions even if self is a replica there.
        var firstFiredCount = fired.size();

        // Second reconcile: self already a replica everywhere — no new "became replica" => no new fires.
        ctrl.reconcile();
        assertThat(fired).hasSize(firstFiredCount);
    }

    @Test
    void passiveStateSuppressesReconcile() {
        var store = new CountingStore();
        var registry = ReplicaRegistry.replicaRegistry(WatermarkStore.NOOP, store);
        var members = new AtomicReference<>(nodes("n0", "n1", "n2", "n3"));
        var ctrl = controller(registry, node("n0"), members, appCatalog(2, Set.of()), (_, _) -> {});

        ctrl.onQuorumStateChange(new ClusterStateNotification(ClusterStateNotification.State.PASSIVE, 1L));
        ctrl.reconcile();
        // membership decision also routes through reconcile, still suppressed.
        ctrl.onMembershipDecision(MembershipDecision.nodeRemoved(node("n3"), members.get()));

        assertThat(store.registers.get()).isZero();
        assertThat(store.unregisters.get()).isZero();

        // ACTIVE edge re-enables and triggers a reconcile.
        ctrl.onQuorumStateChange(new ClusterStateNotification(ClusterStateNotification.State.ACTIVE, 2L));
        assertThat(store.registers.get()).isGreaterThan(0);
    }

    @Test
    void systemStreamUsesSystemReplicationFactor() {
        var systemStream = "system:cluster-events:1.0.0";
        var members = nodes("n0", "n1", "n2", "n3", "n4");
        var registry = ReplicaRegistry.replicaRegistry();
        // minSyncReplicas deliberately set to 1 — should be IGNORED for system streams.
        var catalog = new FakeCatalog(List.of(new StreamCatalog.StreamSpec(systemStream, 1, 1)), Set.of());
        var ctrl = controller(registry, node("n0"), new AtomicReference<>(members), catalog, (_, _) -> {});

        ctrl.reconcile();

        var expectedRf = ReplicaPlacement.systemReplicationFactor(members.size()); // N=5 -> 3
        var actual = registry.replicasFor(systemStream, 0).size();
        assertThat(actual).isEqualTo(expectedRf);
        assertThat(actual).isNotEqualTo(1);
    }

    @Test
    void steadyStateSingleNode_isOwner_afterReconcile() {
        // B3: a steady-state single-node cluster IS the owner and emits.
        var members = new AtomicReference<>(nodes("solo"));
        var ctrl = controller(ReplicaRegistry.replicaRegistry(), node("solo"), members, appCatalog(1, Set.of()), (_, _) -> {});

        ctrl.reconcile();

        for (var p = 0; p < PARTITIONS; p++) {
            assertThat(ctrl.isOwner(APP_STREAM, p)).isTrue();
            assertThat(ctrl.roleFor(APP_STREAM, p)).isEqualTo(Role.OWNER);
        }
    }

    @Test
    void ownerGate_usesLastReconciledSnapshot_notFreshLiveRead() {
        // B3: emit-ownership (isOwner/roleFor) must be computed from the SAME membership snapshot the
        // controller last reconciled from — not an independent live read taken at a different instant.
        // Otherwise, during a membership transition, two nodes reading their local topology at
        // different moments could both-emit or both-drop an advisory event.
        var members = new AtomicReference<>(nodes("n0", "n1", "n2", "n3", "n4"));
        var rf = 3;

        // Find a partition + a self node that is OWNER under the 5-node membership.
        NodeId self = null;
        int ownedPartition = -1;
        for (var p = 0; p < PARTITIONS && self == null; p++) {
            var owner = ReplicaPlacement.place(APP_STREAM, p, members.get(), rf).or(() -> {
                throw new AssertionError("placement");
            }).owner();
            self = owner;
            ownedPartition = p;
        }
        assertThat(self).isNotNull();

        var ctrl = controller(ReplicaRegistry.replicaRegistry(), self, members, appCatalog(rf, Set.of()), (_, _) -> {});

        // Reconcile against the 5-node view: self is OWNER of ownedPartition, snapshot now pinned.
        ctrl.reconcile();
        assertThat(ctrl.isOwner(APP_STREAM, ownedPartition)).isTrue();

        // Now mutate the LIVE membership to a single foreign node WITHOUT reconciling. A fresh live
        // read would recompute ownership against {foreign} (self not even a member → NONE). The pinned
        // reconciled snapshot must keep self OWNER until the next reconcile.
        members.set(nodes("foreign-only"));
        assertThat(ctrl.isOwner(APP_STREAM, ownedPartition)).isTrue();
        assertThat(ctrl.roleFor(APP_STREAM, ownedPartition)).isEqualTo(Role.OWNER);

        // After reconciling against the new view, ownership follows the new snapshot (self gone → NONE).
        ctrl.reconcile();
        assertThat(ctrl.isOwner(APP_STREAM, ownedPartition)).isFalse();
        assertThat(ctrl.roleFor(APP_STREAM, ownedPartition)).isEqualTo(Role.NONE);
    }
}
