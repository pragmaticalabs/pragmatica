// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.VersionView;
import org.pragmatica.aether.api.ManagementApiResponses.VersionedSliceView;
import org.pragmatica.aether.api.ManagementApiResponses.VersionsResponse;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.SliceVersionRegistry.VersionInfo;
import org.pragmatica.lang.Option;


/// `GET /api/versions` (#198 §11.3) — version-registry introspection.
///
/// Lists the versioned slices this node has deployed, projecting each deployed slice's
/// [SliceVersionRegistry] (read from the local `HttpRoutePublisher`) onto the
/// [VersionsResponse] wire shape: slice coordinate, `apiPrefix`, detection knobs
/// (`requireVersionHeader`, `defaultVersion`), and per-version `{version, deprecated, sunset,
/// defaultIfMissing}`. A node hosting no versioned slice returns an empty list.
public final class VersionRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private VersionRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static VersionRoutes versionRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new VersionRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<VersionsResponse> route(ManagementRoute.VERSIONS).toJson(this::buildVersionsResponse));
    }

    private VersionsResponse buildVersionsResponse() {
        return new VersionsResponse(deployedRegistries().entrySet()
                                                      .stream()
                                                      .map(entry -> toSliceView(entry.getKey(),
                                                                                entry.getValue()))
                                                      .sorted(Comparator.comparing(VersionedSliceView::slice))
                                                      .toList());
    }

    private Map<Artifact, SliceVersionRegistry> deployedRegistries() {
        return nodeSupplier.get()
                           .appHttpServer()
                           .httpRoutePublisher()
                           .map(HttpRoutePublisher::versionRegistries)
                           .or(Map.of());
    }

    private static VersionedSliceView toSliceView(Artifact artifact, SliceVersionRegistry registry) {
        return new VersionedSliceView(artifact.asString(),
                                      registry.apiPrefix(),
                                      registry.requireVersionHeader(),
                                      registry.defaultVersion(),
                                      toVersionViews(registry));
    }

    private static List<VersionView> toVersionViews(SliceVersionRegistry registry) {
        return registry.versions()
                       .stream()
                       .map(info -> toVersionView(info,
                                                  registry.defaultVersion()))
                       .toList();
    }

    private static VersionView toVersionView(VersionInfo info, Option<Integer> defaultVersion) {
        return new VersionView(info.version(),
                               info.deprecated(),
                               info.sunset(),
                               isDefault(info.version(), defaultVersion));
    }

    private static boolean isDefault(int version, Option<Integer> defaultVersion) {
        return defaultVersion.map(value -> value == version)
                             .or(false);
    }
}
