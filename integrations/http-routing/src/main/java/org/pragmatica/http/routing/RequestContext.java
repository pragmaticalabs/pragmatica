package org.pragmatica.http.routing;

import java.util.List;
import java.util.Map;

import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import io.netty.handler.codec.http.HttpHeaders;

import static org.pragmatica.lang.Result.all;


/// Request context providing access to HTTP request data and path parameter matching.
///
/// Extends the transport-agnostic [HttpRequest] base with the post-routing handler surface:
/// the matched [Route], JSON body parsing, path-parameter access and path/query matching,
/// multipart parsing and response-header accumulation.
@SuppressWarnings("unused")
public interface RequestContext extends HttpRequest {
    Result<String> NOT_FOUND = HttpStatus.NOT_FOUND.with("Unknown request path").result();
    Route<?> route();
    <T> Result<T> fromJson(TypeToken<T> literal);
    List<String> pathParams();
    HttpHeaders responseHeaders();

    /// Request path (without query string). Retained alias for [#path] to avoid churning callers.
    default String requestPath() {
        return path();
    }

    /// #772: parse the request body as JSON, wrapping any failure — a missing body, malformed
    /// JSON, or a type mismatch — as a typed 400 [org.pragmatica.http.HttpError] naming what was
    /// expected, exactly mirroring [PathParameter#mapped] (#397): a body the client sent wrong is
    /// a CLIENT error, never the framework's [ErrorMapper#defaultMapper] 500. A failure that
    /// occurs AFTER a successful parse (domain validation performed by the handler itself) does
    /// not go through this method and is unaffected.
    default <T> Result<T> jsonBody(TypeToken<T> type) {
        return fromJson(type).mapError(cause -> HttpStatus.BAD_REQUEST.with(cause));
    }

    /// Request headers as a single-valued map (first value per name). Derived from [#headers].
    default Map<String, String> requestHeaders() {
        return headers().asMap()
                      .entrySet()
                      .stream()
                      .filter(entry -> !entry.getValue()
                                             .isEmpty())
                      .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                                                 entry -> entry.getValue()
                                                                               .getFirst()));
    }

    /// Parse multipart form data from this request.
    ///
    /// Returns a parsed {@link MultipartRequest} if the Content-Type is multipart/form-data,
    /// or an error otherwise.
    default Result<MultipartRequest> multipartRequest() {
        return MultipartParser.parse(body(), resolveContentType().or(""), requestPath());
    }

    /// Check if this request is a multipart/form-data request.
    default boolean isMultipart() {
        return MultipartParser.isMultipart(resolveContentType());
    }

    /// Resolve Content-Type from request headers (case-insensitive lookup).
    private Option<String> resolveContentType() {
        return headers().get("content-type");
    }

    default Result<String> pathParam(int index) {
        return pathParams().size() > index
               ? Result.success(pathParams().get(index))
               : NOT_FOUND;
    }

    default List<String> queryParam(String name) {
        return queryParams().getAll(name);
    }

    default <T1> Result.Mapper1<T1> matchPath(PathParameter<T1> p1) {
        return all(pathParam(0).flatMap(p1::parse));
    }

    default <T1, T2> Result.Mapper2<T1, T2> matchPath(PathParameter<T1> p1, PathParameter<T2> p2) {
        return all(pathParam(0).flatMap(p1::parse), pathParam(1).flatMap(p2::parse));
    }

    default <T1, T2, T3> Result.Mapper3<T1, T2, T3> matchPath(PathParameter<T1> p1,
                                                              PathParameter<T2> p2,
                                                              PathParameter<T3> p3) {
        return all(pathParam(0).flatMap(p1::parse), pathParam(1).flatMap(p2::parse), pathParam(2).flatMap(p3::parse));
    }

    default <T1, T2, T3, T4> Result.Mapper4<T1, T2, T3, T4> matchPath(PathParameter<T1> p1,
                                                                      PathParameter<T2> p2,
                                                                      PathParameter<T3> p3,
                                                                      PathParameter<T4> p4) {
        return all(pathParam(0).flatMap(p1::parse),
                   pathParam(1).flatMap(p2::parse),
                   pathParam(2).flatMap(p3::parse),
                   pathParam(3).flatMap(p4::parse));
    }

    default <T1, T2, T3, T4, T5> Result.Mapper5<T1, T2, T3, T4, T5> matchPath(PathParameter<T1> p1,
                                                                              PathParameter<T2> p2,
                                                                              PathParameter<T3> p3,
                                                                              PathParameter<T4> p4,
                                                                              PathParameter<T5> p5) {
        return all(pathParam(0).flatMap(p1::parse),
                   pathParam(1).flatMap(p2::parse),
                   pathParam(2).flatMap(p3::parse),
                   pathParam(3).flatMap(p4::parse),
                   pathParam(4).flatMap(p5::parse));
    }

    default <Q1> Result.Mapper1<Option<Q1>> matchQuery(QueryParameter<Q1> q1) {
        return all(q1.parse(queryParam(q1.name())));
    }

    default <Q1, Q2> Result.Mapper2<Option<Q1>, Option<Q2>> matchQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2) {
        return all(q1.parse(queryParam(q1.name())),
                   q2.parse(queryParam(q2.name())));
    }

    default <Q1, Q2, Q3> Result.Mapper3<Option<Q1>, Option<Q2>, Option<Q3>> matchQuery(QueryParameter<Q1> q1,
                                                                                       QueryParameter<Q2> q2,
                                                                                       QueryParameter<Q3> q3) {
        return all(q1.parse(queryParam(q1.name())),
                   q2.parse(queryParam(q2.name())),
                   q3.parse(queryParam(q3.name())));
    }

    default <Q1, Q2, Q3, Q4> Result.Mapper4<Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> matchQuery(QueryParameter<Q1> q1,
                                                                                                       QueryParameter<Q2> q2,
                                                                                                       QueryParameter<Q3> q3,
                                                                                                       QueryParameter<Q4> q4) {
        return all(q1.parse(queryParam(q1.name())),
                   q2.parse(queryParam(q2.name())),
                   q3.parse(queryParam(q3.name())),
                   q4.parse(queryParam(q4.name())));
    }

    default <Q1, Q2, Q3, Q4, Q5> Result.Mapper5<Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, Option<Q5>> matchQuery(QueryParameter<Q1> q1,
                                                                                                                       QueryParameter<Q2> q2,
                                                                                                                       QueryParameter<Q3> q3,
                                                                                                                       QueryParameter<Q4> q4,
                                                                                                                       QueryParameter<Q5> q5) {
        return all(q1.parse(queryParam(q1.name())),
                   q2.parse(queryParam(q2.name())),
                   q3.parse(queryParam(q3.name())),
                   q4.parse(queryParam(q4.name())),
                   q5.parse(queryParam(q5.name())));
    }

    /// Implementation of RequestContext for Netty HTTP requests.
    @SuppressWarnings("unused")
    final class RequestContextImpl implements RequestContext {
        private static final int PATH_PARAM_LIMIT = 1024;

        private final io.netty.handler.codec.http.FullHttpRequest request;
        private final Route<?> route;
        private final JsonCodec jsonCodec;
        private final String requestId;

        private final io.netty.handler.codec.http.HttpHeaders responseHeaders = io.netty.handler.codec.http.DefaultHttpHeadersFactory.headersFactory()
                                                                                                                                     .withCombiningHeaders(true)
                                                                                                                                     .newHeaders();

        private java.util.function.Supplier<List<String>> pathParamsSupplier = Utils.lazy(() -> pathParamsSupplier = Utils.value(initPathParams()));

        private java.util.function.Supplier<org.pragmatica.http.QueryParams> queryParamsSupplier = Utils.lazy(() -> queryParamsSupplier = Utils.value(initQueryParams()));

        private java.util.function.Supplier<org.pragmatica.http.Headers> headersSupplier = Utils.lazy(() -> headersSupplier = Utils.value(initHeaders()));

        private RequestContextImpl(io.netty.handler.codec.http.FullHttpRequest request,
                                   Route<?> route,
                                   JsonCodec jsonCodec,
                                   String requestId) {
            this.request = request;
            this.route = route;
            this.jsonCodec = jsonCodec;
            this.requestId = requestId;
        }

        public static RequestContextImpl requestContext(io.netty.handler.codec.http.FullHttpRequest request,
                                                        Route<?> route,
                                                        JsonCodec jsonCodec,
                                                        String requestId) {
            return new RequestContextImpl(request, route, jsonCodec, requestId);
        }

        @Override
        public Route<?> route() {
            return route;
        }

        @Override
        public org.pragmatica.http.HttpMethod method() {
            return org.pragmatica.http.HttpMethod.httpMethod(request.method().name())
                                                 .or(org.pragmatica.http.HttpMethod.GET);
        }

        @Override
        public String path() {
            return PathUtils.normalize(request.uri());
        }

        @Override
        public String requestId() {
            return requestId;
        }

        @Override
        public byte[] body() {
            return io.netty.buffer.ByteBufUtil.getBytes(request.content());
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return jsonCodec.deserialize(io.netty.buffer.ByteBufUtil.getBytes(request.content()),
                                         literal);
        }

        @Override
        public List<String> pathParams() {
            return pathParamsSupplier.get();
        }

        @Override
        public org.pragmatica.http.QueryParams queryParams() {
            return queryParamsSupplier.get();
        }

        @Override
        public org.pragmatica.http.Headers headers() {
            return headersSupplier.get();
        }

        @Override
        public io.netty.handler.codec.http.HttpHeaders responseHeaders() {
            return responseHeaders;
        }

        private List<String> initPathParams() {
            var remainder = PathUtils.normalize(request.uri()).substring(route.path().length());
            // Strip leading slash before splitting
            if (remainder.startsWith("/")) {
                remainder = remainder.substring(1);
            }

            if (remainder.isEmpty()) {
                return List.of();
            }

            var elements = remainder.split("/", PATH_PARAM_LIMIT);
            // Remove trailing empty element if path ends with /
            if (elements[elements.length - 1].isEmpty()) {
                return List.of(elements).subList(0, elements.length - 1);
            }

            return List.of(elements);
        }

        private org.pragmatica.http.QueryParams initQueryParams() {
            return org.pragmatica.http.QueryParams.queryParams(new io.netty.handler.codec.http.QueryStringDecoder(request.uri()).parameters());
        }

        private org.pragmatica.http.Headers initHeaders() {
            var headers = new java.util.HashMap<String, List<String>>();

            request.headers()
                   .forEach(entry -> headers.computeIfAbsent(entry.getKey(),
                                                             _ -> new java.util.ArrayList<>())
                                            .add(entry.getValue()));

            return org.pragmatica.http.Headers.headers(headers);
        }
    }
}
