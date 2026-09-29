// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.factoryslice;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1573 (v1608 R2-2), run against the GENERATED router: a request record built from path arguments with no
/// validating factory, whose canonical constructor throws on client input, is answered with a typed 400
/// before the slice method runs. Before the lift the throw escaped the handler, and the runtime's outcome
/// recorder counted it as the slice METHOD throwing — a defect toward automatic rollback caused by a bad path.
/// The recorder counts only a SliceDefect, never the HttpError this now is
/// (`HttpRoutePublisherOutcomeTest.routeReturningAFailure_isNotRecorded_evenThoughItMapsToAServerError`).
class GeneratedConstructorLiftRuntimeTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private final AtomicInteger strictCalls = new AtomicInteger();
    private SliceRouter router;

    @BeforeEach
    void setUp() {
        var delegate = FactorySlice.factorySlice();
        FactorySlice counting = new CountingSlice(delegate, strictCalls);

        router = new FactorySliceRoutes().create(counting, JsonMapper.defaultJsonMapper());
    }

    @Test
    void constructorThrowingOnClientInput_isATyped400_andTheSliceMethodNeverRuns() {
        var response = get("/api/factory/strict/bad-code");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(strictCalls.get()).as("the slice method is never invoked on a request it could not be built for").isZero();
    }

    @Test
    void constructorAcceptingTheInput_reachesTheSliceMethod() {
        var response = get("/api/factory/strict/good-code");

        assertThat(response.statusCode()).as("control: a valid path still serves").isEqualTo(200);
        assertThat(strictCalls.get()).isEqualTo(1);
    }

    private HttpResponseData get(String path) {
        return router.handle(HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req_strict"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()), response -> response);
    }

    private record CountingSlice(FactorySlice delegate, AtomicInteger strictCalls) implements FactorySlice {
        @Override
        public Promise<ShortResponse> shorten(ShortenRequest request) {
            return delegate.shorten(request);
        }

        @Override
        public Promise<ShortResponse> plain(PlainRequest request) {
            return delegate.plain(request);
        }

        @Override
        public Promise<ShortResponse> updateItem(UpdateItemRequest request) {
            return delegate.updateItem(request);
        }

        @Override
        public Promise<ShortResponse> lookup(LookupRequest request) {
            return delegate.lookup(request);
        }

        @Override
        public Promise<ShortResponse> strict(StrictRequest request) {
            strictCalls.incrementAndGet();
            return delegate.strict(request);
        }
    }
}
