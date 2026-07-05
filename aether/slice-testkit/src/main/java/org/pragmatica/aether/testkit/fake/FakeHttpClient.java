// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.resource.http.HttpClientConfig;
import org.pragmatica.aether.testkit.TestKitError;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;

import java.net.http.HttpClient.Redirect;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Scripted, recording [HttpClient] fake (spec §3.3). Scripts string-body responses per
/// `(method, path)` via `onGet/onPost/...` and records every call for `httpCalls(...)` assertions.
///
/// Honest-guarantees note (spec §4.3): this proves the slice *made these HTTP calls and received
/// these scripted responses*. It does not exercise a real transport, headers, or JSON codecs — the
/// `*Json` and `getBytes` paths return [TestKitError.UnscriptedInteraction]; use a real backend
/// (WireMock/httpd, deferred) for those.
public final class FakeHttpClient implements HttpClient {
    private static final HttpClientConfig CONFIG = new HttpClientConfig(none(),
                                                                        TimeSpan.timeSpan(10).seconds(),
                                                                        TimeSpan.timeSpan(30).seconds(),
                                                                        Redirect.NORMAL,
                                                                        none(),
                                                                        Map.of(),
                                                                        none());

    private final Map<CallKey, HttpResult<String>> scripts = new ConcurrentHashMap<>();
    private final List<HttpCall> calls = new CopyOnWriteArrayList<>();

    private FakeHttpClient() {}

    public static FakeHttpClient scripted() {
        return new FakeHttpClient();
    }

    public FakeHttpClient onGet(String path, HttpResult<String> response) {
        return script("GET", path, response);
    }

    public FakeHttpClient onPost(String path, HttpResult<String> response) {
        return script("POST", path, response);
    }

    public FakeHttpClient onPut(String path, HttpResult<String> response) {
        return script("PUT", path, response);
    }

    public FakeHttpClient onPatch(String path, HttpResult<String> response) {
        return script("PATCH", path, response);
    }

    public FakeHttpClient onDelete(String path, HttpResult<String> response) {
        return script("DELETE", path, response);
    }

    /// Outbound HTTP calls the slice made, in call order.
    public List<HttpCall> calls() {
        return List.copyOf(calls);
    }

    private FakeHttpClient script(String method, String path, HttpResult<String> response) {
        scripts.put(new CallKey(method, path), response);

        return this;
    }

    private Promise<HttpResult<String>> respond(String method, String path, Option<String> body) {
        calls.add(new HttpCall(method, path, body));

        return option(scripts.get(new CallKey(method, path))).async(new TestKitError.UnscriptedInteraction("No scripted HTTP " + method
                                                                                                          + " response for path: " + path));
    }

    @Override
    public Promise<HttpResult<String>> get(String path) {
        return respond("GET", path, none());
    }

    @Override
    public Promise<HttpResult<String>> get(String path, Map<String, String> headers) {
        return respond("GET", path, none());
    }

    @Override
    public Promise<HttpResult<String>> post(String path, String body) {
        return respond("POST", path, some(body));
    }

    @Override
    public Promise<HttpResult<String>> post(String path, String body, Map<String, String> headers) {
        return respond("POST", path, some(body));
    }

    @Override
    public Promise<HttpResult<String>> put(String path, String body) {
        return respond("PUT", path, some(body));
    }

    @Override
    public Promise<HttpResult<String>> put(String path, String body, Map<String, String> headers) {
        return respond("PUT", path, some(body));
    }

    @Override
    public Promise<HttpResult<String>> patch(String path, String body) {
        return respond("PATCH", path, some(body));
    }

    @Override
    public Promise<HttpResult<String>> patch(String path, String body, Map<String, String> headers) {
        return respond("PATCH", path, some(body));
    }

    @Override
    public Promise<HttpResult<String>> delete(String path) {
        return respond("DELETE", path, none());
    }

    @Override
    public Promise<HttpResult<String>> delete(String path, Map<String, String> headers) {
        return respond("DELETE", path, none());
    }

    @Override
    public Promise<HttpResult<byte[]>> getBytes(String path) {
        return unsupported("getBytes " + path);
    }

    @Override
    public Promise<HttpResult<byte[]>> getBytes(String path, Map<String, String> headers) {
        return unsupported("getBytes " + path);
    }

    @Override
    public HttpClientConfig config() {
        return CONFIG;
    }

    @Override
    public <T> Promise<T> getJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return unsupported("getJson " + path);
    }

    @Override
    public <T> Promise<T> postJson(String path,
                                   Object body,
                                   TypeToken<T> responseType,
                                   Option<TypeToken<?>> errorType) {
        return unsupported("postJson " + path);
    }

    @Override
    public <T> Promise<T> putJson(String path, Object body, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return unsupported("putJson " + path);
    }

    @Override
    public <T> Promise<T> patchJson(String path,
                                    Object body,
                                    TypeToken<T> responseType,
                                    Option<TypeToken<?>> errorType) {
        return unsupported("patchJson " + path);
    }

    @Override
    public <T> Promise<T> deleteJson(String path, TypeToken<T> responseType, Option<TypeToken<?>> errorType) {
        return unsupported("deleteJson " + path);
    }

    @Override
    public Promise<Unit> deleteJsonVoid(String path) {
        return unsupported("deleteJson " + path);
    }

    private <T> Promise<T> unsupported(String operation) {
        return new TestKitError.UnscriptedInteraction("FakeHttpClient scripts string-body HTTP only; " + operation
                                                     + " is unsupported (MVP). Use onGet/onPost/... or a real backend.").promise();
    }

    private record CallKey(String method, String path) {}
}
