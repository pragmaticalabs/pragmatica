package org.pragmatica.aether.lb;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// HTTP reverse proxy handler.
///
/// Reconstructs the incoming request as a JDK HttpRequest,
/// forwards to the selected backend, and relays the response back.
/// Adds X-Forwarded-For, X-Forwarded-Host, and X-Request-Id headers.
/// Strips hop-by-hop headers (Connection, Transfer-Encoding).
@SuppressWarnings({"JBCT-RET-03", "JBCT-EX-01"})
public final class ProxyHandler {
    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    /// Hop-by-hop headers that should not be forwarded.
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "transfer-encoding", "keep-alive", "upgrade",
        "proxy-authenticate", "proxy-authorization", "te", "trailer"
    );

    private final Duration forwardTimeout;

    private ProxyHandler(Duration forwardTimeout) {
        this.forwardTimeout = forwardTimeout;
    }

    /// Create a proxy handler with the given timeout.
    public static ProxyHandler proxyHandler(long forwardTimeoutMs) {
        return new ProxyHandler(Duration.ofMillis(forwardTimeoutMs));
    }

    /// Forward a request to the given backend and write the response.
    ///
    /// @param request Incoming request
    /// @param response Response writer
    /// @param backend Target backend
    /// @return true if forwarding succeeded, false if backend failed
    public boolean forward(RequestContext request, ResponseWriter response, Backend backend) {
        try (var client = HttpClient.newBuilder()
                                     .connectTimeout(forwardTimeout)
                                     .followRedirects(HttpClient.Redirect.NEVER)
                                     .build()) {
            var targetUrl = buildTargetUrl(request, backend);
            var forwardRequest = buildForwardRequest(request, targetUrl);
            var backendResponse = client.send(forwardRequest, HttpResponse.BodyHandlers.ofByteArray());

            relayResponseHeaders(response, backendResponse, request.requestId());

            var status = mapStatus(backendResponse.statusCode());
            var body = backendResponse.body();
            var contentType = extractContentType(backendResponse);
            response.write(status, body, contentType);
            return true;
        } catch (Exception e) {
            log.debug("Forward to {}:{} failed: {}", backend.host(), backend.port(), e.getMessage());
            return false;
        }
    }

    private String buildTargetUrl(RequestContext request, Backend backend) {
        var url = backend.baseUrl() + request.path();
        var queryString = buildQueryString(request);
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        return url;
    }

    private ContentType extractContentType(HttpResponse<byte[]> backendResponse) {
        return backendResponse.headers()
                              .firstValue("content-type")
                              .map(ct -> ContentType.contentType(ct, ContentCategory.BINARY))
                              .orElse(CommonContentType.APPLICATION_OCTET_STREAM);
    }

    private HttpRequest buildForwardRequest(RequestContext request, String targetUrl) {
        var method = request.method().name();
        var bodyPublisher = request.hasBody()
                            ? HttpRequest.BodyPublishers.ofByteArray(request.body())
                            : HttpRequest.BodyPublishers.noBody();

        var builder = HttpRequest.newBuilder()
                                 .uri(URI.create(targetUrl))
                                 .timeout(forwardTimeout)
                                 .method(method, bodyPublisher);

        // Forward non-hop-by-hop headers
        request.headers().asMap().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase()) && !name.equalsIgnoreCase("host")) {
                values.forEach(value -> builder.header(name, value));
            }
        });

        // Add proxy headers
        builder.header("X-Forwarded-For", "127.0.0.1");
        request.headers().get("host")
               .onPresent(host -> builder.header("X-Forwarded-Host", host));
        builder.header("X-Request-Id", request.requestId());

        return builder.build();
    }

    private String buildQueryString(RequestContext request) {
        var params = request.queryParams();
        if (params == null) {
            return "";
        }
        var sb = new StringBuilder();
        params.asMap().forEach((key, values) -> {
            for (var value : values) {
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(key).append('=').append(value);
            }
        });
        return sb.toString();
    }

    private void relayResponseHeaders(ResponseWriter response, HttpResponse<byte[]> backendResponse, String requestId) {
        backendResponse.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())
                && !name.equalsIgnoreCase("content-length")
                && !name.equalsIgnoreCase("content-type")) {
                values.forEach(value -> response.header(name, value));
            }
        });
        response.header("X-Request-Id", requestId);
    }

    private HttpStatus mapStatus(int code) {
        return switch (code) {
            case 200 -> HttpStatus.OK;
            case 201 -> HttpStatus.CREATED;
            case 202 -> HttpStatus.ACCEPTED;
            case 204 -> HttpStatus.NO_CONTENT;
            case 301 -> HttpStatus.MOVED_PERMANENTLY;
            case 302 -> HttpStatus.FOUND;
            case 304 -> HttpStatus.NOT_MODIFIED;
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 405 -> HttpStatus.METHOD_NOT_ALLOWED;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.OK;
        };
    }
}
