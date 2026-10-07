// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.adapter.impl.SliceRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.security.Access;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraApplication;


/// Dispatch ownership is chosen with the shared shape matcher; authentication wraps the actual
/// version-selected handler, so it can never authorize a different version or sibling route.
record TerraHttpRoutes(RequestRouter index, Map<Route<?>, SliceRouter> owners) {
    static Result<List<SliceRouterFactory<?>>> discover() {
        return Result.lift(Causes::fromThrowable,
                           () -> ServiceLoader.load(SliceRouterFactory.class)
                                              .stream()
                                              .<SliceRouterFactory<?>> map(ServiceLoader.Provider::get)
                                              .toList());
    }

    static Result<TerraHttpRoutes> terraHttpRoutes(TerraApplication application,
                                                   List<SliceRouterFactory<?>> factories,
                                                   TerraHttpConfig config,
                                                   HttpAuthenticator authenticator,
                                                   JsonMapper mapper) {
        var routes = new ArrayList<Route<?>>();
        var owners = new IdentityHashMap<Route<?>, SliceRouter>();
        var types = new java.util.HashSet<Class<?>>();

        for (var factory : factories) {
            if (application.slice(factory.sliceType()).isFailure()) {
                continue;
            }

            if (!types.add(factory.sliceType()) || factory.routeSecurityContract() != SliceRouterFactory.ROUTE_SECURITY_CONTRACT) {
                return new TerraHttpError.InvalidRoutes("Duplicate or incompatible HTTP factory: " + factory.sliceType()
                                                                                                            .getName()).result();
            }

            var bound = bind(application, factory, config, authenticator, mapper);

            if (bound instanceof Result.Failure<Bound>(var cause)) {
                return cause.result();
            }

            var value = bound.unwrap();

            routes.addAll(value.routes());
            value.routes().forEach(route -> owners.put(route, value.router()));
        }

        return checkRoutes(routes, owners).map(_ -> routingIndex(routes,
                                                                 owners,
                                                                 config.mountMode().isHeaderMode()));
    }

    // Header-mode ownership must not apply a shape from another version before the slice's
    // VersionSelector runs. Base paths select an owner; its per-version routers select the handler.
    private static TerraHttpRoutes routingIndex(List<Route<?>> routes,
                                                Map<Route<?>, SliceRouter> owners,
                                                boolean headerMode) {
        if (!headerMode) {
            return new TerraHttpRoutes(RequestRouter.with(() -> routes.stream()),
                                       owners);
        }

        var indexOwners = new IdentityHashMap<Route<?>, SliceRouter>();
        var indexRoutes = new ArrayList<Route<?>>();

        for (var route : routes) {
            var indexRoute = Route.route(route.method(), route.path(), route.handler(), route.contentType());

            indexRoutes.add(indexRoute);
            indexOwners.put(indexRoute, owners.get(route));
        }

        return new TerraHttpRoutes(RequestRouter.with(() -> indexRoutes.stream()),
                                   indexOwners);
    }

    private static Result<org.pragmatica.lang.Unit> checkRoutes(List<Route<?>> routes,
                                                                Map<Route<?>, SliceRouter> owners) {
        var identities = new java.util.HashSet<String>();
        var bases = new HashMap<String, SliceRouter>();

        for (var route : routes) {
            var base = route.method() + " " + route.path();
            var previous = bases.putIfAbsent(base, owners.get(route));

            if (previous != null && previous != owners.get(route)) {
                return new TerraHttpError.InvalidRoutes("Multiple slices own route base: " + base).result();
            }

            var identity = base
                         + " " + route.version()
                         + " " + route.pathParamCount()
                         + " " + route.spacers()
                         + " " + route.spacerSlots();

            if (!identities.add(identity)) {
                return new TerraHttpError.InvalidRoutes("Duplicate route: " + identity).result();
            }

            if (route.path().startsWith("/__terra/") || "/__terra/".startsWith(route.path())) {
                return new TerraHttpError.InvalidRoutes("Route overlaps reserved /__terra/ namespace: " + route.path()).result();
            }

            if (! (route.security() instanceof SecurityPolicy) || route.security() instanceof SecurityPolicy.unused) {
                return new TerraHttpError.InvalidRoutes("Unsupported route security: " + base).result();
            }
        }

        return Result.unitResult();
    }

    private record Bound(SliceRouter router, List<Route<?>> routes) {}

    private static <T> Result<Bound> bind(TerraApplication app,
                                          SliceRouterFactory<T> factory,
                                          TerraHttpConfig config,
                                          HttpAuthenticator authenticator,
                                          JsonMapper mapper) {
        return app.slice(factory.sliceType())
                  .flatMap(slice -> Result.lift(Causes::fromThrowable,
                                                () -> {
                                                    var routes = new ArrayList<Route<?>>();
                                                    var router = factory.create(slice,
                                                                                mapper,
                                                                                config.mountMode())
                                                                        .withInvocationCells(route -> {
                                                                                                 routes.add(route);

                                                                                                 return secure(route,
                                                                                                               config.defaultPolicy(),
                                                                                                               authenticator,
                                                                                                               mapper);
                                                                                             });

                                                    return new Bound(router,
                                                                     List.copyOf(routes));
                                                }));
    }

    private static <T> Route<T> secure(Route<T> route,
                                       SecurityPolicy defaultPolicy,
                                       HttpAuthenticator authenticator,
                                       JsonMapper mapper) {
        return Route.route(route.method(),
                           route.path(),
                           context -> authenticate(route, context, defaultPolicy, authenticator).flatMap(security -> invoke(route,
                                                                                                                            context,
                                                                                                                            security,
                                                                                                                            mapper)),
                           route.contentType(),
                           route.spacers(),
                           route.name(),
                           route.security(),
                           route.version(),
                           route.pathParamCount(),
                           route.spacerSlots());
    }

    private static Promise<SecurityContext> authenticate(Route<?> route,
                                                         RequestContext context,
                                                         SecurityPolicy defaultPolicy,
                                                         HttpAuthenticator authenticator) {
        var policy = route.security() instanceof SecurityPolicy.Unspecified
                     ? defaultPolicy
                     : (SecurityPolicy) route.security();
        var request = ((SliceRequestContext) context).original();

        return Promise.lift(Causes::fromThrowable,
                            () -> authenticator.validate(request, policy))
                      .flatMap(Result::async)
                      .mapError(cause -> cause instanceof HttpStatusAware status
                                         ? status.httpStatus()
                                                 .with(cause)
                                         : HttpStatus.INTERNAL_SERVER_ERROR.with(cause))
                      .flatMap(security -> policy.canAccess(security) == Access.ALLOW
                                           ? Promise.success(security)
                                           : HttpStatus.FORBIDDEN.with("Route policy denied access").promise());
    }

    private static <T> Promise<T> invoke(Route<T> route,
                                         RequestContext context,
                                         SecurityContext security,
                                         JsonMapper mapper) {
        var request = ((SliceRequestContext) context).original().withSecurity(security);

        return Result.lift(Causes::fromThrowable,
                           () -> ScopedValue.where(SecurityContextHolder.scopedValue(),
                                                   security)
                                            .call(() -> Objects.requireNonNull(route.handler()
                                                                                    .handle(SliceRequestContext.sliceRequestContext(request,
                                                                                                                                    route,
                                                                                                                                    mapper)),
                                                                               "HTTP handler returned null Promise")))
                     .fold(Promise::failure, promise -> promise);
    }

    Promise<HttpResponseData> handle(HttpRequestContext request) {
        return HttpMethod.fromString(request.method())
                         .flatMap(method -> index.findRoute(method,
                                                            request.path()))
                         .flatMap(route -> Option.option(owners.get(route)))
                         .map(router -> router.handle(request))
                         .or(() -> Promise.success(HttpResponseData.httpResponseData(404, "No route found")));
    }
}
