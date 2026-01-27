package org.pragmatica.aether.http;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpRequestHandlerFactory;
import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes HTTP routes to KV-Store when slices become active.
 *
 * <p>Discovers {@link HttpRequestHandlerFactory} implementations via ServiceLoader,
 * creates handlers, and publishes their route definitions to the cluster.
 *
 * <p>Routes are idempotent - same route from multiple nodes results in same KV entry.
 */
public interface HttpRoutePublisher {
    /**
     * Publish HTTP routes for a slice that just became active.
     *
     * @param artifact     The slice artifact
     * @param classLoader  The slice's class loader for ServiceLoader discovery
     * @param invokerFacade SliceInvokerFacade for creating handlers
     * @return Promise completing when routes are published
     */
    Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade);

    /**
     * Publish HTTP routes for a slice with direct slice instance access.
     * <p>
     * This method first attempts to discover {@link SliceRouterFactory} implementations,
     * which provide type-safe routing with better performance. Falls back to
     * {@link HttpRequestHandlerFactory} pattern if no SliceRouterFactory is found.
     *
     * @param artifact      The slice artifact
     * @param classLoader   The slice's class loader for ServiceLoader discovery
     * @param sliceInstance The slice implementation instance
     * @param invokerFacade SliceInvokerFacade for fallback handler creation
     * @return Promise completing when routes are published
     */
    Promise<Unit> publishRoutes(Artifact artifact,
                                ClassLoader classLoader,
                                Object sliceInstance,
                                SliceInvokerFacade invokerFacade);

    /**
     * Unpublish HTTP routes when a slice is deactivated.
     *
     * @param artifact The slice artifact
     * @return Promise completing when routes are unpublished
     */
    Promise<Unit> unpublishRoutes(Artifact artifact);

    /**
     * Get the handler for a slice (for local invocation).
     */
    Option<HttpRequestHandler> getHandler(Artifact artifact);

    /**
     * Get the SliceRouter for a slice (for local invocation via http-routing).
     *
     * @param artifact The slice artifact
     * @return SliceRouter if one exists for the artifact
     */
    Option<SliceRouter> getSliceRouter(Artifact artifact);

    static HttpRoutePublisher httpRoutePublisher(ClusterNode<KVCommand<AetherKey>> cluster) {
        return new HttpRoutePublisherImpl(cluster);
    }
}

class HttpRoutePublisherImpl implements HttpRoutePublisher {
    private static final Logger log = LoggerFactory.getLogger(HttpRoutePublisherImpl.class);

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final Map<Artifact, HttpRequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Artifact, SliceRouter> sliceRouters = new ConcurrentHashMap<>();
    private final Map<Artifact, List<HttpRouteDefinition>> publishedRoutes = new ConcurrentHashMap<>();
    private final RouteMetadataExtractor routeMetadataExtractor = RouteMetadataExtractor.routeMetadataExtractor();

    HttpRoutePublisherImpl(ClusterNode<KVCommand<AetherKey>> cluster) {
        this.cluster = cluster;
    }

    @Override
    public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade) {
        // Discover HttpRequestHandlerFactory via ServiceLoader
        var factories = ServiceLoader.load(HttpRequestHandlerFactory.class, classLoader);
        var iterator = factories.iterator();
        if (!iterator.hasNext()) {
            log.debug("No HttpRequestHandlerFactory found for slice {}", artifact);
            return Promise.unitPromise();
        }
        // Use first factory found
        var factory = iterator.next();
        log.info("Found HttpRequestHandlerFactory for slice {}: {}",
                 artifact,
                 factory.getClass()
                        .getName());
        // Create handler
        var handler = factory.create(invokerFacade);
        handlers.put(artifact, handler);
        // Get routes
        var routes = handler.routes();
        if (routes.isEmpty()) {
            log.debug("No HTTP routes defined for slice {}", artifact);
            return Promise.unitPromise();
        }
        // Create KV commands for route publication
        var commands = routes.stream()
                             .map(this::createRoutePutCommand)
                             .toList();
        // Store published routes for unpublishing later
        publishedRoutes.put(artifact, routes);
        // Submit to cluster
        return cluster.apply(commands)
                      .mapToUnit()
                      .onSuccess(_ -> log.info("Published {} HTTP routes for slice {}",
                                               routes.size(),
                                               artifact))
                      .onFailure(cause -> log.error("Failed to publish HTTP routes for {}: {}",
                                                    artifact,
                                                    cause.message()));
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Promise<Unit> publishRoutes(Artifact artifact,
                                       ClassLoader classLoader,
                                       Object sliceInstance,
                                       SliceInvokerFacade invokerFacade) {
        // Try SliceRouterFactory first (new pattern)
        var routerFactories = ServiceLoader.load(SliceRouterFactory.class, classLoader);
        for (var factory : routerFactories) {
            if (factory.sliceType()
                       .isInstance(sliceInstance)) {
                return publishViaSliceRouterFactory(artifact, factory, sliceInstance);
            }
        }
        // Fall back to existing HttpRequestHandlerFactory pattern
        return publishRoutes(artifact, classLoader, invokerFacade);
    }

    @SuppressWarnings("unchecked")
    private Promise<Unit> publishViaSliceRouterFactory(Artifact artifact,
                                                       SliceRouterFactory<?> factory,
                                                       Object sliceInstance) {
        log.info("Found SliceRouterFactory for slice {}: {}",
                 artifact,
                 factory.getClass()
                        .getName());
        var typedFactory = (SliceRouterFactory<Object>) factory;
        var router = typedFactory.create(sliceInstance);
        sliceRouters.put(artifact, router);
        // Extract routes - factory implements RouteSource
        if (factory instanceof RouteSource routeSource) {
            var routes = routeMetadataExtractor.extract(routeSource, artifact.asString());
            if (routes.isEmpty()) {
                log.debug("No HTTP routes defined for slice {}", artifact);
                return Promise.unitPromise();
            }
            publishedRoutes.put(artifact, routes);
            var commands = routes.stream()
                                 .map(this::createRoutePutCommand)
                                 .toList();
            return cluster.apply(commands)
                          .mapToUnit()
                          .onSuccess(_ -> log.info("Published {} HTTP routes for slice {} via SliceRouterFactory",
                                                   routes.size(),
                                                   artifact))
                          .onFailure(cause -> log.error("Failed to publish HTTP routes for {}: {}",
                                                        artifact,
                                                        cause.message()));
        }
        log.warn("SliceRouterFactory {} does not implement RouteSource, no routes published",
                 factory.getClass()
                        .getName());
        return Promise.unitPromise();
    }

    @Override
    public Promise<Unit> unpublishRoutes(Artifact artifact) {
        handlers.remove(artifact);
        sliceRouters.remove(artifact);
        return Option.option(publishedRoutes.remove(artifact))
                     .filter(routes -> !routes.isEmpty())
                     .map(this::unpublishRoutesToCluster)
                     .or(Promise.unitPromise());
    }

    private Promise<Unit> unpublishRoutesToCluster(List<HttpRouteDefinition> routes) {
        var commands = routes.stream()
                             .map(this::createRouteRemoveCommand)
                             .toList();
        return cluster.apply(commands)
                      .mapToUnit()
                      .onSuccess(_ -> log.info("Unpublished {} HTTP routes",
                                               routes.size()))
                      .onFailure(cause -> log.error("Failed to unpublish HTTP routes: {}",
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

    private KVCommand<AetherKey> createRoutePutCommand(HttpRouteDefinition route) {
        var key = HttpRouteKey.httpRouteKey(route.httpMethod(), route.pathPrefix());
        var value = HttpRouteValue.httpRouteValue(route.artifactCoord(),
                                                  route.sliceMethod(),
                                                  route.security()
                                                       .asString());
        return new KVCommand.Put<>(key, value);
    }

    private KVCommand<AetherKey> createRouteRemoveCommand(HttpRouteDefinition route) {
        var key = HttpRouteKey.httpRouteKey(route.httpMethod(), route.pathPrefix());
        return new KVCommand.Remove<>(key);
    }
}
