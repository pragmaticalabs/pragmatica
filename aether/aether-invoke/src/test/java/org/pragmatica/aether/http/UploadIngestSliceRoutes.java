// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.stream.Stream;

import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

/// Fixture for the upload ingest gate: one slice with an upload route that declares `max_size = 5 MiB` and
/// `max_duration = 90 s`, and a plain route that declares nothing, as the route generator emits them.
public final class UploadIngestSliceRoutes implements RouteSource, SliceRouterFactory<UploadIngestSlice> {
    public static final UploadSpec DECLARED = UploadSpec.uploadSpec(true, 5L * 1024 * 1024, 90_000, 3, 2L * 1024 * 1024, 10, 4096);

    @Override
    public Class<UploadIngestSlice> sliceType() {
        return UploadIngestSlice.class;
    }

    @Override
    public int routeSecurityContract() {
        return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;
    }

    @Override
    public SliceRouter create(UploadIngestSlice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(UploadIngestSlice slice, JsonMapper jsonMapper) {
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>post("/uploads/")
                              .withoutParameters()
                              .to(_ -> Promise.success("stored"))
                              .named("upload")
                              .withSecurity(SecurityPolicy.authenticated())
                              .withUpload(DECLARED)
                              .asJson(),
                         Route.<String>get("/plain/")
                              .withoutParameters()
                              .to(_ -> Promise.success("plain"))
                              .named("plain")
                              .withSecurity(SecurityPolicy.authenticated())
                              .asJson());
    }
}
