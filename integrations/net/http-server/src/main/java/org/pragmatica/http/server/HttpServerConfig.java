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
/// @param readTimeout          how long the server waits on a client's request bytes without receiving one: an IDLE
///                             bound, the same on both protocols. HTTP/1.1: a connection that delivers no byte for this
///                             long while no response is pending (a stalled body, an idle keep-alive connection) is
///                             closed. HTTP/3: a request stream whose body delivers no frame for this long is answered
///                             408 and its input reset. WebSocket connections are exempt once upgraded.
/// @param bodyRateLimit        the minimum rate a request body must arrive at, the same on both protocols (see
///                             [BodyRateLimit]): it bounds a client that trickles just inside the idle bound.
/// @param classifier           consulted on the request HEADERS, before any body byte is read (see [RequestClassifier]);
///                             none means every request is aggregated up to `maxContentLength`.
/// @param responseTimeout      HTTP/1.1: how long a dispatched request may wait for its response. A request whose response
///                             has not completed by then is answered 504 in its place in the connection's response order
///                             (see `ResponseSequence`), and the connection is closed once the earlier responses are
///                             written; without a bound one stuck handler stalls every response pipelined behind it.
///                             Zero or negative disables the bound.
public record HttpServerConfig(String name,
                               int port,
                               Option<TlsConfig> tls,
                               int maxContentLength,
                               List<WebSocketEndpoint> webSocketEndpoints,
                               boolean chunkedWriteEnabled,
                               SocketOptions socketOptions,
                               TimeSpan readTimeout,
                               BodyRateLimit bodyRateLimit,
                               Option<RequestClassifier> classifier,
                               TimeSpan responseTimeout) {
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 65536;
    /// 60 s mirrors nginx's `client_body_timeout` default. It is a guess, not a measurement: long enough for a
    /// client on a slow link to send a 10 MiB body over HTTP/3 (about 1.4 Mbit/s), short enough that a stalled
    /// connection stops pinning its aggregation buffer within a minute.
    public static final TimeSpan DEFAULT_READ_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    /// 60 s, a guess like [#DEFAULT_READ_TIMEOUT]: longer than any ordinary handler, short enough that a hung one stops
    /// holding a connection and everything pipelined behind it within a minute. Raise it for a server whose handlers
    /// legitimately run longer.
    public static final TimeSpan DEFAULT_RESPONSE_TIMEOUT = TimeSpan.timeSpan(60).seconds();

    public HttpServerConfig {
        webSocketEndpoints = List.copyOf(webSocketEndpoints);
    }

    /// The shape before request classification existed: every request is aggregated up to `maxContentLength`.
    public HttpServerConfig(String name,
                            int port,
                            Option<TlsConfig> tls,
                            int maxContentLength,
                            List<WebSocketEndpoint> webSocketEndpoints,
                            boolean chunkedWriteEnabled,
                            SocketOptions socketOptions,
                            TimeSpan readTimeout,
                            BodyRateLimit bodyRateLimit) {
        this(name,
             port,
             tls,
             maxContentLength,
             webSocketEndpoints,
             chunkedWriteEnabled,
             socketOptions,
             readTimeout,
             bodyRateLimit,
             Option.empty(),
             DEFAULT_RESPONSE_TIMEOUT);
    }

    public static HttpServerConfig httpServerConfig(String name, int port) {
        return new HttpServerConfig(name,
                                    port,
                                    Option.empty(),
                                    DEFAULT_MAX_CONTENT_LENGTH,
                                    List.of(),
                                    false,
                                    SocketOptions.defaultConfig(),
                                    DEFAULT_READ_TIMEOUT,
                                    BodyRateLimit.DEFAULT,
                                    Option.empty(),
                                    DEFAULT_RESPONSE_TIMEOUT);
    }

    public HttpServerConfig withTls(TlsConfig tls) {
        return new HttpServerConfig(name,
                                    port,
                                    Option.some(tls),
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout,
                                    bodyRateLimit,
                                    classifier,
                                    responseTimeout);
    }

    public HttpServerConfig withMaxContentLength(int maxContentLength) {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout,
                                    bodyRateLimit,
                                    classifier,
                                    responseTimeout);
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
                                    readTimeout,
                                    bodyRateLimit,
                                    classifier,
                                    responseTimeout);
    }

    public HttpServerConfig withChunkedWrite() {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    true,
                                    socketOptions,
                                    readTimeout,
                                    bodyRateLimit,
                                    classifier,
                                    responseTimeout);
    }

    public HttpServerConfig withBodyRateLimit(BodyRateLimit bodyRateLimit) {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout,
                                    bodyRateLimit,
                                    classifier,
                                    responseTimeout);
    }

    public HttpServerConfig withReadTimeout(TimeSpan readTimeout) {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout,
                                    bodyRateLimit,
                                    classifier,
                                    responseTimeout);
    }

    /// How long a dispatched HTTP/1.1 request may wait for its response before it is answered 504 (see
    /// [#responseTimeout]); zero or negative disables the bound.
    public HttpServerConfig withResponseTimeout(TimeSpan responseTimeout) {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout,
                                    bodyRateLimit,
                                    classifier,
                                    responseTimeout);
    }

    /// Classify every request on its HEADERS (see [RequestClassifier]).
    public HttpServerConfig withClassifier(RequestClassifier classifier) {
        return new HttpServerConfig(name,
                                    port,
                                    tls,
                                    maxContentLength,
                                    webSocketEndpoints,
                                    chunkedWriteEnabled,
                                    socketOptions,
                                    readTimeout,
                                    bodyRateLimit,
                                    Option.some(classifier),
                                    responseTimeout);
    }

    /// The classifier in force: the configured one, else one that aggregates every request up to `maxContentLength`.
    public RequestClassifier effectiveClassifier() {
        return classifier.or(() -> RequestClassifier.aggregateAll(maxContentLength));
    }
}
