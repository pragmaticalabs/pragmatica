package org.pragmatica.http.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.routing.security.RouteSecurityPolicy;
import org.pragmatica.lang.Functions.*;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import static org.pragmatica.http.HttpMethod.*;


/// Type-safe HTTP route definition with support for path parameters, query parameters, and request body.
///
/// Routes are built using a fluent builder API that ensures compile-time type safety for all parameter
/// combinations. The total number of parameters (path + query + body) is limited to 5, with one
/// additional combination — 4 path + 3 query, no body (7 total) — added for routes whose identity
/// shape already spends 4 path segments and whose read-control fields must stay independently typed
/// and named rather than packed into one composite query string (rc4: `STREAM_READ`'s `from`/`max`/
/// `readPreference`). Raising this ceiling further as new call sites need it is additive: the
/// underlying `Fn6..Fn15` functional interfaces already exist in `org.pragmatica.lang.Functions`, so
/// each new combination is one more hand-written interface + record, not a framework redesign.
///
/// Example usage:
/// ```{@code
/// // Path parameters only
/// Route.get("/users/{id}")
///      .withPath(aLong())
///      .to(userId -> fetchUser(userId))
///      .asJson();
///
/// // Path + query parameters
/// Route.get("/users/{id}/orders")
///      .withPath(aLong())
///      .withQuery(aInteger("page"), aInteger("size"))
///      .to((userId, page, size) -> fetchOrders(userId, page, size))
///      .asJson();
///
/// // Path + body
/// Route.post("/users/{id}")
///      .withPath(aLong())
///      .withBody(UpdateUserRequest.class)
///      .to((userId, body) -> updateUser(userId, body))
///      .asJson();
///
/// // Query + body
/// Route.post("/search")
///      .withQuery(aBoolean("includeDeleted"))
///      .withBody(SearchRequest.class)
///      .to((includeDeleted, body) -> search(includeDeleted, body))
///      .asJson();
/// }```
///
/// @param <T> the response type
@SuppressWarnings("unused")
public interface Route<T> extends RouteSource {
    HttpMethod method();
    String path();
    Handler<T> handler();
    ContentType contentType();

    /// Returns the spacer patterns for this route.
    /// Spacers are literal path segments that must appear in the URL.
    /// Used for distinguishing routes with the same base path but different trailing segments.
    default List<String> spacers() {
        return List.of();
    }

    /// Returns the declared path arity of this route: the number of trailing path segments the
    /// route consumes after its registered base [#path()], counting both real path parameters and
    /// spacer (literal) segments.
    ///
    /// A no-parameter route (e.g. `get("/items").withoutParameters()`) has arity `0`; a single
    /// path-parameter route (`get("/items/").withPath(aLong())`) has arity `1`; an interleaved
    /// route (`withPath(aLong(), spacer("items"), aLong())`) has arity `3`. The router uses this to
    /// disambiguate sibling routes that normalize to the same registration key, preferring the
    /// candidate whose arity equals the request's trailing-segment count (see [RequestRouter]).
    ///
    /// @return the number of trailing path segments declared by this route
    default int pathParamCount() {
        return 0;
    }

    /// Returns the optional name/identifier for this route.
    /// Used for service discovery and remote invocation.
    ///
    /// @return the route name, or empty string if not specified
    default String name() {
        return "";
    }

    /// Returns the API version this route belongs to (#198 §6.4).
    /// `0` means the route is unversioned; a positive value `N` means the route was declared
    /// under a `[vN.routes]` block. The version is carried as metadata so the same compiled
    /// route can be mounted either in path mode (`{apiPrefix}/v{N}/{path}`, the default) or in
    /// header mode at deploy time, rather than baking `/v{N}/` into the path at codegen.
    ///
    /// @return the API version, or `0` for an unversioned route
    default int version() {
        return 0;
    }

    /// Returns the security policy for this route.
    /// Default is public (allow all).
    default RouteSecurityPolicy security() {
        return new RouteSecurityPolicy() {};
    }

    @Override
    default Stream<Route<?>> routes() {
        return Stream.of(this);
    }

    @Override
    RouteSource withPrefix(String prefix);

    static <T> Route<T> route(HttpMethod method, String path, Handler<T> handler, ContentType contentType) {
        return route(method, path, handler, contentType, List.of(), "", new RouteSecurityPolicy() {});
    }

    static <T> Route<T> route(HttpMethod method,
                              String path,
                              Handler<T> handler,
                              ContentType contentType,
                              List<String> spacers) {
        return route(method, path, handler, contentType, spacers, "", new RouteSecurityPolicy() {});
    }

    static <T> Route<T> route(HttpMethod method,
                              String path,
                              Handler<T> handler,
                              ContentType contentType,
                              List<String> spacers,
                              String name) {
        return route(method, path, handler, contentType, spacers, name, new RouteSecurityPolicy() {});
    }

    static <T> Route<T> route(HttpMethod method,
                              String path,
                              Handler<T> handler,
                              ContentType contentType,
                              List<String> spacers,
                              String name,
                              RouteSecurityPolicy security) {
        return route(method, path, handler, contentType, spacers, name, security, 0);
    }

    static <T> Route<T> route(HttpMethod method,
                              String path,
                              Handler<T> handler,
                              ContentType contentType,
                              List<String> spacers,
                              String name,
                              RouteSecurityPolicy security,
                              int version) {
        return route(method, path, handler, contentType, spacers, name, security, version, spacers.size());
    }

    static <T> Route<T> route(HttpMethod method,
                              String path,
                              Handler<T> handler,
                              ContentType contentType,
                              List<String> spacers,
                              String name,
                              RouteSecurityPolicy security,
                              int version,
                              int pathArity) {
        record route <T>(HttpMethod method,
                         String path,
                         Handler<T> handler,
                         ContentType contentType,
                         List<String> spacers,
                         String name,
                         RouteSecurityPolicy security,
                         int version,
                         int pathArity) implements Route<T> {
            @Override
            public int pathParamCount() {
                return pathArity;
            }

            @Override
            public RouteSource withPrefix(String prefix) {
                return route(method,
                             PathUtils.normalize(prefix + path),
                             handler,
                             contentType,
                             spacers,
                             name,
                             security,
                             version,
                             pathArity);
            }

            @Override
            public String toString() {
                var spacerStr = spacers.isEmpty()
                                ? ""
                                : " [spacers: " + String.join(", ", spacers) + "]";
                var nameStr = name.isEmpty()
                              ? ""
                              : " [name: " + name + "]";
                var versionStr = version == 0
                                 ? ""
                                 : " [version: v" + version + "]";

                return "Route: " + method + " " + path + spacerStr + nameStr + versionStr + ", " + contentType;
            }
        }

        return new route <>(method,
                            PathUtils.normalize(path),
                            handler,
                            contentType,
                            spacers,
                            name,
                            security,
                            version,
                            pathArity);
    }

    /// Mount a route in path mode (#198 §6.4, the default detection mode). For a versioned route
    /// (`version > 0`) the un-versioned compiled path is composed into `{apiPrefix}/v{version}/{path}`;
    /// an unversioned route (`version == 0`) is returned unchanged. Applying this at route-registration
    /// time keeps the same compiled slice deployable in either path mode or header mode (header mode is
    /// a separate step), and makes path-mode behavior identical to baking `/v{N}/` at codegen.
    ///
    /// @param route     the compiled (un-versioned-path) route
    /// @param apiPrefix the version-agnostic API prefix (e.g. `/api/orders`)
    /// @return a route whose [#path()] is the path-mode mounted path
    static Route<?> mountInPathMode(Route<?> route, String apiPrefix) {
        return route.version() == 0
               ? route
               : route(route.method(),
                       PathUtils.normalize(apiPrefix + "/v" + route.version() + route.path()),
                       route.handler(),
                       route.contentType(),
                       route.spacers(),
                       route.name(),
                       route.security(),
                       route.version(),
                       route.pathParamCount());
    }

    /// Mount a route in header mode (#198 §7). For a versioned route (`version > 0`) the un-versioned
    /// compiled path is composed into the bare `{apiPrefix}/{path}` (no `/v{N}/` segment), so every
    /// declared version of a bind key shares one path and the version is selected from a request
    /// header at dispatch time (see [VersionSelector]); an unversioned route (`version == 0`) is
    /// returned unchanged. The version metadata is preserved so the dispatcher can index candidates
    /// by version.
    ///
    /// @param route     the compiled (un-versioned-path) route
    /// @param apiPrefix the version-agnostic API prefix (e.g. `/api/orders`)
    /// @return a route whose [#path()] is the header-mode (bare) mounted path
    static Route<?> mountInHeaderMode(Route<?> route, String apiPrefix) {
        return route.version() == 0
               ? route
               : route(route.method(),
                       PathUtils.normalize(apiPrefix + route.path()),
                       route.handler(),
                       route.contentType(),
                       route.spacers(),
                       route.name(),
                       route.security(),
                       route.version(),
                       route.pathParamCount());
    }

    static Subroutes in(String path) {
        return () -> path;
    }

    /// Creates a route source for serving static files from the classpath.
    ///
    /// @param urlPrefix       the URL prefix to match (e.g., "/static")
    /// @param classpathPrefix the classpath prefix where files are located (e.g., "/web")
    /// @return a route source that serves static files
    static RouteSource staticFiles(String urlPrefix, String classpathPrefix) {
        return StaticFileRouteSource.staticFiles(urlPrefix, classpathPrefix);
    }

    interface Subroutes {
        String path();

        default RouteSource serve(RouteSource... subroutes) {
            return () -> Stream.of(subroutes)
                               .map(route -> route.withPrefix(path()))
                               .flatMap(RouteSource::routes);
        }
    }

    // HTTP method factories
    static <R> ParameterBuilder<R> options(String path) {
        return method(OPTIONS, path);
    }

    static <R> ParameterBuilder<R> get(String path) {
        return method(GET, path);
    }

    static <R> ParameterBuilder<R> head(String path) {
        return method(HEAD, path);
    }

    static <R> ParameterBuilder<R> post(String path) {
        return method(POST, path);
    }

    static <R> ParameterBuilder<R> put(String path) {
        return method(PUT, path);
    }

    static <R> ParameterBuilder<R> patch(String path) {
        return method(PATCH, path);
    }

    static <R> ParameterBuilder<R> delete(String path) {
        return method(DELETE, path);
    }

    static <R> ParameterBuilder<R> trace(String path) {
        return method(TRACE, path);
    }

    static <R> ParameterBuilder<R> connect(String path) {
        return method(CONNECT, path);
    }

    static <R> ParameterBuilder<R> method(HttpMethod method, String path) {
        return new ParameterBuilderImpl<>(method, path);
    }

    /// Like [#method(HttpMethod, String)] but pre-seeds the route with a default name.
    /// The default name is used as the route's [#name()] unless the caller overrides it
    /// later via [ContentTypeBuilder#named(String)]. Used by registry adapters that want
    /// to tag every route with an identifier without requiring an explicit `.named(...)`
    /// call at every site.
    static <R> ParameterBuilder<R> method(HttpMethod method, String path, String defaultName) {
        return new ParameterBuilderImpl<>(method, path, List.of(), defaultName);
    }

    // ===================================================================================
    // Entry Point Builder
    // ===================================================================================
    /// Entry point builder - provides access to all parameter configuration options.
    interface ParameterBuilder<R> {
        ContentTypeBuilder<R> to(Handler<R> handler);

        default RawHandlerBuilder<R> withoutParameters() {
            return this::to;
        }

        // Path parameters (1-5)
        <P1> PathBuilder1<R, P1> withPath(PathParameter<P1> p1);
        <P1, P2> PathBuilder2<R, P1, P2> withPath(PathParameter<P1> p1, PathParameter<P2> p2);

        <P1, P2, P3> PathBuilder3<R, P1, P2, P3> withPath(PathParameter<P1> p1,
                                                          PathParameter<P2> p2,
                                                          PathParameter<P3> p3);

        <P1, P2, P3, P4> PathBuilder4<R, P1, P2, P3, P4> withPath(PathParameter<P1> p1,
                                                                  PathParameter<P2> p2,
                                                                  PathParameter<P3> p3,
                                                                  PathParameter<P4> p4);

        <P1, P2, P3, P4, P5> PathBuilder5<R, P1, P2, P3, P4, P5> withPath(PathParameter<P1> p1,
                                                                          PathParameter<P2> p2,
                                                                          PathParameter<P3> p3,
                                                                          PathParameter<P4> p4,
                                                                          PathParameter<P5> p5);

        // Body only
        <B> BodyBuilder<R, B> withBody(TypeToken<B> type);

        default <B> BodyBuilder<R, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }

        /// Bind the raw request body as a `String` (for routes that `consume` a text media type).
        /// Used by slice-processor codegen when `consumes` is TEXT/HTML/XML.
        BodyBuilder<R, String> withStringBody();
        /// Bind the raw request body as a `byte[]` (for routes that `consume` a binary media type).
        /// Used by slice-processor codegen when `consumes` is BINARY.
        BodyBuilder<R, byte[]> withByteBody();
        /// Bind the request body as a parsed [MultipartRequest] (for `multipart/form-data` routes).
        /// Used by slice-processor codegen when `consumes` is MULTIPART.
        BodyBuilder<R, MultipartRequest> withMultipartBody();
        // Query parameters only (1-5)
        <Q1> QueryBuilder1<R, Q1> withQuery(QueryParameter<Q1> q1);
        <Q1, Q2> QueryBuilder2<R, Q1, Q2> withQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2);

        <Q1, Q2, Q3> QueryBuilder3<R, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                            QueryParameter<Q2> q2,
                                                            QueryParameter<Q3> q3);

        <Q1, Q2, Q3, Q4> QueryBuilder4<R, Q1, Q2, Q3, Q4> withQuery(QueryParameter<Q1> q1,
                                                                    QueryParameter<Q2> q2,
                                                                    QueryParameter<Q3> q3,
                                                                    QueryParameter<Q4> q4);

        <Q1, Q2, Q3, Q4, Q5> QueryBuilder5<R, Q1, Q2, Q3, Q4, Q5> withQuery(QueryParameter<Q1> q1,
                                                                            QueryParameter<Q2> q2,
                                                                            QueryParameter<Q3> q3,
                                                                            QueryParameter<Q4> q4,
                                                                            QueryParameter<Q5> q5);

        // Convenience methods
        default ContentTypeBuilder<R> toImmediate(Fn1<Result<R>, RequestContext> handler) {
            return withoutParameters().toImmediate(handler);
        }

        default Route<R> toText(Handler<R> handler) {
            return to(handler).asText();
        }

        default Route<R> toText(Supplier<R> handler) {
            return to(_ -> Promise.success(handler.get())).asText();
        }

        default Route<R> toJson(Handler<R> handler) {
            return to(handler).asJson();
        }

        default Route<R> toJson(Supplier<R> handler) {
            return to(_ -> Promise.success(handler.get())).asJson();
        }
    }

    // ===================================================================================
    // Path Builders (1-5 path params, can chain to body or query)
    // ===================================================================================
    interface PathBuilder1<R, P1> {
        ContentTypeBuilder<R> to(Fn1<Promise<R>, P1> fn);

        default ContentTypeBuilder<R> toResult(Fn1<Result<R>, P1> fn) {
            return to(p1 -> Promise.resolved(fn.apply(p1)));
        }

        default ContentTypeBuilder<R> toValue(Fn1<R, P1> fn) {
            return to(p1 -> Promise.success(fn.apply(p1)));
        }

        <B> PathBodyBuilder1<R, P1, B> withBody(TypeToken<B> type);

        default <B> PathBodyBuilder1<R, P1, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }

        <Q1> PathQueryBuilder1_1<R, P1, Q1> withQuery(QueryParameter<Q1> q1);

        <Q1, Q2> PathQueryBuilder1_2<R, P1, Q1, Q2> withQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2);

        <Q1, Q2, Q3> PathQueryBuilder1_3<R, P1, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                      QueryParameter<Q2> q2,
                                                                      QueryParameter<Q3> q3);

        <Q1, Q2, Q3, Q4> PathQueryBuilder1_4<R, P1, Q1, Q2, Q3, Q4> withQuery(QueryParameter<Q1> q1,
                                                                              QueryParameter<Q2> q2,
                                                                              QueryParameter<Q3> q3,
                                                                              QueryParameter<Q4> q4);
    }

    interface PathBuilder2<R, P1, P2> {
        ContentTypeBuilder<R> to(Fn2<Promise<R>, P1, P2> fn);

        default ContentTypeBuilder<R> toResult(Fn2<Result<R>, P1, P2> fn) {
            return to((p1, p2) -> Promise.resolved(fn.apply(p1, p2)));
        }

        default ContentTypeBuilder<R> toValue(Fn2<R, P1, P2> fn) {
            return to((p1, p2) -> Promise.success(fn.apply(p1, p2)));
        }

        <B> PathBodyBuilder2<R, P1, P2, B> withBody(TypeToken<B> type);

        default <B> PathBodyBuilder2<R, P1, P2, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }

        <Q1> PathQueryBuilder2_1<R, P1, P2, Q1> withQuery(QueryParameter<Q1> q1);

        <Q1, Q2> PathQueryBuilder2_2<R, P1, P2, Q1, Q2> withQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2);

        <Q1, Q2, Q3> PathQueryBuilder2_3<R, P1, P2, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                          QueryParameter<Q2> q2,
                                                                          QueryParameter<Q3> q3);
    }

    interface PathBuilder3<R, P1, P2, P3> {
        ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, P2, P3> fn);

        default ContentTypeBuilder<R> toResult(Fn3<Result<R>, P1, P2, P3> fn) {
            return to((p1, p2, p3) -> Promise.resolved(fn.apply(p1, p2, p3)));
        }

        default ContentTypeBuilder<R> toValue(Fn3<R, P1, P2, P3> fn) {
            return to((p1, p2, p3) -> Promise.success(fn.apply(p1, p2, p3)));
        }

        <B> PathBodyBuilder3<R, P1, P2, P3, B> withBody(TypeToken<B> type);

        default <B> PathBodyBuilder3<R, P1, P2, P3, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }

        <Q1> PathQueryBuilder3_1<R, P1, P2, P3, Q1> withQuery(QueryParameter<Q1> q1);

        <Q1, Q2> PathQueryBuilder3_2<R, P1, P2, P3, Q1, Q2> withQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2);
    }

    interface PathBuilder4<R, P1, P2, P3, P4> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, P3, P4> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, P1, P2, P3, P4> fn) {
            return to((p1, p2, p3, p4) -> Promise.resolved(fn.apply(p1, p2, p3, p4)));
        }

        default ContentTypeBuilder<R> toValue(Fn4<R, P1, P2, P3, P4> fn) {
            return to((p1, p2, p3, p4) -> Promise.success(fn.apply(p1, p2, p3, p4)));
        }

        <B> PathBodyBuilder4<R, P1, P2, P3, P4, B> withBody(TypeToken<B> type);

        default <B> PathBodyBuilder4<R, P1, P2, P3, P4, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }

        <Q1> PathQueryBuilder4_1<R, P1, P2, P3, P4, Q1> withQuery(QueryParameter<Q1> q1);

        <Q1, Q2, Q3> PathQueryBuilder4_3<R, P1, P2, P3, P4, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                                  QueryParameter<Q2> q2,
                                                                                  QueryParameter<Q3> q3);
    }

    interface PathBuilder5<R, P1, P2, P3, P4, P5> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, P3, P4, P5> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, P1, P2, P3, P4, P5> fn) {
            return to((p1, p2, p3, p4, p5) -> Promise.resolved(fn.apply(p1, p2, p3, p4, p5)));
        }

        default ContentTypeBuilder<R> toValue(Fn5<R, P1, P2, P3, P4, P5> fn) {
            return to((p1, p2, p3, p4, p5) -> Promise.success(fn.apply(p1, p2, p3, p4, p5)));
        }

        <Q1, Q2, Q3> PathQueryBuilder5_3<R, P1, P2, P3, P4, P5, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                                       QueryParameter<Q2> q2,
                                                                                       QueryParameter<Q3> q3);
    }

    // ===================================================================================
    // Body Builder (body only, terminal)
    // ===================================================================================
    interface BodyBuilder<R, B> {
        ContentTypeBuilder<R> to(Fn1<Promise<R>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn1<Result<R>, B> fn) {
            return to(body -> Promise.resolved(fn.apply(body)));
        }

        default Route<R> toJson(Fn1<Promise<R>, B> fn) {
            return to(fn).asJson();
        }
    }

    // ===================================================================================
    // Query Builders (1-5 query params, can chain to body)
    // ===================================================================================
    interface QueryBuilder1<R, Q1> {
        ContentTypeBuilder<R> to(Fn1<Promise<R>, Option<Q1>> fn);

        default ContentTypeBuilder<R> toResult(Fn1<Result<R>, Option<Q1>> fn) {
            return to(q1 -> Promise.resolved(fn.apply(q1)));
        }

        default ContentTypeBuilder<R> toValue(Fn1<R, Option<Q1>> fn) {
            return to(q1 -> Promise.success(fn.apply(q1)));
        }

        <B> QueryBodyBuilder1<R, Q1, B> withBody(TypeToken<B> type);

        default <B> QueryBodyBuilder1<R, Q1, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }
    }

    interface QueryBuilder2<R, Q1, Q2> {
        ContentTypeBuilder<R> to(Fn2<Promise<R>, Option<Q1>, Option<Q2>> fn);

        default ContentTypeBuilder<R> toResult(Fn2<Result<R>, Option<Q1>, Option<Q2>> fn) {
            return to((q1, q2) -> Promise.resolved(fn.apply(q1, q2)));
        }

        default ContentTypeBuilder<R> toValue(Fn2<R, Option<Q1>, Option<Q2>> fn) {
            return to((q1, q2) -> Promise.success(fn.apply(q1, q2)));
        }

        <B> QueryBodyBuilder2<R, Q1, Q2, B> withBody(TypeToken<B> type);

        default <B> QueryBodyBuilder2<R, Q1, Q2, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }
    }

    interface QueryBuilder3<R, Q1, Q2, Q3> {
        ContentTypeBuilder<R> to(Fn3<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>> fn);

        default ContentTypeBuilder<R> toResult(Fn3<Result<R>, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((q1, q2, q3) -> Promise.resolved(fn.apply(q1, q2, q3)));
        }

        default ContentTypeBuilder<R> toValue(Fn3<R, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((q1, q2, q3) -> Promise.success(fn.apply(q1, q2, q3)));
        }

        <B> QueryBodyBuilder3<R, Q1, Q2, Q3, B> withBody(TypeToken<B> type);

        default <B> QueryBodyBuilder3<R, Q1, Q2, Q3, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }
    }

    interface QueryBuilder4<R, Q1, Q2, Q3, Q4> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn) {
            return to((q1, q2, q3, q4) -> Promise.resolved(fn.apply(q1, q2, q3, q4)));
        }

        default ContentTypeBuilder<R> toValue(Fn4<R, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn) {
            return to((q1, q2, q3, q4) -> Promise.success(fn.apply(q1, q2, q3, q4)));
        }

        <B> QueryBodyBuilder4<R, Q1, Q2, Q3, Q4, B> withBody(TypeToken<B> type);

        default <B> QueryBodyBuilder4<R, Q1, Q2, Q3, Q4, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }
    }

    interface QueryBuilder5<R, Q1, Q2, Q3, Q4, Q5> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, Option<Q5>> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, Option<Q5>> fn) {
            return to((q1, q2, q3, q4, q5) -> Promise.resolved(fn.apply(q1, q2, q3, q4, q5)));
        }

        default ContentTypeBuilder<R> toValue(Fn5<R, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, Option<Q5>> fn) {
            return to((q1, q2, q3, q4, q5) -> Promise.success(fn.apply(q1, q2, q3, q4, q5)));
        }
    }

    // ===================================================================================
    // Path + Body Builders (terminal)
    // ===================================================================================
    interface PathBodyBuilder1<R, P1, B> {
        ContentTypeBuilder<R> to(Fn2<Promise<R>, P1, B> fn);

        default ContentTypeBuilder<R> toResult(Fn2<Result<R>, P1, B> fn) {
            return to((p1, body) -> Promise.resolved(fn.apply(p1, body)));
        }

        default Route<R> toJson(Fn2<Promise<R>, P1, B> fn) {
            return to(fn).asJson();
        }
    }

    interface PathBodyBuilder2<R, P1, P2, B> {
        ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, P2, B> fn);

        default ContentTypeBuilder<R> toResult(Fn3<Result<R>, P1, P2, B> fn) {
            return to((p1, p2, body) -> Promise.resolved(fn.apply(p1, p2, body)));
        }

        default Route<R> toJson(Fn3<Promise<R>, P1, P2, B> fn) {
            return to(fn).asJson();
        }
    }

    interface PathBodyBuilder3<R, P1, P2, P3, B> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, P3, B> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, P1, P2, P3, B> fn) {
            return to((p1, p2, p3, body) -> Promise.resolved(fn.apply(p1, p2, p3, body)));
        }

        default Route<R> toJson(Fn4<Promise<R>, P1, P2, P3, B> fn) {
            return to(fn).asJson();
        }
    }

    interface PathBodyBuilder4<R, P1, P2, P3, P4, B> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, P3, P4, B> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, P1, P2, P3, P4, B> fn) {
            return to((p1, p2, p3, p4, body) -> Promise.resolved(fn.apply(p1, p2, p3, p4, body)));
        }

        default Route<R> toJson(Fn5<Promise<R>, P1, P2, P3, P4, B> fn) {
            return to(fn).asJson();
        }
    }

    // ===================================================================================
    // Query + Body Builders (terminal)
    // ===================================================================================
    interface QueryBodyBuilder1<R, Q1, B> {
        ContentTypeBuilder<R> to(Fn2<Promise<R>, Option<Q1>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn2<Result<R>, Option<Q1>, B> fn) {
            return to((q1, body) -> Promise.resolved(fn.apply(q1, body)));
        }

        default Route<R> toJson(Fn2<Promise<R>, Option<Q1>, B> fn) {
            return to(fn).asJson();
        }
    }

    interface QueryBodyBuilder2<R, Q1, Q2, B> {
        ContentTypeBuilder<R> to(Fn3<Promise<R>, Option<Q1>, Option<Q2>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn3<Result<R>, Option<Q1>, Option<Q2>, B> fn) {
            return to((q1, q2, body) -> Promise.resolved(fn.apply(q1, q2, body)));
        }

        default Route<R> toJson(Fn3<Promise<R>, Option<Q1>, Option<Q2>, B> fn) {
            return to(fn).asJson();
        }
    }

    interface QueryBodyBuilder3<R, Q1, Q2, Q3, B> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, Option<Q1>, Option<Q2>, Option<Q3>, B> fn) {
            return to((q1, q2, q3, body) -> Promise.resolved(fn.apply(q1, q2, q3, body)));
        }

        default Route<R> toJson(Fn4<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, B> fn) {
            return to(fn).asJson();
        }
    }

    interface QueryBodyBuilder4<R, Q1, Q2, Q3, Q4, B> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, B> fn) {
            return to((q1, q2, q3, q4, body) -> Promise.resolved(fn.apply(q1, q2, q3, q4, body)));
        }

        default Route<R> toJson(Fn5<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, B> fn) {
            return to(fn).asJson();
        }
    }

    // ===================================================================================
    // Path + Query Builders (can chain to body)
    // ===================================================================================
    // 1 path + 1-4 query
    interface PathQueryBuilder1_1<R, P1, Q1> {
        ContentTypeBuilder<R> to(Fn2<Promise<R>, P1, Option<Q1>> fn);

        default ContentTypeBuilder<R> toResult(Fn2<Result<R>, P1, Option<Q1>> fn) {
            return to((p1, q1) -> Promise.resolved(fn.apply(p1, q1)));
        }

        default ContentTypeBuilder<R> toValue(Fn2<R, P1, Option<Q1>> fn) {
            return to((p1, q1) -> Promise.success(fn.apply(p1, q1)));
        }

        <B> PathQueryBodyBuilder1_1<R, P1, Q1, B> withBody(TypeToken<B> type);

        default <B> PathQueryBodyBuilder1_1<R, P1, Q1, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }
    }

    interface PathQueryBuilder1_2<R, P1, Q1, Q2> {
        ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, Option<Q1>, Option<Q2>> fn);

        default ContentTypeBuilder<R> toResult(Fn3<Result<R>, P1, Option<Q1>, Option<Q2>> fn) {
            return to((p1, q1, q2) -> Promise.resolved(fn.apply(p1, q1, q2)));
        }

        default ContentTypeBuilder<R> toValue(Fn3<R, P1, Option<Q1>, Option<Q2>> fn) {
            return to((p1, q1, q2) -> Promise.success(fn.apply(p1, q1, q2)));
        }

        <B> PathQueryBodyBuilder1_2<R, P1, Q1, Q2, B> withBody(TypeToken<B> type);

        default <B> PathQueryBodyBuilder1_2<R, P1, Q1, Q2, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }
    }

    interface PathQueryBuilder1_3<R, P1, Q1, Q2, Q3> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, Option<Q1>, Option<Q2>, Option<Q3>> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, P1, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, q1, q2, q3) -> Promise.resolved(fn.apply(p1, q1, q2, q3)));
        }

        default ContentTypeBuilder<R> toValue(Fn4<R, P1, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, q1, q2, q3) -> Promise.success(fn.apply(p1, q1, q2, q3)));
        }
    }

    interface PathQueryBuilder1_4<R, P1, Q1, Q2, Q3, Q4> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, P1, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn) {
            return to((p1, q1, q2, q3, q4) -> Promise.resolved(fn.apply(p1, q1, q2, q3, q4)));
        }

        default ContentTypeBuilder<R> toValue(Fn5<R, P1, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn) {
            return to((p1, q1, q2, q3, q4) -> Promise.success(fn.apply(p1, q1, q2, q3, q4)));
        }
    }

    // 2 path + 1-3 query
    interface PathQueryBuilder2_1<R, P1, P2, Q1> {
        ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, P2, Option<Q1>> fn);

        default ContentTypeBuilder<R> toResult(Fn3<Result<R>, P1, P2, Option<Q1>> fn) {
            return to((p1, p2, q1) -> Promise.resolved(fn.apply(p1, p2, q1)));
        }

        default ContentTypeBuilder<R> toValue(Fn3<R, P1, P2, Option<Q1>> fn) {
            return to((p1, p2, q1) -> Promise.success(fn.apply(p1, p2, q1)));
        }

        <B> PathQueryBodyBuilder2_1<R, P1, P2, Q1, B> withBody(TypeToken<B> type);

        default <B> PathQueryBodyBuilder2_1<R, P1, P2, Q1, B> withBody(Class<B> type) {
            return withBody(TypeToken.typeToken(type));
        }
    }

    interface PathQueryBuilder2_2<R, P1, P2, Q1, Q2> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, Option<Q1>, Option<Q2>> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, P1, P2, Option<Q1>, Option<Q2>> fn) {
            return to((p1, p2, q1, q2) -> Promise.resolved(fn.apply(p1, p2, q1, q2)));
        }

        default ContentTypeBuilder<R> toValue(Fn4<R, P1, P2, Option<Q1>, Option<Q2>> fn) {
            return to((p1, p2, q1, q2) -> Promise.success(fn.apply(p1, p2, q1, q2)));
        }
    }

    interface PathQueryBuilder2_3<R, P1, P2, Q1, Q2, Q3> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, Option<Q1>, Option<Q2>, Option<Q3>> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, P1, P2, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, p2, q1, q2, q3) -> Promise.resolved(fn.apply(p1, p2, q1, q2, q3)));
        }

        default ContentTypeBuilder<R> toValue(Fn5<R, P1, P2, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, p2, q1, q2, q3) -> Promise.success(fn.apply(p1, p2, q1, q2, q3)));
        }
    }

    // 3 path + 1-2 query
    interface PathQueryBuilder3_1<R, P1, P2, P3, Q1> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, P3, Option<Q1>> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, P1, P2, P3, Option<Q1>> fn) {
            return to((p1, p2, p3, q1) -> Promise.resolved(fn.apply(p1, p2, p3, q1)));
        }

        default ContentTypeBuilder<R> toValue(Fn4<R, P1, P2, P3, Option<Q1>> fn) {
            return to((p1, p2, p3, q1) -> Promise.success(fn.apply(p1, p2, p3, q1)));
        }
    }

    interface PathQueryBuilder3_2<R, P1, P2, P3, Q1, Q2> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, P3, Option<Q1>, Option<Q2>> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, P1, P2, P3, Option<Q1>, Option<Q2>> fn) {
            return to((p1, p2, p3, q1, q2) -> Promise.resolved(fn.apply(p1, p2, p3, q1, q2)));
        }

        default ContentTypeBuilder<R> toValue(Fn5<R, P1, P2, P3, Option<Q1>, Option<Q2>> fn) {
            return to((p1, p2, p3, q1, q2) -> Promise.success(fn.apply(p1, p2, p3, q1, q2)));
        }
    }

    // 4 path + 1 query
    interface PathQueryBuilder4_1<R, P1, P2, P3, P4, Q1> {
        ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, P3, P4, Option<Q1>> fn);

        default ContentTypeBuilder<R> toResult(Fn5<Result<R>, P1, P2, P3, P4, Option<Q1>> fn) {
            return to((p1, p2, p3, p4, q1) -> Promise.resolved(fn.apply(p1, p2, p3, p4, q1)));
        }

        default ContentTypeBuilder<R> toValue(Fn5<R, P1, P2, P3, P4, Option<Q1>> fn) {
            return to((p1, p2, p3, p4, q1) -> Promise.success(fn.apply(p1, p2, p3, p4, q1)));
        }
    }

    // 4 path + 3 query (7 total, no body — ceiling raised for STREAM_READ's independently-typed
    // from/max/readPreference; see class doc)
    interface PathQueryBuilder4_3<R, P1, P2, P3, P4, Q1, Q2, Q3> {
        ContentTypeBuilder<R> to(Fn7<Promise<R>, P1, P2, P3, P4, Option<Q1>, Option<Q2>, Option<Q3>> fn);

        default ContentTypeBuilder<R> toResult(Fn7<Result<R>, P1, P2, P3, P4, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, p2, p3, p4, q1, q2, q3) -> Promise.resolved(fn.apply(p1, p2, p3, p4, q1, q2, q3)));
        }

        default ContentTypeBuilder<R> toValue(Fn7<R, P1, P2, P3, P4, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, p2, p3, p4, q1, q2, q3) -> Promise.success(fn.apply(p1, p2, p3, p4, q1, q2, q3)));
        }
    }

    // 5 path + 3 query (8 total, no body — ceiling raised for STREAM_READ's catalog-form
    // registration, which needs a spacer("read") path slot in addition to the 4 real params;
    // see class doc)
    interface PathQueryBuilder5_3<R, P1, P2, P3, P4, P5, Q1, Q2, Q3> {
        ContentTypeBuilder<R> to(Fn8<Promise<R>, P1, P2, P3, P4, P5, Option<Q1>, Option<Q2>, Option<Q3>> fn);

        default ContentTypeBuilder<R> toResult(Fn8<Result<R>, P1, P2, P3, P4, P5, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, p2, p3, p4, p5, q1, q2, q3) -> Promise.resolved(fn.apply(p1, p2, p3, p4, p5, q1, q2, q3)));
        }

        default ContentTypeBuilder<R> toValue(Fn8<R, P1, P2, P3, P4, P5, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return to((p1, p2, p3, p4, p5, q1, q2, q3) -> Promise.success(fn.apply(p1, p2, p3, p4, p5, q1, q2, q3)));
        }
    }

    // ===================================================================================
    // Path + Query + Body Builders (terminal)
    // ===================================================================================
    // 1 path + 1 query + body
    interface PathQueryBodyBuilder1_1<R, P1, Q1, B> {
        ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, Option<Q1>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn3<Result<R>, P1, Option<Q1>, B> fn) {
            return to((p1, q1, body) -> Promise.resolved(fn.apply(p1, q1, body)));
        }

        default Route<R> toJson(Fn3<Promise<R>, P1, Option<Q1>, B> fn) {
            return to(fn).asJson();
        }
    }

    // 1 path + 2 query + body
    interface PathQueryBodyBuilder1_2<R, P1, Q1, Q2, B> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, Option<Q1>, Option<Q2>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, P1, Option<Q1>, Option<Q2>, B> fn) {
            return to((p1, q1, q2, body) -> Promise.resolved(fn.apply(p1, q1, q2, body)));
        }

        default Route<R> toJson(Fn4<Promise<R>, P1, Option<Q1>, Option<Q2>, B> fn) {
            return to(fn).asJson();
        }
    }

    // 2 path + 1 query + body
    interface PathQueryBodyBuilder2_1<R, P1, P2, Q1, B> {
        ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, Option<Q1>, B> fn);

        default ContentTypeBuilder<R> toResult(Fn4<Result<R>, P1, P2, Option<Q1>, B> fn) {
            return to((p1, p2, q1, body) -> Promise.resolved(fn.apply(p1, p2, q1, body)));
        }

        default Route<R> toJson(Fn4<Promise<R>, P1, P2, Option<Q1>, B> fn) {
            return to(fn).asJson();
        }
    }

    // ===================================================================================
    // Terminal Builders
    // ===================================================================================
    interface RawHandlerBuilder<T> {
        ContentTypeBuilder<T> to(Handler<T> handler);

        default ContentTypeBuilder<T> toImmediate(Fn1<Result<T>, RequestContext> handler) {
            return to(ctx -> Promise.resolved(handler.apply(ctx)));
        }
    }

    interface ContentTypeBuilder<T> {
        Route<T> as(ContentType contentType);

        /// Sets a name/identifier for this route.
        /// Used for service discovery and remote invocation.
        ///
        /// @param name the route name
        /// @return a new builder with the name set
        ContentTypeBuilder<T> named(String name);

        /// Sets the security policy for this route.
        ///
        /// @param security the security policy
        /// @return a new builder with the security policy set
        ContentTypeBuilder<T> withSecurity(RouteSecurityPolicy security);

        /// Tags this route with the API version it belongs to (#198 §6.4).
        /// `0` (the default) leaves the route unversioned; a positive `N` records that the route
        /// was declared under `[vN.routes]`, so it can later be mounted in path mode
        /// (`{apiPrefix}/v{N}/{path}`) or header mode at registration time.
        ///
        /// @param version the API version (`0` for unversioned)
        /// @return a new builder with the version set
        ContentTypeBuilder<T> versioned(int version);

        default Route<T> asText() {
            return as(CommonContentType.TEXT_PLAIN);
        }

        default Route<T> asJson() {
            return as(CommonContentType.APPLICATION_JSON);
        }
    }

    // ===================================================================================
    // Implementation
    // ===================================================================================
    record ContentTypeBuilderImpl<T>(HttpMethod method,
                                     String path,
                                     Handler<T> handler,
                                     List<String> spacers,
                                     String name,
                                     RouteSecurityPolicy security,
                                     int version,
                                     int pathArity) implements ContentTypeBuilder<T> {
        ContentTypeBuilderImpl(HttpMethod method,
                               String path,
                               Handler<T> handler,
                               List<String> spacers,
                               String name,
                               RouteSecurityPolicy security) {
            this(method, path, handler, spacers, name, security, 0, spacers.size());
        }

        ContentTypeBuilderImpl(HttpMethod method,
                               String path,
                               Handler<T> handler,
                               List<String> spacers,
                               String name,
                               RouteSecurityPolicy security,
                               int pathArity) {
            this(method, path, handler, spacers, name, security, 0, pathArity);
        }

        @Override
        public Route<T> as(ContentType contentType) {
            return route(method, path, handler, contentType, spacers, name, security, version, pathArity);
        }

        @Override
        public ContentTypeBuilder<T> named(String name) {
            return new ContentTypeBuilderImpl<>(method, path, handler, spacers, name, security, version, pathArity);
        }

        @Override
        public ContentTypeBuilder<T> withSecurity(RouteSecurityPolicy security) {
            return new ContentTypeBuilderImpl<>(method, path, handler, spacers, name, security, version, pathArity);
        }

        @Override
        public ContentTypeBuilder<T> versioned(int version) {
            return new ContentTypeBuilderImpl<>(method, path, handler, spacers, name, security, version, pathArity);
        }
    }

    record ParameterBuilderImpl<R>(HttpMethod method,
                                   String path,
                                   List<String> spacers,
                                   String defaultName,
                                   int pathArity) implements ParameterBuilder<R> {
        ParameterBuilderImpl(HttpMethod method, String path) {
            this(method, path, List.of(), "", 0);
        }

        ParameterBuilderImpl(HttpMethod method, String path, List<String> spacers) {
            this(method, path, spacers, "", 0);
        }

        ParameterBuilderImpl(HttpMethod method, String path, List<String> spacers, String defaultName) {
            this(method, path, spacers, defaultName, 0);
        }

        @Override
        public ContentTypeBuilder<R> to(Handler<R> handler) {
            return new ContentTypeBuilderImpl<>(method,
                                                path,
                                                handler,
                                                spacers,
                                                defaultName,
                                                new RouteSecurityPolicy() {},
                                                pathArity);
        }

        // Path parameters - collect spacers from path parameter definitions
        @Override
        public <P1> PathBuilder1<R, P1> withPath(PathParameter<P1> p1) {
            var collected = collectSpacers(p1);

            return new PathBuilder1Impl<>(new ParameterBuilderImpl<>(method, path, collected, defaultName, 1), p1);
        }

        @Override
        public <P1, P2> PathBuilder2<R, P1, P2> withPath(PathParameter<P1> p1, PathParameter<P2> p2) {
            var collected = collectSpacers(p1, p2);

            return new PathBuilder2Impl<>(new ParameterBuilderImpl<>(method, path, collected, defaultName, 2), p1, p2);
        }

        @Override
        public <P1, P2, P3> PathBuilder3<R, P1, P2, P3> withPath(PathParameter<P1> p1,
                                                                 PathParameter<P2> p2,
                                                                 PathParameter<P3> p3) {
            var collected = collectSpacers(p1, p2, p3);

            return new PathBuilder3Impl<>(new ParameterBuilderImpl<>(method, path, collected, defaultName, 3),
                                          p1,
                                          p2,
                                          p3);
        }

        @Override
        public <P1, P2, P3, P4> PathBuilder4<R, P1, P2, P3, P4> withPath(PathParameter<P1> p1,
                                                                         PathParameter<P2> p2,
                                                                         PathParameter<P3> p3,
                                                                         PathParameter<P4> p4) {
            var collected = collectSpacers(p1, p2, p3, p4);

            return new PathBuilder4Impl<>(new ParameterBuilderImpl<>(method, path, collected, defaultName, 4),
                                          p1,
                                          p2,
                                          p3,
                                          p4);
        }

        @Override
        public <P1, P2, P3, P4, P5> PathBuilder5<R, P1, P2, P3, P4, P5> withPath(PathParameter<P1> p1,
                                                                                 PathParameter<P2> p2,
                                                                                 PathParameter<P3> p3,
                                                                                 PathParameter<P4> p4,
                                                                                 PathParameter<P5> p5) {
            var collected = collectSpacers(p1, p2, p3, p4, p5);

            return new PathBuilder5Impl<>(new ParameterBuilderImpl<>(method, path, collected, defaultName, 5),
                                          p1,
                                          p2,
                                          p3,
                                          p4,
                                          p5);
        }

        // Helper to collect spacer text from path parameters
        @SafeVarargs
        private static List<String> collectSpacers(PathParameter<?>... params) {
            var spacers = new ArrayList<String>();

            for (var param : params) {
                if (param instanceof PathParameter.Spacer s) {
                    spacers.add(s.text());
                }
            }

            return List.copyOf(spacers);
        }

        // Body only
        @Override
        public <B> BodyBuilder<R, B> withBody(TypeToken<B> type) {
            return fn -> to(ctx -> ctx.jsonBody(type)
                                      .async()
                                      .flatMap(fn));
        }

        @Override
        public BodyBuilder<R, String> withStringBody() {
            return fn -> to(ctx -> fn.apply(ctx.bodyAsString()));
        }

        @Override
        public BodyBuilder<R, byte[]> withByteBody() {
            return fn -> to(ctx -> fn.apply(ctx.body()));
        }

        @Override
        public BodyBuilder<R, MultipartRequest> withMultipartBody() {
            return fn -> to(ctx -> ctx.multipartRequest()
                                      .async()
                                      .flatMap(fn));
        }

        // Query parameters
        @Override
        public <Q1> QueryBuilder1<R, Q1> withQuery(QueryParameter<Q1> q1) {
            return new QueryBuilder1Impl<>(this, q1);
        }

        @Override
        public <Q1, Q2> QueryBuilder2<R, Q1, Q2> withQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2) {
            return new QueryBuilder2Impl<>(this, q1, q2);
        }

        @Override
        public <Q1, Q2, Q3> QueryBuilder3<R, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                   QueryParameter<Q2> q2,
                                                                   QueryParameter<Q3> q3) {
            return new QueryBuilder3Impl<>(this, q1, q2, q3);
        }

        @Override
        public <Q1, Q2, Q3, Q4> QueryBuilder4<R, Q1, Q2, Q3, Q4> withQuery(QueryParameter<Q1> q1,
                                                                           QueryParameter<Q2> q2,
                                                                           QueryParameter<Q3> q3,
                                                                           QueryParameter<Q4> q4) {
            return new QueryBuilder4Impl<>(this, q1, q2, q3, q4);
        }

        @Override
        public <Q1, Q2, Q3, Q4, Q5> QueryBuilder5<R, Q1, Q2, Q3, Q4, Q5> withQuery(QueryParameter<Q1> q1,
                                                                                   QueryParameter<Q2> q2,
                                                                                   QueryParameter<Q3> q3,
                                                                                   QueryParameter<Q4> q4,
                                                                                   QueryParameter<Q5> q5) {
            return new QueryBuilder5Impl<>(this, q1, q2, q3, q4, q5);
        }
    }

    // Path Builder Implementations
    record PathBuilder1Impl<R, P1>(ParameterBuilder<R> parent, PathParameter<P1> p1) implements PathBuilder1<R, P1> {
        @Override
        public ContentTypeBuilder<R> to(Fn1<Promise<R>, P1> fn) {
            return parent.to(ctx -> ctx.matchPath(p1)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> PathBodyBuilder1<R, P1, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchPath(p1)
                                             .flatMap(v1 -> ctx.jsonBody(type)
                                                               .map(body -> fn.apply(v1, body)))
                                             .async()
                                             .flatMap(p -> p));
        }

        @Override
        public <Q1> PathQueryBuilder1_1<R, P1, Q1> withQuery(QueryParameter<Q1> q1) {
            return new PathQueryBuilder1_1Impl<>(parent, p1, q1);
        }

        @Override
        public <Q1, Q2> PathQueryBuilder1_2<R, P1, Q1, Q2> withQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2) {
            return new PathQueryBuilder1_2Impl<>(parent, p1, q1, q2);
        }

        @Override
        public <Q1, Q2, Q3> PathQueryBuilder1_3<R, P1, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                             QueryParameter<Q2> q2,
                                                                             QueryParameter<Q3> q3) {
            return new PathQueryBuilder1_3Impl<>(parent, p1, q1, q2, q3);
        }

        @Override
        public <Q1, Q2, Q3, Q4> PathQueryBuilder1_4<R, P1, Q1, Q2, Q3, Q4> withQuery(QueryParameter<Q1> q1,
                                                                                     QueryParameter<Q2> q2,
                                                                                     QueryParameter<Q3> q3,
                                                                                     QueryParameter<Q4> q4) {
            return new PathQueryBuilder1_4Impl<>(parent, p1, q1, q2, q3, q4);
        }
    }

    record PathBuilder2Impl<R, P1, P2>(ParameterBuilder<R> parent, PathParameter<P1> p1, PathParameter<P2> p2) implements PathBuilder2<R, P1, P2> {
        @Override
        public ContentTypeBuilder<R> to(Fn2<Promise<R>, P1, P2> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> PathBodyBuilder2<R, P1, P2, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchPath(p1, p2)
                                             .flatMap((v1, v2) -> ctx.jsonBody(type)
                                                                     .map(body -> fn.apply(v1, v2, body)))
                                             .async()
                                             .flatMap(p -> p));
        }

        @Override
        public <Q1> PathQueryBuilder2_1<R, P1, P2, Q1> withQuery(QueryParameter<Q1> q1) {
            return new PathQueryBuilder2_1Impl<>(parent, p1, p2, q1);
        }

        @Override
        public <Q1, Q2> PathQueryBuilder2_2<R, P1, P2, Q1, Q2> withQuery(QueryParameter<Q1> q1, QueryParameter<Q2> q2) {
            return new PathQueryBuilder2_2Impl<>(parent, p1, p2, q1, q2);
        }

        @Override
        public <Q1, Q2, Q3> PathQueryBuilder2_3<R, P1, P2, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                                 QueryParameter<Q2> q2,
                                                                                 QueryParameter<Q3> q3) {
            return new PathQueryBuilder2_3Impl<>(parent, p1, p2, q1, q2, q3);
        }
    }

    record PathBuilder3Impl<R, P1, P2, P3>(ParameterBuilder<R> parent,
                                           PathParameter<P1> p1,
                                           PathParameter<P2> p2,
                                           PathParameter<P3> p3) implements PathBuilder3<R, P1, P2, P3> {
        @Override
        public ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, P2, P3> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> PathBodyBuilder3<R, P1, P2, P3, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchPath(p1, p2, p3)
                                             .flatMap((v1, v2, v3) -> ctx.jsonBody(type)
                                                                         .map(body -> fn.apply(v1, v2, v3, body)))
                                             .async()
                                             .flatMap(p -> p));
        }

        @Override
        public <Q1> PathQueryBuilder3_1<R, P1, P2, P3, Q1> withQuery(QueryParameter<Q1> q1) {
            return new PathQueryBuilder3_1Impl<>(parent, p1, p2, p3, q1);
        }

        @Override
        public <Q1, Q2> PathQueryBuilder3_2<R, P1, P2, P3, Q1, Q2> withQuery(QueryParameter<Q1> q1,
                                                                             QueryParameter<Q2> q2) {
            return new PathQueryBuilder3_2Impl<>(parent, p1, p2, p3, q1, q2);
        }
    }

    record PathBuilder4Impl<R, P1, P2, P3, P4>(ParameterBuilder<R> parent,
                                               PathParameter<P1> p1,
                                               PathParameter<P2> p2,
                                               PathParameter<P3> p3,
                                               PathParameter<P4> p4) implements PathBuilder4<R, P1, P2, P3, P4> {
        @Override
        public ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, P3, P4> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3, p4)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> PathBodyBuilder4<R, P1, P2, P3, P4, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchPath(p1, p2, p3, p4)
                                             .flatMap((v1, v2, v3, v4) -> ctx.jsonBody(type)
                                                                             .map(body -> fn.apply(v1, v2, v3, v4, body)))
                                             .async()
                                             .flatMap(p -> p));
        }

        @Override
        public <Q1> PathQueryBuilder4_1<R, P1, P2, P3, P4, Q1> withQuery(QueryParameter<Q1> q1) {
            return new PathQueryBuilder4_1Impl<>(parent, p1, p2, p3, p4, q1);
        }

        @Override
        public <Q1, Q2, Q3> PathQueryBuilder4_3<R, P1, P2, P3, P4, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                                          QueryParameter<Q2> q2,
                                                                                          QueryParameter<Q3> q3) {
            return new PathQueryBuilder4_3Impl<>(parent, p1, p2, p3, p4, q1, q2, q3);
        }
    }

    record PathBuilder5Impl<R, P1, P2, P3, P4, P5>(ParameterBuilder<R> parent,
                                                   PathParameter<P1> p1,
                                                   PathParameter<P2> p2,
                                                   PathParameter<P3> p3,
                                                   PathParameter<P4> p4,
                                                   PathParameter<P5> p5) implements PathBuilder5<R, P1, P2, P3, P4, P5> {
        @Override
        public ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, P3, P4, P5> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3, p4, p5)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <Q1, Q2, Q3> PathQueryBuilder5_3<R, P1, P2, P3, P4, P5, Q1, Q2, Q3> withQuery(QueryParameter<Q1> q1,
                                                                                              QueryParameter<Q2> q2,
                                                                                              QueryParameter<Q3> q3) {
            return new PathQueryBuilder5_3Impl<>(parent, p1, p2, p3, p4, p5, q1, q2, q3);
        }
    }

    // Query Builder Implementations
    record QueryBuilder1Impl<R, Q1>(ParameterBuilder<R> parent, QueryParameter<Q1> q1) implements QueryBuilder1<R, Q1> {
        @Override
        public ContentTypeBuilder<R> to(Fn1<Promise<R>, Option<Q1>> fn) {
            return parent.to(ctx -> ctx.matchQuery(q1)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> QueryBodyBuilder1<R, Q1, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchQuery(q1)
                                             .flatMap(v1 -> ctx.jsonBody(type)
                                                               .map(body -> fn.apply(v1, body)))
                                             .async()
                                             .flatMap(p -> p));
        }
    }

    record QueryBuilder2Impl<R, Q1, Q2>(ParameterBuilder<R> parent, QueryParameter<Q1> q1, QueryParameter<Q2> q2) implements QueryBuilder2<R, Q1, Q2> {
        @Override
        public ContentTypeBuilder<R> to(Fn2<Promise<R>, Option<Q1>, Option<Q2>> fn) {
            return parent.to(ctx -> ctx.matchQuery(q1, q2)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> QueryBodyBuilder2<R, Q1, Q2, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchQuery(q1, q2)
                                             .flatMap((v1, v2) -> ctx.jsonBody(type)
                                                                     .map(body -> fn.apply(v1, v2, body)))
                                             .async()
                                             .flatMap(p -> p));
        }
    }

    record QueryBuilder3Impl<R, Q1, Q2, Q3>(ParameterBuilder<R> parent,
                                            QueryParameter<Q1> q1,
                                            QueryParameter<Q2> q2,
                                            QueryParameter<Q3> q3) implements QueryBuilder3<R, Q1, Q2, Q3> {
        @Override
        public ContentTypeBuilder<R> to(Fn3<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return parent.to(ctx -> ctx.matchQuery(q1, q2, q3)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> QueryBodyBuilder3<R, Q1, Q2, Q3, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchQuery(q1, q2, q3)
                                             .flatMap((v1, v2, v3) -> ctx.jsonBody(type)
                                                                         .map(body -> fn.apply(v1, v2, v3, body)))
                                             .async()
                                             .flatMap(p -> p));
        }
    }

    record QueryBuilder4Impl<R, Q1, Q2, Q3, Q4>(ParameterBuilder<R> parent,
                                                QueryParameter<Q1> q1,
                                                QueryParameter<Q2> q2,
                                                QueryParameter<Q3> q3,
                                                QueryParameter<Q4> q4) implements QueryBuilder4<R, Q1, Q2, Q3, Q4> {
        @Override
        public ContentTypeBuilder<R> to(Fn4<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn) {
            return parent.to(ctx -> ctx.matchQuery(q1, q2, q3, q4)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> QueryBodyBuilder4<R, Q1, Q2, Q3, Q4, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchQuery(q1, q2, q3, q4)
                                             .flatMap((v1, v2, v3, v4) -> ctx.jsonBody(type)
                                                                             .map(body -> fn.apply(v1, v2, v3, v4, body)))
                                             .async()
                                             .flatMap(p -> p));
        }
    }

    record QueryBuilder5Impl<R, Q1, Q2, Q3, Q4, Q5>(ParameterBuilder<R> parent,
                                                    QueryParameter<Q1> q1,
                                                    QueryParameter<Q2> q2,
                                                    QueryParameter<Q3> q3,
                                                    QueryParameter<Q4> q4,
                                                    QueryParameter<Q5> q5) implements QueryBuilder5<R, Q1, Q2, Q3, Q4, Q5> {
        @Override
        public ContentTypeBuilder<R> to(Fn5<Promise<R>, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>, Option<Q5>> fn) {
            return parent.to(ctx -> ctx.matchQuery(q1, q2, q3, q4, q5)
                                       .map(fn)
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    // Path + Query Builder Implementations
    record PathQueryBuilder1_1Impl<R, P1, Q1>(ParameterBuilder<R> parent, PathParameter<P1> p1, QueryParameter<Q1> q1) implements PathQueryBuilder1_1<R, P1, Q1> {
        @Override
        public ContentTypeBuilder<R> to(Fn2<Promise<R>, P1, Option<Q1>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1)
                                       .flatMap(pv -> ctx.matchQuery(q1)
                                                         .map(qv -> fn.apply(pv, qv)))
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> PathQueryBodyBuilder1_1<R, P1, Q1, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchPath(p1)
                                             .flatMap(pv -> ctx.matchQuery(q1)
                                                               .flatMap(qv -> ctx.jsonBody(type)
                                                                                 .map(body -> fn.apply(pv, qv, body))))
                                             .async()
                                             .flatMap(p -> p));
        }
    }

    record PathQueryBuilder1_2Impl<R, P1, Q1, Q2>(ParameterBuilder<R> parent,
                                                  PathParameter<P1> p1,
                                                  QueryParameter<Q1> q1,
                                                  QueryParameter<Q2> q2) implements PathQueryBuilder1_2<R, P1, Q1, Q2> {
        @Override
        public ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, Option<Q1>, Option<Q2>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1)
                                       .flatMap(pv -> ctx.matchQuery(q1, q2)
                                                         .map((qv1, qv2) -> fn.apply(pv, qv1, qv2)))
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> PathQueryBodyBuilder1_2<R, P1, Q1, Q2, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchPath(p1)
                                             .flatMap(pv -> ctx.matchQuery(q1, q2)
                                                               .flatMap((qv1, qv2) -> ctx.jsonBody(type)
                                                                                         .map(body -> fn.apply(pv,
                                                                                                               qv1,
                                                                                                               qv2,
                                                                                                               body))))
                                             .async()
                                             .flatMap(p -> p));
        }
    }

    record PathQueryBuilder1_3Impl<R, P1, Q1, Q2, Q3>(ParameterBuilder<R> parent,
                                                      PathParameter<P1> p1,
                                                      QueryParameter<Q1> q1,
                                                      QueryParameter<Q2> q2,
                                                      QueryParameter<Q3> q3) implements PathQueryBuilder1_3<R, P1, Q1, Q2, Q3> {
        @Override
        public ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1)
                                       .flatMap(pv -> ctx.matchQuery(q1, q2, q3)
                                                         .map((qv1, qv2, qv3) -> fn.apply(pv, qv1, qv2, qv3)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder1_4Impl<R, P1, Q1, Q2, Q3, Q4>(ParameterBuilder<R> parent,
                                                          PathParameter<P1> p1,
                                                          QueryParameter<Q1> q1,
                                                          QueryParameter<Q2> q2,
                                                          QueryParameter<Q3> q3,
                                                          QueryParameter<Q4> q4) implements PathQueryBuilder1_4<R, P1, Q1, Q2, Q3, Q4> {
        @Override
        public ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, Option<Q1>, Option<Q2>, Option<Q3>, Option<Q4>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1)
                                       .flatMap(pv -> ctx.matchQuery(q1, q2, q3, q4)
                                                         .map((qv1, qv2, qv3, qv4) -> fn.apply(pv, qv1, qv2, qv3, qv4)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder2_1Impl<R, P1, P2, Q1>(ParameterBuilder<R> parent,
                                                  PathParameter<P1> p1,
                                                  PathParameter<P2> p2,
                                                  QueryParameter<Q1> q1) implements PathQueryBuilder2_1<R, P1, P2, Q1> {
        @Override
        public ContentTypeBuilder<R> to(Fn3<Promise<R>, P1, P2, Option<Q1>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2)
                                       .flatMap((pv1, pv2) -> ctx.matchQuery(q1)
                                                                 .map(qv -> fn.apply(pv1, pv2, qv)))
                                       .async()
                                       .flatMap(p -> p));
        }

        @Override
        public <B> PathQueryBodyBuilder2_1<R, P1, P2, Q1, B> withBody(TypeToken<B> type) {
            return fn -> parent.to(ctx -> ctx.matchPath(p1, p2)
                                             .flatMap((pv1, pv2) -> ctx.matchQuery(q1)
                                                                       .flatMap(qv -> ctx.jsonBody(type)
                                                                                         .map(body -> fn.apply(pv1,
                                                                                                               pv2,
                                                                                                               qv,
                                                                                                               body))))
                                             .async()
                                             .flatMap(p -> p));
        }
    }

    record PathQueryBuilder2_2Impl<R, P1, P2, Q1, Q2>(ParameterBuilder<R> parent,
                                                      PathParameter<P1> p1,
                                                      PathParameter<P2> p2,
                                                      QueryParameter<Q1> q1,
                                                      QueryParameter<Q2> q2) implements PathQueryBuilder2_2<R, P1, P2, Q1, Q2> {
        @Override
        public ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, Option<Q1>, Option<Q2>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2)
                                       .flatMap((pv1, pv2) -> ctx.matchQuery(q1, q2)
                                                                 .map((qv1, qv2) -> fn.apply(pv1, pv2, qv1, qv2)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder2_3Impl<R, P1, P2, Q1, Q2, Q3>(ParameterBuilder<R> parent,
                                                          PathParameter<P1> p1,
                                                          PathParameter<P2> p2,
                                                          QueryParameter<Q1> q1,
                                                          QueryParameter<Q2> q2,
                                                          QueryParameter<Q3> q3) implements PathQueryBuilder2_3<R, P1, P2, Q1, Q2, Q3> {
        @Override
        public ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2)
                                       .flatMap((pv1, pv2) -> ctx.matchQuery(q1, q2, q3)
                                                                 .map((qv1, qv2, qv3) -> fn.apply(pv1,
                                                                                                  pv2,
                                                                                                  qv1,
                                                                                                  qv2,
                                                                                                  qv3)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder3_1Impl<R, P1, P2, P3, Q1>(ParameterBuilder<R> parent,
                                                      PathParameter<P1> p1,
                                                      PathParameter<P2> p2,
                                                      PathParameter<P3> p3,
                                                      QueryParameter<Q1> q1) implements PathQueryBuilder3_1<R, P1, P2, P3, Q1> {
        @Override
        public ContentTypeBuilder<R> to(Fn4<Promise<R>, P1, P2, P3, Option<Q1>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3)
                                       .flatMap((pv1, pv2, pv3) -> ctx.matchQuery(q1)
                                                                      .map(qv -> fn.apply(pv1, pv2, pv3, qv)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder3_2Impl<R, P1, P2, P3, Q1, Q2>(ParameterBuilder<R> parent,
                                                          PathParameter<P1> p1,
                                                          PathParameter<P2> p2,
                                                          PathParameter<P3> p3,
                                                          QueryParameter<Q1> q1,
                                                          QueryParameter<Q2> q2) implements PathQueryBuilder3_2<R, P1, P2, P3, Q1, Q2> {
        @Override
        public ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, P3, Option<Q1>, Option<Q2>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3)
                                       .flatMap((pv1, pv2, pv3) -> ctx.matchQuery(q1, q2)
                                                                      .map((qv1, qv2) -> fn.apply(pv1,
                                                                                                  pv2,
                                                                                                  pv3,
                                                                                                  qv1,
                                                                                                  qv2)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder4_1Impl<R, P1, P2, P3, P4, Q1>(ParameterBuilder<R> parent,
                                                          PathParameter<P1> p1,
                                                          PathParameter<P2> p2,
                                                          PathParameter<P3> p3,
                                                          PathParameter<P4> p4,
                                                          QueryParameter<Q1> q1) implements PathQueryBuilder4_1<R, P1, P2, P3, P4, Q1> {
        @Override
        public ContentTypeBuilder<R> to(Fn5<Promise<R>, P1, P2, P3, P4, Option<Q1>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3, p4)
                                       .flatMap((pv1, pv2, pv3, pv4) -> ctx.matchQuery(q1)
                                                                           .map(qv -> fn.apply(pv1, pv2, pv3, pv4, qv)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder4_3Impl<R, P1, P2, P3, P4, Q1, Q2, Q3>(ParameterBuilder<R> parent,
                                                                  PathParameter<P1> p1,
                                                                  PathParameter<P2> p2,
                                                                  PathParameter<P3> p3,
                                                                  PathParameter<P4> p4,
                                                                  QueryParameter<Q1> q1,
                                                                  QueryParameter<Q2> q2,
                                                                  QueryParameter<Q3> q3) implements PathQueryBuilder4_3<R, P1, P2, P3, P4, Q1, Q2, Q3> {
        @Override
        public ContentTypeBuilder<R> to(Fn7<Promise<R>, P1, P2, P3, P4, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3, p4)
                                       .flatMap((pv1, pv2, pv3, pv4) -> ctx.matchQuery(q1, q2, q3)
                                                                           .map((qv1, qv2, qv3) -> fn.apply(pv1,
                                                                                                            pv2,
                                                                                                            pv3,
                                                                                                            pv4,
                                                                                                            qv1,
                                                                                                            qv2,
                                                                                                            qv3)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }

    record PathQueryBuilder5_3Impl<R, P1, P2, P3, P4, P5, Q1, Q2, Q3>(ParameterBuilder<R> parent,
                                                                      PathParameter<P1> p1,
                                                                      PathParameter<P2> p2,
                                                                      PathParameter<P3> p3,
                                                                      PathParameter<P4> p4,
                                                                      PathParameter<P5> p5,
                                                                      QueryParameter<Q1> q1,
                                                                      QueryParameter<Q2> q2,
                                                                      QueryParameter<Q3> q3) implements PathQueryBuilder5_3<R, P1, P2, P3, P4, P5, Q1, Q2, Q3> {
        @Override
        public ContentTypeBuilder<R> to(Fn8<Promise<R>, P1, P2, P3, P4, P5, Option<Q1>, Option<Q2>, Option<Q3>> fn) {
            return parent.to(ctx -> ctx.matchPath(p1, p2, p3, p4, p5)
                                       .flatMap((pv1, pv2, pv3, pv4, pv5) -> ctx.matchQuery(q1, q2, q3)
                                                                                .map((qv1, qv2, qv3) -> fn.apply(pv1,
                                                                                                                 pv2,
                                                                                                                 pv3,
                                                                                                                 pv4,
                                                                                                                 pv5,
                                                                                                                 qv1,
                                                                                                                 qv2,
                                                                                                                 qv3)))
                                       .async()
                                       .flatMap(p -> p));
        }
    }
}
