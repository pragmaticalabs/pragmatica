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

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.http.HttpClientConfig.HttpBackend;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The `@Http` resource must be closeable AS A RESOURCE (#891).
///
/// `ResourceFactory`'s release dispatch sees only the provisioned object. `JdkHttpClient` HOLDS the
/// `HttpOperations` that owns the Netty `EventLoopGroup`, and a closeable held in a field is
/// invisible to that dispatch no matter how wide it gets — which is why #891's original diagnosis
/// (that `NettyHttpOperations` being `AsyncCloseable` was enough) was off by one level. Widening the
/// dispatch alone closes nothing here; the resource itself has to implement a convention.
class JdkHttpClientCloseTest {
    private static final TimeSpan TIMEOUT = timeSpan(10).seconds();

    /// Operations that own releasable state, standing in for the Netty backend: the only thing
    /// they record is whether their close ran.
    private static final class RecordingOperations implements HttpOperations, AsyncCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            return Promise.failure(Causes.cause("not under test"));
        }

        @Override
        public Promise<Unit> close() {
            closes.incrementAndGet();

            return Promise.unitPromise();
        }

        int closeCount() {
            return closes.get();
        }
    }

    private static HttpClientConfig configWith(HttpBackend backend) {
        return HttpClientConfig.httpClientConfig(none(),
                                                 timeSpan(10).seconds(),
                                                 timeSpan(30).seconds(),
                                                 Redirect.NORMAL,
                                                 none(),
                                                 Map.of(),
                                                 some(backend))
                               .unwrap();
    }

    /// The load-bearing assertion: revert `implements AsyncCloseable` on JdkHttpClient and this
    /// goes red, because the release path's `instanceof` check is exactly this one.
    @Test
    void client_isAsyncCloseable_soReleaseDispatchCanSeeIt() {
        assertThat(JdkHttpClient.jdkHttpClient(configWith(HttpBackend.JDK))).isInstanceOf(AsyncCloseable.class);
        assertThat(JdkHttpClient.jdkHttpClient(configWith(HttpBackend.NETTY))).isInstanceOf(AsyncCloseable.class);
    }

    /// The hop this module owns: `JdkHttpClient.close()` must reach the close of the operations
    /// it holds. The two real backends cannot show this — the JDK one owns nothing to observe and
    /// the Netty one's event loop is only measurable from outside the module (#895) — so the
    /// operations are supplied through the package-private seam. Replace the client's `close()`
    /// body with `Promise.unitPromise()` and this goes red; the two backend tests below do not.
    @Test
    void close_closesTheOperationsItOwns() {
        var operations = new RecordingOperations();
        var client = JdkHttpClient.jdkHttpClient(configWith(HttpBackend.NETTY), operations);

        assertThat(client.close()
                         .await(TIMEOUT)
                         .isSuccess()).isTrue();
        assertThat(operations.closeCount()).isEqualTo(1);
    }

    @Test
    void close_succeeds_withNettyBackend() {
        var client = JdkHttpClient.jdkHttpClient(configWith(HttpBackend.NETTY));

        assertThat(client.close()
                         .await(TIMEOUT)
                         .isSuccess()).isTrue();
    }

    /// The JDK backend's operations own no releasable state and implement no close convention, so
    /// the client's close is a success rather than a failure.
    @Test
    void close_succeeds_withJdkBackend() {
        var client = JdkHttpClient.jdkHttpClient(configWith(HttpBackend.JDK));

        assertThat(client.close()
                         .await(TIMEOUT)
                         .isSuccess()).isTrue();
    }
}
