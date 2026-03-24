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

import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.quic.QuicSslContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.BiConsumer;

/// HTTP server abstraction.
public interface HttpServer {
    /// Server port.
    int port();

    /// Stop the server.
    Promise<Unit> stop();

    /// Create and start an HTTP server.
    ///
    /// @param config  server configuration
    /// @param handler request handler
    /// @return promise of the running server
    static Promise<HttpServer> httpServer(HttpServerConfig config, BiConsumer<RequestContext, ResponseWriter> handler) {
        return NettyHttpServer.create(config, handler);
    }

    /// Create and start an HTTP server using externally managed event loop groups.
    ///
    /// @param config      server configuration
    /// @param handler     request handler
    /// @param bossGroup   external boss event loop group
    /// @param workerGroup external worker event loop group
    /// @return promise of the running server
    static Promise<HttpServer> httpServer(HttpServerConfig config,
                                          BiConsumer<RequestContext, ResponseWriter> handler,
                                          EventLoopGroup bossGroup,
                                          EventLoopGroup workerGroup) {
        return NettyHttpServer.createShared(config, handler, bossGroup, workerGroup);
    }

    /// Create and start an HTTP/3 server over QUIC.
    ///
    /// @param config         server configuration
    /// @param quicSslContext QUIC-specific SSL context (TLS 1.3 required for HTTP/3)
    /// @param handler        request handler (shared with HTTP/1.1 pipeline)
    /// @return promise of the running HTTP/3 server
    static Promise<HttpServer> http3Server(HttpServerConfig config,
                                           QuicSslContext quicSslContext,
                                           BiConsumer<RequestContext, ResponseWriter> handler) {
        return Http3Server.create(config, quicSslContext, handler)
                          .map(Http3ServerAdapter::new);
    }

    /// Create and start an HTTP/3 server using an externally managed event loop group.
    ///
    /// @param config         server configuration
    /// @param quicSslContext QUIC-specific SSL context (TLS 1.3 required for HTTP/3)
    /// @param handler        request handler (shared with HTTP/1.1 pipeline)
    /// @param workerGroup    external event loop group
    /// @return promise of the running HTTP/3 server
    static Promise<HttpServer> http3Server(HttpServerConfig config,
                                           QuicSslContext quicSslContext,
                                           BiConsumer<RequestContext, ResponseWriter> handler,
                                           EventLoopGroup workerGroup) {
        return Http3Server.createShared(config, quicSslContext, handler, workerGroup)
                          .map(Http3ServerAdapter::new);
    }
}
