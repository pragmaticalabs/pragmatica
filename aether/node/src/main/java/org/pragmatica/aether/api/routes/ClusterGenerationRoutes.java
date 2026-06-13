// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.function.Supplier;
import java.util.stream.Stream;


public final class ClusterGenerationRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private ClusterGenerationRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterGenerationRoutes clusterGenerationRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterGenerationRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClusterGenerationResponse> route(ManagementRoute.CLUSTER_GENERATION).toJson(this::buildGenerationResponse));
    }

    private ClusterGenerationResponse buildGenerationResponse() {
        return ClusterGenerationAssembler.assemble(nodeSupplier.get());
    }
}
