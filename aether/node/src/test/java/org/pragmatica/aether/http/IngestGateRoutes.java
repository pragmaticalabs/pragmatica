// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalResolution;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;
import org.pragmatica.net.tcp.TlsConfig;

/// Routes, each with its declared ingest limits, all behind an API key:
/// `/api/small/` 1 MiB; `/api/big/` 100 MiB; `/api/undeclared/` none; `/api/upload/` an upload route;
/// `/api/timed/` an upload route with `max_duration = 1500 ms`; `/upload/` 1 MiB, for tests that post to it.
record IngestGateRoutes(SliceRouter router, SecurityPolicy security) implements HttpRoutePublisher {
    private static final int MIB = 1024 * 1024;
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final Map<String, UploadSpec> SPECS = Map.of("/api/small/",
                                                                UploadSpec.uploadSpec(false, MIB, 0),
                                                                "/api/big/",
                                                                UploadSpec.uploadSpec(false, 100L * MIB, 0),
                                                                "/api/undeclared/",
                                                                UploadSpec.NONE,
                                                                "/api/upload/",
                                                                UploadSpec.uploadSpec(true, 100L * MIB, 0),
                                                                "/api/timed/",
                                                                UploadSpec.uploadSpec(true, 100L * MIB, 1500),
                                                                "/upload/",
                                                                UploadSpec.uploadSpec(false, MIB, 0));

    private Option<LocalRouteInfo> routeFor(String method, String path) {
        return "POST".equalsIgnoreCase(method)
               ? SPECS.entrySet()
                      .stream()
                      .filter(entry -> path.startsWith(entry.getKey()))
                      .findFirst()
                      .map(entry -> Option.some(new LocalRouteInfo("POST",
                                                                   entry.getKey(),
                                                                   ARTIFACT.asString(),
                                                                   "upload",
                                                                   security,
                                                                   entry.getValue())))
                      .orElse(Option.none())
               : Option.none();
    }

    @Override
    public Option<LocalResolution> resolveLocal(String method, String path) {
        return routeFor(method, path).map(route -> new LocalResolution(route, Option.some(router)));
    }

    @Override
    public Set<HttpNodeRouteKey> allLocalRoutes() {
        return Set.of();
    }

    @Override
    public Option<SliceRouter> findLocalRouter(String method, String prefix) {
        return routeFor(method, prefix).map(_ -> router);
    }

    @Override
    public Option<LocalRouteInfo> findLocalRoute(String method, String path) {
        return routeFor(method, path);
    }

    @Override
    public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade) {
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> publishRoutes(Artifact artifact,
                                       ClassLoader classLoader,
                                       Object sliceInstance,
                                       SliceInvokerFacade invokerFacade) {
        return Promise.success(unit());
    }

    @Override
    public boolean hasRoutes(ClassLoader classLoader, Object sliceInstance) {
        return true;
    }

    @Override
    public Promise<Unit> unpublishRoutes(Artifact artifact) {
        return Promise.success(unit());
    }

    @Override
    public Option<HttpRequestHandler> getHandler(Artifact artifact) {
        return Option.none();
    }

    @Override
    public Option<SliceRouter> getSliceRouter(Artifact artifact) {
        return Option.some(router);
    }

    @Override
    public Unit updateSecurityOverrides(SecurityOverrides overrides) {
        return unit();
    }

    @Override
    public Unit setVersioningMetricsSink(VersioningMetricsSink sink) {
        return unit();
    }

    @Override
    public Map<Artifact, SliceVersionRegistry> versionRegistries() {
        return Map.of();
    }

    @Override
    public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
        return unit();
    }
}

