// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistry;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.stream.Stream;


/// Read-only routes exposing the stream namespace registry.
///
/// - `GET /api/stream-namespaces` — snapshot of all registered streams.
/// - `GET /api/stream-namespaces/{namespace}/{stream}/{version}` — exact lookup.
///
/// Per the event-stream-namespaces spec (RC1-mandatory), the facade is always active. System
/// namespace admin-gating (spec §10) is applied at the HTTP auth layer, not at the route handler.
public final class StreamNamespacesRoutes implements RouteSource {
    private final StreamNamespacesService service;

    private StreamNamespacesRoutes(StreamNamespacesService service) {
        this.service = service;
    }

    public static StreamNamespacesRoutes streamNamespacesRoutes(StreamNamespacesService service) {
        return new StreamNamespacesRoutes(service);
    }

    public record StreamNamespacesListResponse(List<StreamRegistryEntryDto> entries) {}

    public record StreamNamespacesEntryResponse(StreamRegistryEntryDto entry) {}

    public record StreamRegistryEntryDto(String namespace,
                                          String stream,
                                          String version,
                                          String registeredBy,
                                          long registeredAtEpochMs,
                                          int refCount) {
        static StreamRegistryEntryDto fromEntry(StreamRegistryEntry entry) {
            return new StreamRegistryEntryDto(entry.address().namespace(),
                                               entry.address().stream(),
                                               entry.address().version().asString(),
                                               entry.registeredBy().name(),
                                               entry.registeredAtEpochMillis(),
                                               entry.refCount());
        }
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<StreamNamespacesListResponse>route(ManagementRoute.STREAM_NAMESPACES_LIST)
                                         .toJson(this::listAll),
                         ManagementRoutes.<StreamNamespacesEntryResponse>route(ManagementRoute.STREAM_NAMESPACES_GET)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString())
                                         .toResult(this::lookupEntry)
                                         .asJson());
    }

    private StreamNamespacesListResponse listAll() {
        var entries = service.snapshot().stream()
                             .map(StreamRegistryEntryDto::fromEntry)
                             .toList();
        return new StreamNamespacesListResponse(entries);
    }

    private Result<StreamNamespacesEntryResponse> lookupEntry(String namespace, String stream, String version) {
        return StreamAddress.streamAddress(namespace, stream, version)
                             .flatMap(address -> service.lookup(address)
                                                         .toResult(StreamRegistry.StreamRegistryError.General.NOT_FOUND))
                             .map(StreamRegistryEntryDto::fromEntry)
                             .map(StreamNamespacesEntryResponse::new);
    }
}
