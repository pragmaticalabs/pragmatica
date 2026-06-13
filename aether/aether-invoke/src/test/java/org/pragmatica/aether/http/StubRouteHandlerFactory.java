// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpRequestHandlerFactory;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.lang.Promise;

import java.util.List;

/// ServiceLoader-discoverable [`HttpRequestHandlerFactory`] used only by
/// [`HttpRoutePublisherConsensusRetryTest`]. It produces a handler advertising a single HTTP
/// route, which is the minimum needed to drive `publishRoutes(...)` into the consensus
/// apply-with-retry path under test (FIX 2b / #325). Registered via
/// `src/test/resources/META-INF/services/org.pragmatica.aether.http.handler.HttpRequestHandlerFactory`.
public final class StubRouteHandlerFactory implements HttpRequestHandlerFactory {
    private static final HttpRouteDefinition ROUTE =
            HttpRouteDefinition.httpRouteDefinition("GET", "/stub/", "org.example:stub:1.0.0", "list");

    @Override
    public HttpRequestHandler create(SliceInvokerFacade invoker) {
        return new HttpRequestHandler() {
            @Override
            public Promise<HttpResponseData> handle(HttpRequestContext ctx) {
                return Promise.success(HttpResponseData.httpResponseData(204));
            }

            @Override
            public List<HttpRouteDefinition> routes() {
                return List.of(ROUTE);
            }
        };
    }
}
