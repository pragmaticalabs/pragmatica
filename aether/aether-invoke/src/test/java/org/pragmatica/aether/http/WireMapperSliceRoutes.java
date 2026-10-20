// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

/// Fixture: a generated-style factory that records the JsonMapper the publisher hands it, so a test can
/// check which wire form every slice route JSON path of a published slice uses.
public final class WireMapperSliceRoutes implements RouteSource, SliceRouterFactory<WireMapperSlice> {
    static final AtomicReference<JsonMapper> RECEIVED = new AtomicReference<>();

    @Override
    public Class<WireMapperSlice> sliceType() {
        return WireMapperSlice.class;
    }

    @Override
    public int routeSecurityContract() {
        return 1;
    }

    @Override
    public SliceRouter create(WireMapperSlice slice) {
        return create(slice, JsonMapper.defaultJsonMapper());
    }

    @Override
    public SliceRouter create(WireMapperSlice slice, JsonMapper jsonMapper) {
        RECEIVED.set(jsonMapper);
        return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), jsonMapper);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<String>get("/wire/items")
                              .withoutParameters()
                              .to(_ -> Promise.success("ok"))
                              .named("items").withSecurity(SecurityPolicy.publicRoute())
                              .asJson());
    }
}
