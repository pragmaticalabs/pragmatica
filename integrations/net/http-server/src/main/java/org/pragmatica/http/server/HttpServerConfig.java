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

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.SocketOptions;
import org.pragmatica.net.tcp.TlsConfig;


/// Configuration for HTTP server.
///
/// @param name                 server name for logging
/// @param port                 port to bind to
/// @param tls                  optional TLS configuration
/// @param maxContentLength     maximum content length for requests
/// @param webSocketEndpoints   WebSocket endpoints
/// @param chunkedWriteEnabled  whether to enable chunked transfer encoding
/// @param socketOptions        socket-level options
/// @param readTimeout          how long the server waits on a client's request bytes. HTTP/1.1: a connection that
///                             delivers no byte for this long while no response is pending (a stalled body, an idle
///                             keep-alive connection) is closed. HTTP/3: QUIC already closes a silent connection after
///                             its 5 s idle timeout, so the gap there is a stream that trickles; this bounds the whole
///                             request, from its HEADERS frame to the end of its body, and answers 408 past it.
///                             WebSocket connections are exempt once upgraded.
public record HttpServerConfig(String name,
                               int port,
                               Option<TlsConfig> tls,
                               int maxContentLength,
                               List<WebSocketEndpoint> webSocketEndpoints,
                               boolean chunkedWriteEnabled,
                               SocketOptions socketOptions,
                               TimeSpan readTimeout) {
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 65536;
    /// 60 s mirrors nginx's `client_body_timeout` default. It is a guess, not a measurement: long enough for a
    /// client on a slow link to send a 10 MiB body over HTTP/3 (about 1.4 Mbit/s), short enough that a stalled
    /// connection stops pinning its aggregation buffer within a minute.
    public static final TimeSpan DEFAULT_READ_TIMEOUT = TimeSpan.timeSpan(60).seconds();

    public HttpServerConfig {
        webSocketEndpoints = List.copyOf(webSocketEndpoints);
    }

    public static HttpServerConfig httpServerConfig(String name, int port) {
        return new HttpServerConfig(name,
                                    port,
                                    Option.empty(),
                                    DEFAULT_MAX_CONTENT_LENGTH,
                                    List.of(),
                                    false,
                                    SocketOptions.defaultConfig(),
                                    DEFAULT_READ_TIMEOUT);
    }

    public HttpServerConfig withTls(TlsConfig tls) {
        return new HttpServerConfig(name,
                                    port,
                                    Option.some(tls),
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout);
    }

    public HttpServerConfig withMaxContentLength(int maxContentLength) {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout);
    }

    public HttpServerConfig withWebSocket(WebSocketEndpoint endpoint) {
        var endpoints = new ArrayList<>(webSocketEndpoints);

        endpoints.add(endpoint);

        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    List.copyOf(endpoints),
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout);
    }

    public HttpServerConfig withChunkedWrite() {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    true,
                                    socketOptions,
                                    readTimeout);
    }

    public HttpServerConfig withReadTimeout(TimeSpan readTimeout) {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout);
    }
}
