// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpRequestHandlerFactory;
import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.http.routing.Handler;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteMounting;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface HttpRoutePublisher {
    Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade);

    Promise<Unit> publishRoutes(Artifact artifact,
                                ClassLoader classLoader,
                                Object sliceInstance,
                                SliceInvokerFacade invokerFacade);

    boolean hasRoutes(ClassLoader classLoader, Object sliceInstance);
    Promise<Unit> unpublishRoutes(Artifact artifact);
    Option<HttpRequestHandler> getHandler(Artifact artifact);
    Option<SliceRouter> getSliceRouter(Artifact artifact);
    Set<HttpNodeRouteKey> allLocalRoutes();
    Option<SliceRouter> findLocalRouter(String httpMethod, String pathPrefix);
    Option<LocalRouteInfo> findLocalRoute(String httpMethod, String path);

    Unit updateSecurityOverrides(SecurityOverrides overrides);

    /// Install the #198 §11.1 versioning metrics sink. The management server owns the metrics backend
    /// and binds it here after node boot; routers created before this call observe the sink lazily via
    /// the forwarding indirection. Idempotent — the last sink wins.
    ///
    /// @param sink the versioning metrics sink
    /// @return unit
    Unit setVersioningMetricsSink(VersioningMetricsSink sink);

    /// Bind the write-side observability cell registrar (#277 increment 2) so each published route's
    /// handler is wrapped once with a per-injection-point cell that a KV config put can swap. Late-bound
    /// after the config registry is built at boot; routers published before this call decorate with the
    /// no-op registrar (cells stay identity). Idempotent — the last registrar wins.
    ///
    /// @param registrar the write-side cell registrar
    /// @return unit
    Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar);

    /// Snapshot the version registries of every deployed slice that declares API versions (#198
    /// §11.3), keyed by artifact. Drives the `GET /api/versions` introspection endpoint; unversioned
    /// slices are omitted.
    ///
    /// @return artifact → version registry for each deployed versioned slice
    Map<Artifact, SliceVersionRegistry> versionRegistries();

    record LocalRouteInfo(String httpMethod,
                          String pathPrefix,
                          String artifactCoord,
                          String sliceMethod,
                          SecurityPolicy security) {
        public static LocalRouteInfo localRouteInfo(HttpRouteDefinition def) {
            return new LocalRouteInfo(def.httpMethod(),
                                      def.pathPrefix(),
                                      def.artifactCoord(),
                                      def.sliceMethod(),
                                      def.security());
        }
    }

    static HttpRoutePublisher httpRoutePublisher(NodeId selfNodeId, ClusterNode<KVCommand<AetherKey>> cluster) {
        return httpRoutePublisher(selfNodeId, cluster, GenerationSnapshotSource.noop());
    }

    static HttpRoutePublisher httpRoutePublisher(NodeId selfNodeId,
                                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                                 GenerationSnapshotSource snapshotSource) {
        return httpRoutePublisher(selfNodeId, cluster, snapshotSource, RouteMountMode.pathMode());
    }

    static HttpRoutePublisher httpRoutePublisher(NodeId selfNodeId,
                                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                                 GenerationSnapshotSource snapshotSource,
                                                 RouteMountMode mountMode) {
        return new HttpRoutePublisherImpl(selfNodeId, cluster, snapshotSource, mountMode);
    }
}

class HttpRoutePublisherImpl implements HttpRoutePublisher {
    private static final Logger log = LoggerFactory.getLogger(HttpRoutePublisherImpl.class);
    private static final TimeSpan CONSENSUS_OPERATION_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final int CONSENSUS_MAX_RETRIES = 2;

    private final NodeId selfNodeId;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final GenerationSnapshotSource snapshotSource;
    private final RouteMountMode mountMode;
    private final Map<Artifact, HttpRequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Artifact, SliceRouter> sliceRouters = new ConcurrentHashMap<>();
    private final Map<Artifact, List<HttpRouteDefinition>> publishedRoutes = new ConcurrentHashMap<>();
    private final Map<Artifact, List<ObservabilityStrategyCell>> routeCells = new ConcurrentHashMap<>();
    private final RouteMetadataExtractor routeMetadataExtractor = RouteMetadataExtractor.routeMetadataExtractor();

    private final AtomicReference<SecurityOverrides> activeOverrides = new AtomicReference<>(SecurityOverrides.EMPTY);

    private final AtomicReference<ObservabilityCellRegistrar> cellRegistrar = new AtomicReference<>(ObservabilityCellRegistrar.NOOP);

    private final AtomicReference<VersioningMetricsSink> versioningMetricsSink = new AtomicReference<>(VersioningMetricsSink.noop());

    private final VersioningMetricsSink forwardingSink = VersioningMetricsSink.forwarding(versioningMetricsSink::get);

    HttpRoutePublisherImpl(NodeId selfNodeId,
                           ClusterNode<KVCommand<AetherKey>> cluster,
                           GenerationSnapshotSource snapshotSource,
                           RouteMountMode mountMode) {
        this.selfNodeId = selfNodeId;
        this.cluster = cluster;
        this.snapshotSource = snapshotSource;
        this.mountMode = mountMode;
    }

    @Override
    public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade) {
        log.debug("publishRoutes(3-arg) called for artifact={}, classLoader={}",
                  artifact,
                  classLoader.getClass().getName());
        var factories = ServiceLoader.load(HttpRequestHandlerFactory.class, classLoader);
        var iterator = factories.iterator();

        if (!iterator.hasNext()) {
            log.debug("ServiceLoader: No HttpRequestHandlerFactory found for slice {}", artifact);

            return Promise.unitPromise();
        }

        var factory = iterator.next();

        log.debug("ServiceLoader: Found HttpRequestHandlerFactory for slice {}: {}",
                  artifact,
                  factory.getClass().getName());
        var handler = factory.create(invokerFacade);

        handlers.put(artifact, handler);
        var routes = handler.routes();

        log.debug("Route extraction: {} routes found for slice {}", routes.size(), artifact);
        if (routes.isEmpty()) {
            log.debug("No HTTP routes defined for slice {}, skipping publication", artifact);

            return Promise.unitPromise();
        }

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
                  sliceInstance.getClass().getName(),
                  classLoader.getClass().getName());
        var routerFactories = ServiceLoader.load(SliceRouterFactory.class, classLoader);
        int factoryCount = 0;

        for (var factory : routerFactories) {
            factoryCount++;
            log.debug("ServiceLoader: Checking SliceRouterFactory {} for slice type match with {}",
                      factory.getClass().getName(),
                      sliceInstance.getClass().getName());
            if (factory.sliceType().isInstance(sliceInstance)) {
                log.debug("ServiceLoader: SliceRouterFactory {} matches slice instance",
                          factory.getClass().getName());

                return publishViaSliceRouterFactory(artifact, factory, sliceInstance);
            }
        }

        log.debug("ServiceLoader: {} SliceRouterFactory(s) found, none matched. Falling back to HttpRequestHandlerFactory",
                  factoryCount);

        return publishRoutes(artifact, classLoader, invokerFacade);
    }

    @SuppressWarnings("unchecked")
    private Promise<Unit> publishViaSliceRouterFactory(Artifact artifact,
                                                       SliceRouterFactory<?> factory,
                                                       Object sliceInstance) {
        log.debug("publishViaSliceRouterFactory: artifact={}, factory={}",
                  artifact,
                  factory.getClass().getName());
        var typedFactory = (SliceRouterFactory<Object>) factory;
        var baseRouter = typedFactory.create(sliceInstance, JsonMapper.defaultJsonMapper(), mountMode);
        // #198 §11.1: bind the slice identity + lazy metrics sink so the router emits the versioned /
        // deprecated / missing-header counters at dispatch. The sink forwards to the live backend the
        // management server installs post-boot (forwardingSink), so deploy-time router creation needn't
        // wait on the metrics registry.
        // #277 increment 2: rewrap each route's handler once with its per-injection-point observability
        // cell (north-south seam). Cells are minted+registered here and dropped at unpublishRoutes.
        var cells = new ArrayList<ObservabilityStrategyCell>();
        var router = baseRouter.withObservability(sliceLabel(artifact),
                                                  forwardingSink)
                               .withInvocationCells(route -> decorateRoute(artifact, route, cells));

        routeCells.put(artifact, List.copyOf(cells));
        sliceRouters.put(artifact, router);
        if (factory instanceof RouteSource routeSource) {
            // #198 §7: compose the routes ONCE for this node's detection mode and feed the SAME
            // composed paths to the wire route-table extractor that the SliceRouter dispatches over,
            // so both consumers agree on the exposed paths (path mode `/v{N}/` or header mode bare).
            var composed = RouteMounting.compose(routeSource, mountMode);
            var routes = routeMetadataExtractor.extract(composed, artifact.asString());

            log.debug("Route extraction: {} routes found for slice {} via SliceRouterFactory", routes.size(), artifact);
            if (routes.isEmpty()) {
                log.debug("No HTTP routes defined for slice {}, skipping publication", artifact);

                return Promise.unitPromise();
            }

            publishedRoutes.put(artifact, routes);

            return publishRoutesToCluster(routes, artifact);
        }

        log.warn("SliceRouterFactory {} does not implement RouteSource, no routes published",
                 factory.getClass().getName());

        return Promise.unitPromise();
    }

    @Override
    public boolean hasRoutes(ClassLoader classLoader, Object sliceInstance) {
        var routerFactories = ServiceLoader.load(SliceRouterFactory.class, classLoader);

        for (var factory : routerFactories) {
            if (factory.sliceType().isInstance(sliceInstance) && factory instanceof RouteSource routeSource) {
                return ! routeMetadataExtractor.extract(routeSource, "")
                                               .isEmpty();
            }
        }

        var handlerFactories = ServiceLoader.load(HttpRequestHandlerFactory.class, classLoader);

        return handlerFactories.iterator()
                               .hasNext();
    }

    @Override
    public Unit updateSecurityOverrides(SecurityOverrides overrides) {
        activeOverrides.set(overrides);
        log.info("Updated security overrides: {} entries, policy={}",
                 overrides.entries().size(),
                 overrides.policy());

        return Unit.unit();
    }

    private Promise<Unit> publishRoutesToCluster(List<HttpRouteDefinition> routes, Artifact artifact) {
        var effectiveRoutes = SecurityOverrideApplier.applyOverrides(routes, activeOverrides.get());

        log.debug("Publishing {} HTTP routes for slice {}", effectiveRoutes.size(), artifact);
        var routeEntries = effectiveRoutes.stream().map(HttpRoutePublisherImpl::toRouteEntry).toList();
        var key = NodeRoutesKey.nodeRoutesKey(selfNodeId, artifact);
        var stampedEpoch = Epoch.epoch(snapshotSource.observedEpochRabiaTerm(), 0L);
        var value = NodeRoutesValue.nodeRoutesValue(routeEntries, stampedEpoch);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);

        return applyWithRetry(List.of(command), 0).onSuccess(_ -> log.debug("Published {} HTTP routes for slice {} stamped with epoch {}",
                                                                            routes.size(),
                                                                            artifact,
                                                                            stampedEpoch));
    }

    private static RouteEntry toRouteEntry(HttpRouteDefinition route) {
        return RouteEntry.activeRoute(route.httpMethod(),
                                      route.pathPrefix(),
                                      route.sliceMethod(),
                                      route.security().asString());
    }

    @Override
    public Promise<Unit> unpublishRoutes(Artifact artifact) {
        handlers.remove(artifact);
        sliceRouters.remove(artifact);
        deregisterRouteCells(artifact);
        var routes = publishedRoutes.remove(artifact);

        if (routes == null || routes.isEmpty()) {
            return Promise.unitPromise();
        }

        return unpublishRoutesFromCluster(artifact, routes);
    }

    private Promise<Unit> unpublishRoutesFromCluster(Artifact artifact, List<HttpRouteDefinition> routes) {
        KVCommand<AetherKey> command = new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(selfNodeId, artifact));

        return applyWithRetry(List.of(command),
                              0).onSuccess(_ -> log.debug("Unpublished {} HTTP routes for {}",
                                                          routes.size(),
                                                          artifact))
                             .onFailure(cause -> log.error("Failed to unpublish HTTP routes for {}: {}",
                                                           artifact,
                                                           cause.message()));
    }

    private Promise<Unit> applyWithRetry(List<KVCommand<AetherKey>> commands, int attempt) {
        return cluster.apply(commands)
                      .timeout(CONSENSUS_OPERATION_TIMEOUT)
                      .mapToUnit()
                      .orElse(() -> retryApply(commands, attempt));
    }

    private Promise<Unit> retryApply(List<KVCommand<AetherKey>> commands, int attempt) {
        if (attempt >= CONSENSUS_MAX_RETRIES) {
            log.warn("Route consensus apply failed after {} retries ({} commands)",
                     CONSENSUS_MAX_RETRIES,
                     commands.size());

            return Causes.cause("Route consensus apply timed out after " + CONSENSUS_MAX_RETRIES + " retries").promise();
        }

        log.debug("Retrying route consensus apply ({} commands, attempt {}/{})",
                  commands.size(),
                  attempt + 1,
                  CONSENSUS_MAX_RETRIES);

        return applyWithRetry(commands, attempt + 1);
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
    public Unit setVersioningMetricsSink(VersioningMetricsSink sink) {
        versioningMetricsSink.set(sink);

        return Unit.unit();
    }

    @Override
    public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
        cellRegistrar.set(registrar);

        return Unit.unit();
    }

    /// Mint + register the route's per-injection-point cell (keyed `artifactBase/route.name()`, falling
    /// back to the path for un-`.named()` routes) and rewrap its handler over `cell.around(...)`. The
    /// minted cell is collected so unpublishRoutes can deregister it.
    private Route<?> decorateRoute(Artifact artifact, Route<?> route, List<ObservabilityStrategyCell> collected) {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(artifact.base().asString(),
                                                                       routeCellKey(route));

        cellRegistrar.get().register(cell);
        collected.add(cell);

        return wrapHandler(cell, route);
    }

    private static String routeCellKey(Route<?> route) {
        return route.name()
                    .isEmpty()
               ? stripTrailingSlash(route.path())
               : route.name();
    }

    /// The path-fallback cell key is operator-facing: normalize away the router's trailing slash
    /// (`/two/` → `/two`) so the KV key an operator addresses matches the path as authored. Root `/`
    /// stays as-is.
    private static String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/")
               ? path.substring(0, path.length() - 1)
               : path;
    }

    private static <T> Route<T> wrapHandler(ObservabilityStrategyCell cell, Route<T> route) {
        var original = route.handler();
        Handler<T> wrapped = ctx -> cell.around(() -> original.handle(ctx));

        return Route.route(route.method(),
                           route.path(),
                           wrapped,
                           route.contentType(),
                           route.spacers(),
                           route.name(),
                           route.security(),
                           route.version(),
                           route.pathParamCount());
    }

    private void deregisterRouteCells(Artifact artifact) {
        Option.option(routeCells.remove(artifact)).onPresent(cells -> cells.forEach(cell -> cellRegistrar.get()
                                                                                                         .deregister(cell)));
    }

    @Override
    public Map<Artifact, SliceVersionRegistry> versionRegistries() {
        var registries = new LinkedHashMap<Artifact, SliceVersionRegistry>();

        sliceRouters.forEach((artifact, router) -> collectVersioned(registries, artifact, router));

        return Map.copyOf(registries);
    }

    private static void collectVersioned(Map<Artifact, SliceVersionRegistry> registries,
                                         Artifact artifact,
                                         SliceRouter router) {
        var registry = router.versionRegistry();

        if (registry.isVersioned()) {
            registries.put(artifact, registry);
        }
    }

    private static String sliceLabel(Artifact artifact) {
        return artifact.artifactId()
                       .toString();
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
        for (var entry : publishedRoutes.entrySet()) {
            var artifact = entry.getKey();
            var routes = entry.getValue();

            for (var route : routes) {
                if (route.httpMethod().equalsIgnoreCase(httpMethod) && route.pathPrefix().equals(pathPrefix)) {
                    return Option.option(sliceRouters.get(artifact));
                }
            }
        }

        return Option.none();
    }

    @Override
    public Option<LocalRouteInfo> findLocalRoute(String httpMethod, String path) {
        var normalizedPath = normalizePath(path);

        for (var routes : publishedRoutes.values()) {
            for (var route : routes) {
                if (route.httpMethod().equalsIgnoreCase(httpMethod) && normalizedPath.startsWith(route.pathPrefix())) {
                    return Option.some(LocalRouteInfo.localRouteInfo(route));
                }
            }
        }

        return Option.none();
    }

    private String normalizePath(String path) {
        if (!Verify.Is.present(path)) {
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
