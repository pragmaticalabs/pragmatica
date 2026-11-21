package org.pragmatica.testlogging;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/// A JDK [HttpClient] whose every send fails as a refused connection does: an already failed future carrying a
/// [ConnectException] with no message, exactly what the JDK client reports for an RST (#3558). For tests of how a
/// refusal is TYPED, which must not depend on what the kernel does with a loopback port under load (#3319, #3321).
/// Hand it to `JdkHttpOperations.jdkHttpOperations(HttpClient)`; the caller's dependent stages then wrap the failure
/// in a `CompletionException`, as they do for a real refusal. Test-only.
public final class RefusingHttpClient extends HttpClient {
    private RefusingHttpClient() {}

    public static RefusingHttpClient refusingHttpClient() {
        return new RefusingHttpClient();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) throws IOException {
        throw new ConnectException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler) {
        return CompletableFuture.failedFuture(new ConnectException());
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            BodyHandler<T> handler,
                                                            PushPromiseHandler<T> pushHandler) {
        return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException("a refusing client has no TLS context");
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    /// Nothing is ever open, so the JDK's default `close()` (which waits for termination) must not wait.
    @Override
    public void shutdown() {}

    @Override
    public void shutdownNow() {}

    @Override
    public boolean isTerminated() {
        return true;
    }

    @Override
    public boolean awaitTermination(Duration duration) {
        return true;
    }
}
