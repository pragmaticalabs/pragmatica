// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpRequestHandlerFactory;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.lang.Promise;

/// Two [HttpRequestHandlerFactory] implementations advertising NESTED prefixes, one per artifact,
/// for [HttpRoutePublisherNestedPrefixTest] (#884). Neither is registered in
/// `META-INF/services` — the 3-arg `publishRoutes` takes the FIRST factory `ServiceLoader` finds on
/// the given class loader, so each artifact is published through a class loader whose service
/// lookup names exactly one of these.
final class NestedPrefixRouteFactories {
    static final String METHOD = "GET";
    static final String OUTER_PREFIX = "/api/v1/pricing/";
    static final String INNER_PREFIX = "/api/v1/pricing/analytics/";

    private NestedPrefixRouteFactories() {}

    public static final class OuterFactory implements HttpRequestHandlerFactory {
        @Override
        public HttpRequestHandler create(SliceInvokerFacade invoker) {
            return handler(HttpRouteDefinition.httpRouteDefinition(METHOD, OUTER_PREFIX, HttpRoutePublisherNestedPrefixTest.OUTER.asString(), "catalog"));
        }
    }

    public static final class InnerFactory implements HttpRequestHandlerFactory {
        @Override
        public HttpRequestHandler create(SliceInvokerFacade invoker) {
            return handler(HttpRouteDefinition.httpRouteDefinition(METHOD, INNER_PREFIX, HttpRoutePublisherNestedPrefixTest.INNER.asString(), "report"));
        }
    }

    private static HttpRequestHandler handler(HttpRouteDefinition route) {
        return new HttpRequestHandler() {
            @Override
            public Promise<HttpResponseData> handle(HttpRequestContext ctx) {
                return Promise.success(HttpResponseData.httpResponseData(204));
            }

            @Override
            public List<HttpRouteDefinition> routes() {
                return List.of(route);
            }
        };
    }
}
