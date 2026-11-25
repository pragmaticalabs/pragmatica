/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http.server;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.TlsContextFactory;

import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.ssl.SslContext;


/// #2032: a listener's TLS context as a live input. The server reads [#current()] once per NEW connection (the HTTP/1.1
/// pipeline initializer, the QUIC engine provider), so [#swap] changes the certificate new connections are offered
/// without closing, rebinding or pausing the listener. Connections established before a swap finish on the context
/// they handshook with: their engine was created from it and keeps it.
///
/// A holder is built only from a context its protocol can serve: [#liveTls(SslContext)] admits a server-side, non-QUIC
/// context (HTTP/1.1); [#liveQuicTls(QuicSslContext)] admits a server-side QUIC context (HTTP/3). [#swap] applies the
/// same admission, and a refused context leaves the current one installed.
///
/// Reference counts. The holder never retains or releases a context: a reference-counted context stays owned by
/// whoever built it. The caller keeps the current context alive while it is installed and may release it once it is
/// swapped out, because every connection holds its own count for as long as it lives: an OpenSSL engine retains its
/// parent context when created and releases it when the engine is freed (Netty 4.2.18 `ReferenceCountedOpenSslEngine`
/// lines 319 and 185), and a QUIC connection retains the native context of its `QuicSslContext` (`QuicheQuicSslContext`
/// line 346) until the connection is freed (`QuicheQuicConnection.free`). The contexts this module's factories build
/// carry no count a caller must release: `TlsContextFactory` uses Netty's default provider, the JDK one here (OpenSSL
/// only with netty-tcnative present, and then the finalizer-released `OpenSslContext`, never `OPENSSL_REFCNT`), and a
/// `QuicheQuicSslContext` frees its native context in its finalizer once no connection holds it. Releasing a context
/// at swap would therefore free one the holder does not own: a later swap back to it, or the caller's own release,
/// would then act on a released context.
///
/// @param <C> the context type: `SslContext` for HTTP/1.1, `QuicSslContext` for HTTP/3
public final class LiveTls<C extends SslContext> {
    private final AtomicReference<C> current;
    private final Fn1<Result<C>, C> admission;

    private LiveTls(C context, Fn1<Result<C>, C> admission) {
        this.current = new AtomicReference<>(context);
        this.admission = admission;
    }

    /// An HTTP/1.1 holder over the server context built from `config`; fails when the context does not build.
    public static Result<LiveTls<SslContext>> liveTls(TlsConfig config) {
        return TlsContextFactory.createServer(config).flatMap(LiveTls::liveTls);
    }

    /// An HTTP/1.1 holder over `context`; refused unless it is a server-side, non-QUIC context.
    public static Result<LiveTls<SslContext>> liveTls(SslContext context) {
        return admitHttp1(context).map(admitted -> new LiveTls<>(admitted, LiveTls::admitHttp1));
    }

    /// An HTTP/3 holder over `context`; refused unless it is a server-side QUIC context.
    ///
    /// #2310: the context must also offer the `h3` ALPN token (`Http3.supportedApplicationProtocols()`); that is not
    /// checked here, as the server never checked it, and a context without it completes no handshake.
    public static Result<LiveTls<QuicSslContext>> liveQuicTls(QuicSslContext context) {
        return admitHttp3(context).map(admitted -> new LiveTls<>(admitted, LiveTls::admitHttp3));
    }

    /// The context the next new connection handshakes with.
    public C current() {
        return current.get();
    }

    /// Installs `context` for every connection opened from now on; connections already open keep theirs. A refused
    /// context changes nothing: the current one stays installed and the failure says why.
    public Result<Unit> swap(C context) {
        return admission.apply(context)
                        .onSuccess(current::set)
                        .mapToUnit();
    }

    private static Result<SslContext> admitHttp1(SslContext context) {
        return Verify.ensure(context, LiveTls::isHttp1ServerContext, HttpServerError.TlsContextRefused.NOT_HTTP1_SERVER);
    }

    private static Result<QuicSslContext> admitHttp3(QuicSslContext context) {
        return Verify.ensure(context, SslContext::isServer, HttpServerError.TlsContextRefused.NOT_HTTP3_SERVER);
    }

    /// A QUIC context builds no `SslHandler` (`QuicheQuicSslContext.newHandler` throws), so it cannot serve HTTP/1.1.
    private static boolean isHttp1ServerContext(SslContext context) {
        return context.isServer() && !(context instanceof QuicSslContext);
    }
}
