// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.http;

import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #270 R6: a malformed path threw `IllegalArgumentException` synchronously out of `get`/`post`/…
/// (`Network.parseURI(uri).unwrap()`), so a slice calling the client got an exception across the
/// `Promise` boundary instead of a failed promise. Every request method is driven with the same two
/// malformed inputs: one `URI` refuses to parse (a space), one parses but `HttpRequest` refuses
/// (no scheme). In both cases the promise must FAIL and the backend must never be reached.
class JdkHttpClientMalformedUriTest {
    private static final String UNPARSEABLE = "http://example.com/a b";
    private static final String SCHEMELESS = "example.com/no-scheme";
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    /// Backend that must never be reached: counts arrivals, answers nothing useful.
    private static final class CountingOperations implements HttpOperations {
        private final AtomicInteger sends = new AtomicInteger();

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            sends.incrementAndGet();

            return Promise.failure(cause("backend reached with " + request.uri()));
        }
    }

    @Test
    void get_withUnparseablePath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.get(UNPARSEABLE));
    }

    @Test
    void get_withSchemelessPath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.get(SCHEMELESS));
    }

    @Test
    void post_withUnparseablePath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.post(UNPARSEABLE, "{}"));
    }

    @Test
    void put_withUnparseablePath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.put(UNPARSEABLE, "{}"));
    }

    @Test
    void patch_withUnparseablePath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.patch(UNPARSEABLE, "{}"));
    }

    @Test
    void delete_withUnparseablePath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.delete(UNPARSEABLE));
    }

    @Test
    void getBytes_withUnparseablePath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.getBytes(UNPARSEABLE));
    }

    @Test
    void getJson_withUnparseablePath_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.getJson(UNPARSEABLE, new TypeToken<String>() {}, none()));
    }

    /// SF-3 (review of #1080): with a `base_url` configured the base join ran BEFORE the lift, so a
    /// null path threw `NullPointerException` synchronously while the same null without a base
    /// failed the promise. Both must land in the `Result`.
    @Test
    void get_withNullPath_andBaseUrl_failsThePromise_doesNotThrow() {
        var operations = new CountingOperations();
        var client = JdkHttpClient.jdkHttpClient(config(some("http://example.com")), operations);

        assertFailsWithoutThrowing(client, operations, c -> c.get(null));
    }

    @Test
    void get_withNullPath_andNoBaseUrl_failsThePromise_doesNotThrow() {
        assertFailsWithoutThrowing(client -> client.get(null));
    }

    /// SF-1: the failure IS an `InvalidRequest`, terminal, and its detail is the exception's
    /// one-line message — not a stack trace (SF-2: `Causes.fromThrowable` puts 90 lines in
    /// `message()`, and `detail` used to be built from it).
    @Test
    void malformedUri_failsAs_terminalInvalidRequest_withOneLineDetail() {
        var operations = new CountingOperations();
        var client = JdkHttpClient.jdkHttpClient(config(none()), operations);
        var cause = client.get(UNPARSEABLE).await(TIMEOUT).fold(c -> c, _ -> fail("must fail"));

        assertThat(cause).isInstanceOf(HttpClientError.InvalidRequest.class);
        assertThat(cause.isTerminal()).as("the same arguments produce the same refusal").isTrue();
        assertThat(((HttpClientError.InvalidRequest) cause).uri()).isEqualTo(UNPARSEABLE);
        assertThat(((HttpClientError.InvalidRequest) cause).detail().lines().count()).as("detail is a message, not a trace")
                  .isEqualTo(1);
        assertThat(cause.message()).contains("Illegal character");
    }

    @Test
    void schemelessUri_failsAs_terminalInvalidRequest() {
        var operations = new CountingOperations();
        var client = JdkHttpClient.jdkHttpClient(config(none()), operations);
        var cause = client.get(SCHEMELESS).await(TIMEOUT).fold(c -> c, _ -> fail("must fail"));

        assertThat(cause).isInstanceOf(HttpClientError.InvalidRequest.class);
        assertThat(cause.isTerminal()).isTrue();
        assertThat(((HttpClientError.InvalidRequest) cause).detail().lines().count()).isEqualTo(1);
    }

    /// Control for the instrument: a well-formed path reaches the backend exactly once.
    @Test
    void get_withWellFormedPath_reachesTheBackend() {
        var operations = new CountingOperations();
        var client = JdkHttpClient.jdkHttpClient(config(none()), operations);

        client.get("http://example.com/ok").await(TIMEOUT);
        assertThat(operations.sends.get()).isEqualTo(1);
    }

    /// A malformed BASE URL is reported the same way: it is joined into every request.
    @Test
    void get_withMalformedBaseUrl_failsThePromise_doesNotThrow() {
        var operations = new CountingOperations();
        var client = JdkHttpClient.jdkHttpClient(config(some("http://bad host")), operations);

        assertFailsWithoutThrowing(client, operations, c -> c.get("/path"));
    }

    private static void assertFailsWithoutThrowing(Function<JdkHttpClient, Promise<?>> call) {
        var operations = new CountingOperations();
        var client = JdkHttpClient.jdkHttpClient(config(none()), operations);

        assertFailsWithoutThrowing(client, operations, call);
    }

    private static void assertFailsWithoutThrowing(JdkHttpClient client,
                                                   CountingOperations operations,
                                                   Function<JdkHttpClient, Promise<?>> call) {
        Promise<?> promise;

        try {
            promise = call.apply(client);
        } catch (RuntimeException e) {
            fail("request must not throw across the Promise boundary, threw " + e);

            return;
        }

        promise.await().onSuccess(_ -> fail("a malformed URI must fail the promise"));
        assertThat(operations.sends.get()).as("the backend must not be reached with a malformed URI").isZero();
    }

    private static HttpClientConfig config(Option<String> baseUrl) {
        return HttpClientConfig.httpClientConfig(baseUrl,
                                                 timeSpan(10).seconds(),
                                                 timeSpan(30).seconds(),
                                                 Redirect.NORMAL,
                                                 none(),
                                                 Map.of())
                               .unwrap();
    }
}
