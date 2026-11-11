// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package com.example.factoryslice;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #3019, run against the GENERATED router: a user-declared request record whose factory rejects its input is never
/// handed to the slice. For every route form that builds the record (pure body, path + body, query, query + body,
/// path + query, path + query + body) the invariant runs at the HTTP boundary: the rejected request is a typed 400 and
/// the slice method is not invoked; the control request with valid input is served and invokes it exactly once.
class GeneratedFactoryRejectionRuntimeTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private final AtomicInteger calls = new AtomicInteger();
    private SliceRouter router;

    @BeforeEach
    void setUp() {
        var delegate = FactorySlice.factorySlice();
        FactorySlice counting = (FactorySlice) Proxy.newProxyInstance(FactorySlice.class.getClassLoader(),
                                                                      new Class<?>[]{FactorySlice.class},
                                                                      (_, method, args) -> invokeCounting(delegate, method, args));

        router = new FactorySliceRoutes().create(counting, JsonMapper.defaultJsonMapper());
    }

    private Object invokeCounting(FactorySlice delegate, java.lang.reflect.Method method, Object[] args) throws Throwable {
        calls.incrementAndGet();

        return method.invoke(delegate, args);
    }

    @Test
    void pureBody_rejectedByFactory_isATyped400_andTheSliceNeverRuns() {
        assertRejected(send("POST", "/api/factory/shorten", Map.of(), "{\"url\":\"u\",\"ttlSeconds\":0}"));
    }

    @Test
    void pureBody_acceptedByFactory_reachesTheSlice() {
        assertServed(send("POST", "/api/factory/shorten", Map.of(), "{\"url\":\"u\",\"ttlSeconds\":5}"));
    }

    @Test
    void pathBody_rejectedByFactory_isATyped400_andTheSliceNeverRuns() {
        assertRejected(send("PUT", "/api/factory/items/0", Map.of(), "{\"name\":\"n\"}"));
    }

    @Test
    void pathBody_acceptedByFactory_reachesTheSlice() {
        assertServed(send("PUT", "/api/factory/items/7", Map.of(), "{\"name\":\"n\"}"));
    }

    @Test
    void query_rejectedByFactory_isATyped400_andTheSliceNeverRuns() {
        assertRejected(send("GET", "/api/factory/bytag", Map.of("tag", List.of(" ")), ""));
    }

    @Test
    void query_acceptedByFactory_reachesTheSlice() {
        assertServed(send("GET", "/api/factory/bytag", Map.of("tag", List.of("t")), ""));
    }

    @Test
    void queryBody_rejectedByFactory_isATyped400_andTheSliceNeverRuns() {
        assertRejected(send("POST", "/api/factory/tagged", Map.of("tag", List.of(" ")), "{\"name\":\"n\"}"));
    }

    @Test
    void queryBody_acceptedByFactory_reachesTheSlice() {
        assertServed(send("POST", "/api/factory/tagged", Map.of("tag", List.of("t")), "{\"name\":\"n\"}"));
    }

    @Test
    void pathQuery_rejectedByFactory_isATyped400_andTheSliceNeverRuns() {
        assertRejected(send("GET", "/api/factory/pt/abc", Map.of("tag", List.of(" ")), ""));
    }

    @Test
    void pathQuery_acceptedByFactory_reachesTheSlice() {
        assertServed(send("GET", "/api/factory/pt/abc", Map.of("tag", List.of("t")), ""));
    }

    @Test
    void pathQueryBody_rejectedByFactory_isATyped400_andTheSliceNeverRuns() {
        assertRejected(send("PUT", "/api/factory/ptb/abc", Map.of("tag", List.of("t")), "{\"name\":\" \"}"));
    }

    @Test
    void pathQueryBody_acceptedByFactory_reachesTheSlice() {
        assertServed(send("PUT", "/api/factory/ptb/abc", Map.of("tag", List.of("t")), "{\"name\":\"n\"}"));
    }

    private void assertRejected(HttpResponseData response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(calls.get()).as("the slice method is never invoked on a record its factory rejected").isZero();
    }

    private void assertServed(HttpResponseData response) {
        assertThat(response.statusCode()).as("control: valid input still serves").isEqualTo(200);
        assertThat(calls.get()).isEqualTo(1);
    }

    private HttpResponseData send(String method, String path, Map<String, List<String>> query, String body) {
        return router.handle(HttpRequestContext.httpRequestContext(path, method, query, Map.of(), body.getBytes(), "req_3019"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()), response -> response);
    }
}
