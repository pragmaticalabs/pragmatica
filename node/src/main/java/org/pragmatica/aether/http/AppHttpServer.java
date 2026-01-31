package org.pragmatica.aether.http;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.invoke.SliceInvoker.SliceInvokerError;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.ProblemDetail;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.net.tcp.TlsConfig;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * Application HTTP server for cluster-wide HTTP routing.
 *
 * <p>Handles HTTP requests by looking up routes in HttpRouteRegistry
 * and forwarding to slice handlers via SliceInvoker.
 *
 * <p>Separate from ManagementServer for security isolation.
 */
public interface AppHttpServer {
    Promise<Unit> start();

    Promise<Unit> stop();

    Option<Integer> boundPort();

    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       HttpRouteRegistry routeRegistry,
                                       Option<SliceInvoker> sliceInvoker,
                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                       Option<TlsConfig> tls) {
        return new AppHttpServerImpl(config, routeRegistry, sliceInvoker, httpRoutePublisher, tls);
    }

    /**
     * @deprecated Use {@link #appHttpServer(AppHttpConfig, HttpRouteRegistry, Option, Option, Option)} with SliceInvoker and HttpRoutePublisher.
     */
    @Deprecated
    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       HttpRouteRegistry routeRegistry,
                                       Option<SliceInvoker> sliceInvoker,
                                       Option<TlsConfig> tls) {
        return new AppHttpServerImpl(config, routeRegistry, sliceInvoker, Option.none(), tls);
    }

    /**
     * @deprecated Use {@link #appHttpServer(AppHttpConfig, HttpRouteRegistry, Option, Option, Option)} with SliceInvoker and HttpRoutePublisher.
     */
    @Deprecated
    static AppHttpServer appHttpServer(AppHttpConfig config,
                                       HttpRouteRegistry routeRegistry,
                                       Option<TlsConfig> tls) {
        return new AppHttpServerImpl(config, routeRegistry, Option.none(), Option.none(), tls);
    }
}

class AppHttpServerImpl implements AppHttpServer {
    private static final Logger log = LoggerFactory.getLogger(AppHttpServerImpl.class);
    private static final int MAX_CONTENT_LENGTH = 16 * 1024 * 1024;

    private final AppHttpConfig config;
    private final HttpRouteRegistry routeRegistry;
    private final Option<SliceInvoker> sliceInvoker;
    private final Option<HttpRoutePublisher> httpRoutePublisher;
    private final SecurityValidator securityValidator;
    private final Option<TlsConfig> tls;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();

    AppHttpServerImpl(AppHttpConfig config,
                      HttpRouteRegistry routeRegistry,
                      Option<SliceInvoker> sliceInvoker,
                      Option<HttpRoutePublisher> httpRoutePublisher,
                      Option<TlsConfig> tls) {
        this.config = config;
        this.routeRegistry = routeRegistry;
        this.sliceInvoker = sliceInvoker;
        this.httpRoutePublisher = httpRoutePublisher;
        this.securityValidator = config.securityEnabled()
                                 ? SecurityValidator.apiKeyValidator(config.apiKeys())
                                 : SecurityValidator.noOpValidator();
        this.tls = tls;
    }

    @Override
    public Promise<Unit> start() {
        if (!config.enabled()) {
            log.info("App HTTP server is disabled");
            return Promise.success(unit());
        }
        log.info("Starting App HTTP server on port {}", config.port());
        var serverConfig = buildServerConfig();
        var requestHandler = new AppHttpRequestHandler(routeRegistry,
                                                       sliceInvoker,
                                                       httpRoutePublisher,
                                                       securityValidator);
        return HttpServer.httpServer(serverConfig, requestHandler::handle)
                         .map(server -> {
                                  serverRef.set(server);
                                  log.info("App HTTP server started on port {}",
                                           server.port());
                                  return unit();
                              })
                         .onFailure(cause -> log.error("Failed to start App HTTP server on port {}: {}",
                                                       config.port(),
                                                       cause.message()));
    }

    private HttpServerConfig buildServerConfig() {
        var serverConfig = HttpServerConfig.httpServerConfig("app-http",
                                                             config.port())
                                           .withMaxContentLength(MAX_CONTENT_LENGTH);
        return tls.fold(() -> serverConfig, serverConfig::withTls);
    }

    @Override
    public Promise<Unit> stop() {
        return Option.option(serverRef.get())
                     .fold(() -> Promise.success(unit()),
                           server -> server.stop()
                                           .onSuccessRun(() -> log.info("App HTTP server stopped")));
    }

    @Override
    public Option<Integer> boundPort() {
        return Option.option(serverRef.get())
                     .map(HttpServer::port);
    }
}

class AppHttpRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(AppHttpRequestHandler.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.defaultJsonMapper();
    private static final org.pragmatica.http.ContentType CONTENT_TYPE_PROBLEM = org.pragmatica.http.ContentType.contentType("application/problem+json",
                                                                                                                            org.pragmatica.http.ContentCategory.JSON);
    private static final Map<Integer, org.pragmatica.http.HttpStatus> STATUS_MAP = Map.ofEntries(Map.entry(200,
                                                                                                           org.pragmatica.http.HttpStatus.OK),
                                                                                                 Map.entry(201,
                                                                                                           org.pragmatica.http.HttpStatus.CREATED),
                                                                                                 Map.entry(202,
                                                                                                           org.pragmatica.http.HttpStatus.ACCEPTED),
                                                                                                 Map.entry(204,
                                                                                                           org.pragmatica.http.HttpStatus.NO_CONTENT),
                                                                                                 Map.entry(301,
                                                                                                           org.pragmatica.http.HttpStatus.MOVED_PERMANENTLY),
                                                                                                 Map.entry(302,
                                                                                                           org.pragmatica.http.HttpStatus.FOUND),
                                                                                                 Map.entry(304,
                                                                                                           org.pragmatica.http.HttpStatus.NOT_MODIFIED),
                                                                                                 Map.entry(307,
                                                                                                           org.pragmatica.http.HttpStatus.TEMPORARY_REDIRECT),
                                                                                                 Map.entry(308,
                                                                                                           org.pragmatica.http.HttpStatus.PERMANENT_REDIRECT),
                                                                                                 Map.entry(400,
                                                                                                           org.pragmatica.http.HttpStatus.BAD_REQUEST),
                                                                                                 Map.entry(401,
                                                                                                           org.pragmatica.http.HttpStatus.UNAUTHORIZED),
                                                                                                 Map.entry(403,
                                                                                                           org.pragmatica.http.HttpStatus.FORBIDDEN),
                                                                                                 Map.entry(404,
                                                                                                           org.pragmatica.http.HttpStatus.NOT_FOUND),
                                                                                                 Map.entry(405,
                                                                                                           org.pragmatica.http.HttpStatus.METHOD_NOT_ALLOWED),
                                                                                                 Map.entry(409,
                                                                                                           org.pragmatica.http.HttpStatus.CONFLICT),
                                                                                                 Map.entry(422,
                                                                                                           org.pragmatica.http.HttpStatus.UNPROCESSABLE_ENTITY),
                                                                                                 Map.entry(429,
                                                                                                           org.pragmatica.http.HttpStatus.TOO_MANY_REQUESTS),
                                                                                                 Map.entry(500,
                                                                                                           org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR),
                                                                                                 Map.entry(501,
                                                                                                           org.pragmatica.http.HttpStatus.NOT_IMPLEMENTED),
                                                                                                 Map.entry(502,
                                                                                                           org.pragmatica.http.HttpStatus.BAD_GATEWAY),
                                                                                                 Map.entry(503,
                                                                                                           org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE),
                                                                                                 Map.entry(504,
                                                                                                           org.pragmatica.http.HttpStatus.GATEWAY_TIMEOUT));

    private final HttpRouteRegistry routeRegistry;
    private final Option<SliceInvoker> sliceInvoker;
    private final Option<HttpRoutePublisher> httpRoutePublisher;
    private final SecurityValidator securityValidator;
    private final ConcurrentHashMap<String, MethodHandle<HttpResponseData, HttpRequestContext>> methodHandleCache;

    AppHttpRequestHandler(HttpRouteRegistry routeRegistry,
                          Option<SliceInvoker> sliceInvoker,
                          Option<HttpRoutePublisher> httpRoutePublisher,
                          SecurityValidator securityValidator) {
        this.routeRegistry = routeRegistry;
        this.sliceInvoker = sliceInvoker;
        this.httpRoutePublisher = httpRoutePublisher;
        this.securityValidator = securityValidator;
        this.methodHandleCache = new ConcurrentHashMap<>();
    }

    void handle(RequestContext request, ResponseWriter response) {
        var method = request.method()
                            .name();
        var path = request.path();
        var requestId = request.requestId();
        log.debug("Received {} {} [{}]", method, path, requestId);
        routeRegistry.findRoute(method, path)
                     .onPresent(route -> handleRouteFound(request, response, route, path, requestId))
                     .onEmpty(() -> {
                                  log.warn("No route found for {} {} [{}]. Available routes: {}",
                                           method,
                                           path,
                                           requestId,
                                           routeRegistry.allRoutes()
                                                        .size());
                                  sendProblem(response,
                                              HttpStatus.NOT_FOUND,
                                              "No route found for " + method + " " + path,
                                              path,
                                              requestId);
                              });
    }

    private void handleRouteFound(RequestContext request,
                                  ResponseWriter response,
                                  HttpRouteRegistry.RouteInfo route,
                                  String path,
                                  String requestId) {
        if (sliceInvoker.isEmpty()) {
            log.debug("Route found but SliceInvoker not available: {} {} -> {}:{} [{}]",
                      route.httpMethod(),
                      route.pathPrefix(),
                      route.artifact(),
                      route.sliceMethod(),
                      requestId);
            sendProblem(response, HttpStatus.SERVICE_UNAVAILABLE, "Slice invoker not initialized", path, requestId);
            return;
        }
        var initialContext = toHttpRequestContext(request, requestId);
        securityValidator.validate(initialContext,
                                   route.securityPolicy())
                         .onFailure(cause -> sendSecurityError(response, cause, path, requestId))
                         .onSuccess(securityContext -> invokeSecuredRoute(response,
                                                                          initialContext,
                                                                          securityContext,
                                                                          route,
                                                                          path,
                                                                          requestId));
    }

    private HttpRequestContext toHttpRequestContext(RequestContext request, String requestId) {
        var queryParams = request.queryParams()
                                 .asMap();
        var headers = request.headers()
                             .asMap();
        var body = request.body();
        return HttpRequestContext.httpRequestContext(request.path(),
                                                     request.method()
                                                            .name(),
                                                     queryParams,
                                                     headers,
                                                     body,
                                                     requestId);
    }

    private void invokeSecuredRoute(ResponseWriter response,
                                    HttpRequestContext initialContext,
                                    SecurityContext securityContext,
                                    HttpRouteRegistry.RouteInfo route,
                                    String path,
                                    String requestId) {
        var securedContext = initialContext.withSecurity(securityContext);
        log.debug("Route found: {} {} -> {}:{} [{}] security={}",
                  route.httpMethod(),
                  route.pathPrefix(),
                  route.artifact(),
                  route.sliceMethod(),
                  requestId,
                  securedContext.security()
                                .principal()
                                .value());
        // TRY LOCAL ROUTING FIRST via SliceRouter
        var artifact = Artifact.artifact(route.artifact());
        if (artifact.isSuccess() && httpRoutePublisher.isPresent()) {
            var artObj = artifact.unwrap();
            var localRouter = httpRoutePublisher.unwrap()
                                                .getSliceRouter(artObj);
            if (localRouter.isPresent()) {
                log.debug("Using local SliceRouter for {} [{}]", artObj, requestId);
                localRouter.unwrap()
                           .handle(securedContext)
                           .onSuccess(responseData -> sendResponse(response, responseData, requestId))
                           .onFailure(cause -> handleInvocationError(response, cause, path, requestId));
                return;
            }
        }
        // FALL BACK to remote SliceInvoker path
        var cacheKey = route.artifact() + ":" + route.sliceMethod();
        var methodHandle = getOrCreateMethodHandle(cacheKey, route);
        if (methodHandle.isEmpty()) {
            sendProblem(response,
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to create method handle for route",
                        path,
                        requestId);
            return;
        }
        methodHandle.unwrap()
                    .invoke(securedContext)
                    .onSuccess(responseData -> sendResponse(response, responseData, requestId))
                    .onFailure(cause -> handleInvocationError(response, cause, path, requestId));
    }

    private void sendSecurityError(ResponseWriter response, Cause cause, String path, String requestId) {
        log.debug("Security validation failed [{}]: {}", requestId, cause.message());
        var status = switch (cause) {
            case SecurityError.MissingCredentials _ -> HttpStatus.UNAUTHORIZED;
            case SecurityError.InvalidCredentials _ -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.UNAUTHORIZED;
        };
        sendProblem(response, status, cause.message(), path, requestId);
    }

    private void handleInvocationError(ResponseWriter response, Cause cause, String path, String requestId) {
        log.error("Slice invocation failed [{}]: {}", requestId, cause.message());
        var status = switch (cause) {
            case SliceInvokerError.AllInstancesFailedError _ -> HttpStatus.SERVICE_UNAVAILABLE;
            case SliceInvokerError.InvocationError _ -> HttpStatus.BAD_GATEWAY;
            default -> {
                if (cause.message() != null && cause.message()
                                                    .toLowerCase()
                                                    .contains("timeout")) {
                    yield HttpStatus.GATEWAY_TIMEOUT;
                }
                yield HttpStatus.BAD_GATEWAY;
            }
        };
        sendProblem(response, status, "Slice invocation failed: " + cause.message(), path, requestId);
    }

    private Option<MethodHandle<HttpResponseData, HttpRequestContext>> getOrCreateMethodHandle(String cacheKey,
                                                                                               HttpRouteRegistry.RouteInfo route) {
        var cached = methodHandleCache.get(cacheKey);
        if (cached != null) {
            return Option.some(cached);
        }
        return sliceInvoker.flatMap(invoker -> invoker.methodHandle(route.artifact(),
                                                                    route.sliceMethod(),
                                                                    TypeToken.of(HttpRequestContext.class),
                                                                    TypeToken.of(HttpResponseData.class))
                                                      .onSuccess(handle -> methodHandleCache.put(cacheKey, handle))
                                                      .option());
    }

    private void sendProblem(ResponseWriter response,
                             HttpStatus status,
                             String detail,
                             String instance,
                             String requestId) {
        var problem = ProblemDetail.problemDetail(status, detail, instance, requestId);
        JSON_MAPPER.writeAsString(problem)
                   .onSuccess(json -> response.header(ResponseWriter.X_REQUEST_ID, requestId)
                                              .write(toServerStatus(status.code()),
                                                     json.getBytes(StandardCharsets.UTF_8),
                                                     CONTENT_TYPE_PROBLEM))
                   .onFailure(cause -> {
                                  log.error("Failed to serialize ProblemDetail: {}",
                                            cause.message());
                                  sendPlainError(response, status, requestId);
                              });
    }

    private void sendResponse(ResponseWriter response, HttpResponseData responseData, String requestId) {
        log.debug("Sending response [{}]: {} {}", requestId, responseData.statusCode(), responseData.headers());
        var writer = response.header(ResponseWriter.X_REQUEST_ID, requestId);
        for (var entry : responseData.headers()
                                     .entrySet()) {
            writer = writer.header(entry.getKey(), entry.getValue());
        }
        var contentType = Option.option(responseData.headers()
                                                    .get("Content-Type"))
                                .map(ct -> org.pragmatica.http.ContentType.contentType(ct,
                                                                                       org.pragmatica.http.ContentCategory.JSON))
                                .or(CommonContentType.APPLICATION_JSON);
        writer.write(toServerStatus(responseData.statusCode()), responseData.body(), contentType);
    }

    private void sendPlainError(ResponseWriter response, HttpStatus status, String requestId) {
        var content = "{\"error\":\"" + status.message() + "\"}";
        response.header(ResponseWriter.X_REQUEST_ID, requestId)
                .write(toServerStatus(status.code()),
                       content.getBytes(StandardCharsets.UTF_8),
                       CommonContentType.APPLICATION_JSON);
    }

    private static org.pragmatica.http.HttpStatus toServerStatus(int code) {
        return Option.option(STATUS_MAP.get(code))
                     .or(org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
