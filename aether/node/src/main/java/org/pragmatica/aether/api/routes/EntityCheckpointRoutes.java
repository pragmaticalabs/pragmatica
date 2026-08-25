// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.EntityCheckpointsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.EntityKeyspaceCheckpointView;
import org.pragmatica.aether.api.ManagementApiResponses.EntityKeyspaceView;
import org.pragmatica.aether.api.ManagementApiResponses.EntityKeyspacesResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.EntityOwnershipReconciler;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;


/// Per-node durable-entity checkpoint observability (#345 I3) plus the keyspace HOSTING view
/// (#634-3 fold-in).
///
/// ## Why the checkpoint surface exists
/// A checkpoint is the ONLY thing that ever bounds an entity log: the retention floor refuses to reclaim
/// anything at or above a partition's committed checkpoint, so until one is written nothing is reclaimed
/// at all. A checkpoint driver that silently stopped therefore produces no immediate symptom — writes
/// still succeed, reads still succeed, failover still works — and surfaces hours later as unbounded disk
/// growth with nothing pointing at the cause.
///
/// Before this, the driver logged only FAILURES, which meant a driver that never ran and a driver that
/// ran perfectly produced identical output. `writes` is the positive signal that distinguishes them.
///
/// ## Why the keyspaces surface exists
/// The set of committed per-node registrations IS the hosting set the leader mints entity-arc owners
/// over (the 02w hosting-set fix) — and it had no operator surface: the defect was diagnosed from typed
/// write refusals instead of one GET. Assembled from replicated KV, so any caught-up node answers
/// identically; the checkpoint view beside it is per-node driver state, and the two deliberately stay
/// distinct responses rather than one blended report claiming a single source.
///
/// Both are assembled on request from values the code already maintains — no hot-path cost, per the
/// observability-first rule (additive capture, never log-scraping).
public final class EntityCheckpointRoutes implements RouteSource {
    private final EntityCheckpointDriver driver;
    private final Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier;

    private EntityCheckpointRoutes(EntityCheckpointDriver driver,
                                   Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        this.driver = driver;
        this.kvStoreSupplier = kvStoreSupplier;
    }

    public static EntityCheckpointRoutes entityCheckpointRoutes(EntityCheckpointDriver driver,
                                                                Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        return new EntityCheckpointRoutes(driver, kvStoreSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<EntityCheckpointsResponse> route(ManagementRoute.ENTITY_CHECKPOINTS).toJson(this::checkpoints),
                         ManagementRoutes.<EntityKeyspacesResponse> route(ManagementRoute.ENTITY_KEYSPACES).toJson(this::keyspaces));
    }

    /// An empty `keyspaces` list means this node hosts no durable-entity keyspace — a true and useful
    /// answer, distinct from an error, and distinct from a keyspace whose `writes` is stuck at zero.
    private EntityCheckpointsResponse checkpoints() {
        return new EntityCheckpointsResponse(driver.snapshot()
                                                   .keyspaces()
                                                   .stream()
                                                   .map(EntityCheckpointRoutes::toView)
                                                   .toList());
    }

    private EntityKeyspacesResponse keyspaces() {
        return assembleKeyspaces(kvStoreSupplier.get());
    }

    /// Package-visible assembler (the `ClusterTopologyRoutes` precedent) so the hosting view is unit
    /// testable off a seeded store. A pure PROJECTION over
    /// [EntityOwnershipReconciler#scanRegistrations] — the single authority on the merge semantics —
    /// so the operator surface can never drift from what the leader acts on (review catch: the first
    /// version re-implemented the merge with no equivalence test). Hosts are sorted and keyspaces
    /// ordered by name so the response is stable across reads.
    static EntityKeyspacesResponse assembleKeyspaces(KVStore<AetherKey, AetherValue> kvStore) {
        return new EntityKeyspacesResponse(EntityOwnershipReconciler.scanRegistrations(kvStore)
                                                                    .entrySet()
                                                                    .stream()
                                                                    .sorted(Map.Entry.comparingByKey())
                                                                    .map(entry -> toKeyspaceView(entry.getKey(),
                                                                                                 entry.getValue()))
                                                                    .toList());
    }

    private static EntityKeyspaceView toKeyspaceView(String keyspace, EntityOwnershipReconciler.HostedKeyspace hosted) {
        var hosts = hosted.hosts().stream().map(NodeId::id).sorted().toList();

        return new EntityKeyspaceView(keyspace, hosted.partitionCount(), hosts, hosted.countsDisagree());
    }

    private static EntityKeyspaceCheckpointView toView(EntityCheckpointDriver.KeyspaceCheckpoints keyspace) {
        return new EntityKeyspaceCheckpointView(keyspace.keyspace(),
                                                keyspace.partitionCount(),
                                                keyspace.writes(),
                                                keyspace.failures(),
                                                keyspace.checkpointedThrough());
    }
}
