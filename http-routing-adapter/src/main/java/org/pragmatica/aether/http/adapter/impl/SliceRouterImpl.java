package org.pragmatica.aether.http.adapter.impl;

import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.routing.ContentType;
import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.ProblemDetail;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Implementation of SliceRouter that bridges http-handler-api with http-routing.
 */
public final class SliceRouterImpl implements SliceRouter {
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json; charset=UTF-8");
    private static final Map<String, String> TEXT_HEADERS = Map.of("Content-Type", "text/plain; charset=UTF-8");

    private final RequestRouter requestRouter;
    private final ErrorMapper errorMapper;
    private final JsonMapper jsonMapper;

    private SliceRouterImpl(RequestRouter requestRouter,
                            ErrorMapper errorMapper,
                            JsonMapper jsonMapper) {
        this.requestRouter = requestRouter;
        this.errorMapper = errorMapper;
        this.jsonMapper = jsonMapper;
    }

    public static SliceRouter sliceRouterImpl(RouteSource routes,
                                              ErrorMapper errorMapper,
                                              JsonMapper jsonMapper) {
        return new SliceRouterImpl(RequestRouter.with(routes), errorMapper, jsonMapper);
    }

    @Override
    public Promise<HttpResponseData> handle(HttpRequestContext request) {
        var method = parseMethod(request.method());
        if (method == null) {
            return Promise.success(methodNotAllowed(request));
        }
        return requestRouter.findRoute(method,
                                       request.path())
                            .map(route -> handleRoute(route, request))
                            .or(() -> Promise.success(notFound(request)));
    }

    private Promise<HttpResponseData> handleRoute(Route<?> route, HttpRequestContext request) {
        var context = SliceRequestContext.sliceRequestContext(request, route, jsonMapper);
        return invokeHandler(route, context)
        .<HttpResponseData>fold(result -> Promise.success(result.fold(cause -> errorToResponse(cause, request),
                                                                      value -> successToResponse(value,
                                                                                                 route.contentType()))));
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<T> invokeHandler(Route<T> route, SliceRequestContext context) {
        return route.handler()
                    .handle(context);
    }

    private HttpResponseData successToResponse(Object value, ContentType contentType) {
        if (value == null) {
            return HttpResponseData.noContent();
        }
        var headers = headersForContentType(contentType);
        if (isTextContent(contentType)) {
            var body = value.toString()
                            .getBytes(StandardCharsets.UTF_8);
            return HttpResponseData.httpResponseData(200, headers, body);
        }
        return jsonMapper.writeAsBytes(value)
                         .fold(_ -> HttpResponseData.internalError("Serialization failed"),
                               body -> HttpResponseData.httpResponseData(200, headers, body));
    }

    private HttpResponseData errorToResponse(Cause cause, HttpRequestContext request) {
        var httpError = errorMapper.map(cause);
        var problemDetail = ProblemDetail.fromHttpError(httpError, request.path(), request.requestId());
        return jsonMapper.writeAsBytes(problemDetail)
                         .fold(_ -> plainErrorResponse(httpError.status(),
                                                       httpError.message()),
                               body -> HttpResponseData.httpResponseData(httpError.status()
                                                                                  .code(),
                                                                         JSON_HEADERS,
                                                                         body));
    }

    private HttpResponseData notFound(HttpRequestContext request) {
        var problemDetail = ProblemDetail.problemDetail(HttpStatus.NOT_FOUND,
                                                        "No route found for " + request.method() + " " + request.path(),
                                                        request.path(),
                                                        request.requestId());
        return jsonMapper.writeAsBytes(problemDetail)
                         .fold(_ -> plainErrorResponse(HttpStatus.NOT_FOUND, "Not Found"),
                               body -> HttpResponseData.httpResponseData(404, JSON_HEADERS, body));
    }

    private HttpResponseData methodNotAllowed(HttpRequestContext request) {
        var problemDetail = ProblemDetail.problemDetail(HttpStatus.METHOD_NOT_ALLOWED,
                                                        "Invalid HTTP method: " + request.method(),
                                                        request.path(),
                                                        request.requestId());
        return jsonMapper.writeAsBytes(problemDetail)
                         .fold(_ -> plainErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed"),
                               body -> HttpResponseData.httpResponseData(405, JSON_HEADERS, body));
    }

    private HttpResponseData plainErrorResponse(HttpStatus status, String message) {
        return HttpResponseData.httpResponseData(status.code(), TEXT_HEADERS, message.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpMethod parseMethod(String method) {
        try{
            return HttpMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, String> headersForContentType(ContentType contentType) {
        return Map.of("Content-Type", contentType.headerText());
    }

    private static boolean isTextContent(ContentType contentType) {
        var headerText = contentType.headerText()
                                    .toLowerCase();
        return headerText.startsWith("text/") || headerText.contains("plain");
    }
}
