// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.EntityCheckpointsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.EntityKeyspaceCheckpointView;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;


/// Per-node durable-entity checkpoint observability (#345 I3).
///
/// ## Why this surface exists
/// A checkpoint is the ONLY thing that ever bounds an entity log: the retention floor refuses to reclaim
/// anything at or above a partition's committed checkpoint, so until one is written nothing is reclaimed
/// at all. A checkpoint driver that silently stopped therefore produces no immediate symptom — writes
/// still succeed, reads still succeed, failover still works — and surfaces hours later as unbounded disk
/// growth with nothing pointing at the cause.
///
/// Before this, the driver logged only FAILURES, which meant a driver that never ran and a driver that
/// ran perfectly produced identical output. `writes` is the positive signal that distinguishes them.
///
/// Assembled on request from counters the tick already maintains — no hot-path cost, per the
/// observability-first rule (additive capture of values the code already computes, never log-scraping).
public final class EntityCheckpointRoutes implements RouteSource {
    private final EntityCheckpointDriver driver;

    private EntityCheckpointRoutes(EntityCheckpointDriver driver) {
        this.driver = driver;
    }

    public static EntityCheckpointRoutes entityCheckpointRoutes(EntityCheckpointDriver driver) {
        return new EntityCheckpointRoutes(driver);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<EntityCheckpointsResponse> route(ManagementRoute.ENTITY_CHECKPOINTS).toJson(this::checkpoints));
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

    private static EntityKeyspaceCheckpointView toView(EntityCheckpointDriver.KeyspaceCheckpoints keyspace) {
        return new EntityKeyspaceCheckpointView(keyspace.keyspace(),
                                                keyspace.partitionCount(),
                                                keyspace.writes(),
                                                keyspace.failures(),
                                                keyspace.checkpointedThrough());
    }
}
