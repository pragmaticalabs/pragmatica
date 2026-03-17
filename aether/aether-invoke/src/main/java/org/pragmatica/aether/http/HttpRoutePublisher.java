package org.pragmatica.aether.http;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpRequestHandlerFactory;
import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Publishes HTTP routes to KV-Store when slices become active.
///
///
/// Discovers {@link HttpRequestHandlerFactory} implementations via ServiceLoader,
/// creates handlers, and publishes their route definitions to the cluster.
///
///
/// Each node writes flat per-node keys (HttpNodeRouteKey) containing (method, prefix, nodeId).
/// No read-modify-write, no races. Consumers reconstruct node sets from flat keys in-memory.
public interface HttpRoutePublisher {
    /// Publish HTTP routes for a slice that just became active.
    ///
    /// @param artifact     The slice artifact
    /// @param classLoader  The slice's class loader for ServiceLoader discovery
    /// @param invokerFacade SliceInvokerFacade for creating handlers
    /// @return Promise completing when routes are published
    Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade);

    /// Publish HTTP routes for a slice with direct slice instance access.
    ///
    /// This method first attempts to discover {@link SliceRouterFactory} implementations,
    /// which provide type-safe routing with better performance. Falls back to
    /// {@link HttpRequestHandlerFactory} pattern if no SliceRouterFactory is found.
    ///
    /// @param artifact      The slice artifact
    /// @param classLoader   The slice's class loader for ServiceLoader discovery
    /// @param sliceInstance The slice implementation instance
    /// @param invokerFacade SliceInvokerFacade for fallback handler creation
    /// @return Promise completing when routes are published
    Promise<Unit> publishRoutes(Artifact artifact,
                                ClassLoader classLoader,
                                Object sliceInstance,
                                SliceInvokerFacade invokerFacade);

    /// Unpublish HTTP routes when a slice is deactivated.
    ///
    /// @param artifact The slice artifact
    /// @return Promise completing when routes are unpublished
    Promise<Unit> unpublishRoutes(Artifact artifact);

    /// Get the handler for a slice (for local invocation).
    Option<HttpRequestHandler> getHandler(Artifact artifact);

    /// Get the SliceRouter for a slice (for local invocation via http-routing).
    ///
    /// @param artifact The slice artifact
    /// @return SliceRouter if one exists for the artifact
    Option<SliceRouter> getSliceRouter(Artifact artifact);

    /// Get all HTTP routes that this node can handle locally.
    /// Used by AppHttpServer to distinguish local vs remote routes.
    ///
    /// @return Set of HttpNodeRouteKey for locally available routes
    Set<HttpNodeRouteKey> allLocalRoutes();

    /// Find a local SliceRouter that can handle the given HTTP method and path prefix.
    /// Used by AppHttpServer for local request handling.
    ///
    /// @param httpMethod HTTP method (GET, POST, etc.)
    /// @param pathPrefix path prefix
    /// @return SliceRouter if this node has a local handler for the route
    Option<SliceRouter> findLocalRouter(String httpMethod, String pathPrefix);

    /// Find local route info for a given HTTP method and path.
    /// Used by AppHttpServer to get artifact/method info for local routing.
    ///
    /// @param httpMethod HTTP method
    /// @param path request path
    /// @return LocalRouteInfo if found locally
    Option<LocalRouteInfo> findLocalRoute(String httpMethod, String path);

    /// Local route information containing artifact and method details.
    /// This information is only available on nodes that have the route registered.
    record LocalRouteInfo(String httpMethod,
                          String pathPrefix,
                          String artifactCoord,
                          String sliceMethod,
                          RouteSecurityPolicy security) {
        public static LocalRouteInfo localRouteInfo(HttpRouteDefinition def) {
            return new LocalRouteInfo(def.httpMethod(),
                                      def.pathPrefix(),
                                      def.artifactCoord(),
                                      def.sliceMethod(),
                                      def.security());
        }
    }

    static HttpRoutePublisher httpRoutePublisher(NodeId selfNodeId,
                                                 ClusterNode<KVCommand<AetherKey>> cluster) {
        return new HttpRoutePublisherImpl(selfNodeId, cluster);
    }
}

class HttpRoutePublisherImpl implements HttpRoutePublisher {
    private static final Logger log = LoggerFactory.getLogger(HttpRoutePublisherImpl.class);

    private final NodeId selfNodeId;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final Map<Artifact, HttpRequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Artifact, SliceRouter> sliceRouters = new ConcurrentHashMap<>();
    private final Map<Artifact, List<HttpRouteDefinition>> publishedRoutes = new ConcurrentHashMap<>();
    private final RouteMetadataExtractor routeMetadataExtractor = RouteMetadataExtractor.routeMetadataExtractor();

    HttpRoutePublisherImpl(NodeId selfNodeId,
                           ClusterNode<KVCommand<AetherKey>> cluster) {
        this.selfNodeId = selfNodeId;
        this.cluster = cluster;
    }

    @Override
    public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade) {
        log.debug("publishRoutes(3-arg) called for artifact={}, classLoader={}",
                  artifact,
                  classLoader.getClass()
                             .getName());
        // Discover HttpRequestHandlerFactory via ServiceLoader
        var factories = ServiceLoader.load(HttpRequestHandlerFactory.class, classLoader);
        var iterator = factories.iterator();
        if (!iterator.hasNext()) {
            log.debug("ServiceLoader: No HttpRequestHandlerFactory found for slice {}", artifact);
            return Promise.unitPromise();
        }
        // Use first factory found
        var factory = iterator.next();
        log.debug("ServiceLoader: Found HttpRequestHandlerFactory for slice {}: {}",
                  artifact,
                  factory.getClass()
                         .getName());
        // Create handler
        var handler = factory.create(invokerFacade);
        handlers.put(artifact, handler);
        // Get routes
        var routes = handler.routes();
        log.debug("Route extraction: {} routes found for slice {}", routes.size(), artifact);
        if (routes.isEmpty()) {
            log.debug("No HTTP routes defined for slice {}, skipping publication", artifact);
            return Promise.unitPromise();
        }
        // Store published routes for unpublishing later
        publishedRoutes.put(artifact, routes);
        return publishRoutesToCluster(routes, artifact);
    }

    @Override
    public Promise<Unit> publishRoutes(Artifact artifact,
                                       ClassLoader classLoader,
                                       Object sliceInstance,
                                       SliceInvokerFacade invokerFacade) {
        log.debug("publishRoutes(4-arg) called for artifact={}, sliceInstance={}, classLoader={}",
                  artifact,
                  sliceInstance.getClass()
                               .getName(),
                  classLoader.getClass()
                             .getName());
        // Try SliceRouterFactory first (new pattern)
        var routerFactories = ServiceLoader.load(SliceRouterFactory.class, classLoader);
        int factoryCount = 0;
        for (var factory : routerFactories) {
            factoryCount++;
            log.debug("ServiceLoader: Checking SliceRouterFactory {} for slice type match with {}",
                      factory.getClass()
                             .getName(),
                      sliceInstance.getClass()
                                   .getName());
            if (factory.sliceType()
                       .isInstance(sliceInstance)) {
                log.debug("ServiceLoader: SliceRouterFactory {} matches slice instance",
                          factory.getClass()
                                 .getName());
                return publishViaSliceRouterFactory(artifact, factory, sliceInstance);
            }
        }
        log.debug("ServiceLoader: {} SliceRouterFactory(s) found, none matched. Falling back to HttpRequestHandlerFactory",
                  factoryCount);
        // Fall back to existing HttpRequestHandlerFactory pattern
        return publishRoutes(artifact, classLoader, invokerFacade);
    }

    @SuppressWarnings("unchecked")
    private Promise<Unit> publishViaSliceRouterFactory(Artifact artifact,
                                                       SliceRouterFactory<?> factory,
                                                       Object sliceInstance) {
        log.debug("publishViaSliceRouterFactory: artifact={}, factory={}",
                  artifact,
                  factory.getClass()
                         .getName());
        var typedFactory = (SliceRouterFactory<Object>) factory;
        var router = typedFactory.create(sliceInstance);
        sliceRouters.put(artifact, router);
        // Extract routes - factory implements RouteSource
        if (factory instanceof RouteSource routeSource) {
            var routes = routeMetadataExtractor.extract(routeSource, artifact.asString());
            log.debug("Route extraction: {} routes found for slice {} via SliceRouterFactory", routes.size(), artifact);
            if (routes.isEmpty()) {
                log.debug("No HTTP routes defined for slice {}, skipping publication", artifact);
                return Promise.unitPromise();
            }
            publishedRoutes.put(artifact, routes);
            return publishRoutesToCluster(routes, artifact);
        }
        log.warn("SliceRouterFactory {} does not implement RouteSource, no routes published",
                 factory.getClass()
                        .getName());
        return Promise.unitPromise();
    }

    private Promise<Unit> publishRoutesToCluster(List<HttpRouteDefinition> routes, Artifact artifact) {
        log.debug("Publishing {} HTTP routes for slice {}", routes.size(), artifact);
        var routeEntries = routes.stream()
                                 .map(HttpRoutePublisherImpl::toRouteEntry)
                                 .toList();
        var key = NodeRoutesKey.nodeRoutesKey(selfNodeId, artifact);
        var value = new NodeRoutesValue(routeEntries);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
        return cluster.apply(List.of(command))
                      .mapToUnit()
                      .onSuccess(_ -> log.debug("Published {} HTTP routes for slice {}",
                                                routes.size(),
                                                artifact));
    }

    private static RouteEntry toRouteEntry(HttpRouteDefinition route) {
        return RouteEntry.activeRoute(route.httpMethod(), route.pathPrefix(), route.sliceMethod());
    }

    @Override
    public Promise<Unit> unpublishRoutes(Artifact artifact) {
        handlers.remove(artifact);
        sliceRouters.remove(artifact);
        var routes = publishedRoutes.remove(artifact);
        if (routes == null || routes.isEmpty()) {
            return Promise.unitPromise();
        }
        return unpublishRoutesFromCluster(artifact, routes);
    }

    private Promise<Unit> unpublishRoutesFromCluster(Artifact artifact, List<HttpRouteDefinition> routes) {
        KVCommand<AetherKey> command = new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(selfNodeId, artifact));
        return cluster.apply(List.of(command))
                      .mapToUnit()
                      .onSuccess(_ -> log.debug("Unpublished {} HTTP routes for {}",
                                                routes.size(),
                                                artifact))
                      .onFailure(cause -> log.error("Failed to unpublish HTTP routes for {}: {}",
                                                    artifact,
                                                    cause.message()));
    }

    @Override
    public Option<HttpRequestHandler> getHandler(Artifact artifact) {
        return Option.option(handlers.get(artifact));
    }

    @Override
    public Option<SliceRouter> getSliceRouter(Artifact artifact) {
        return Option.option(sliceRouters.get(artifact));
    }

    @Override
    public Set<HttpNodeRouteKey> allLocalRoutes() {
        var localRoutes = new java.util.HashSet<HttpNodeRouteKey>();
        for (var routes : publishedRoutes.values()) {
            for (var route : routes) {
                localRoutes.add(HttpNodeRouteKey.httpNodeRouteKey(route.httpMethod(), route.pathPrefix(), selfNodeId));
            }
        }
        return Set.copyOf(localRoutes);
    }

    @Override
    public Option<SliceRouter> findLocalRouter(String httpMethod, String pathPrefix) {
        // Find which artifact has a route matching the given method and path
        for (var entry : publishedRoutes.entrySet()) {
            var artifact = entry.getKey();
            var routes = entry.getValue();
            for (var route : routes) {
                if (route.httpMethod()
                         .equalsIgnoreCase(httpMethod) &&
                route.pathPrefix()
                     .equals(pathPrefix)) {
                    return Option.option(sliceRouters.get(artifact));
                }
            }
        }
        return Option.none();
    }

    @Override
    public Option<LocalRouteInfo> findLocalRoute(String httpMethod, String path) {
        var normalizedPath = normalizePath(path);
        // Try prefix matching against all published routes
        for (var routes : publishedRoutes.values()) {
            for (var route : routes) {
                if (route.httpMethod()
                         .equalsIgnoreCase(httpMethod) &&
                normalizedPath.startsWith(route.pathPrefix())) {
                    return Option.some(LocalRouteInfo.localRouteInfo(route));
                }
            }
        }
        return Option.none();
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        var normalized = path.strip();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }
}
