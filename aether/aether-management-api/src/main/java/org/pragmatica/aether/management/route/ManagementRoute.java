/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.management.route;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.lang.Result;

import java.util.Arrays;
import java.util.List;

import static org.pragmatica.aether.management.route.RouteTarget.ANY;
import static org.pragmatica.aether.management.route.RouteTarget.LOCAL;
import static org.pragmatica.aether.management.route.RouteTarget.taskGroup;
import static org.pragmatica.http.routing.HttpMethod.DELETE;
import static org.pragmatica.http.routing.HttpMethod.GET;
import static org.pragmatica.http.routing.HttpMethod.POST;
import static org.pragmatica.http.routing.HttpMethod.PUT;


/// Compile-time registry of all Aether management API routes.
///
/// Each enum value carries its HTTP method, static path prefix, parameter names (in order),
/// and forwarding [RouteTarget]. This registry is the single source of truth for every
/// consumer of the management API: the server-side route bindings, the load balancer's
/// forwarder, the CLI, and tests.
///
/// ## Path layout invariant
///
/// Every route has the shape `<static prefix>/<param1>/<param2>/.../<paramN>` — no literal
/// path segments interleave between or after parameters. This makes matching trivial
/// (split by `/`, look up by `(method, prefix, paramCount)`) and assembly symmetric
/// (append URL-encoded values to the prefix).
///
/// ## Ambiguity
///
/// No two enum values may share `(method, prefix, paramCount)`. Violation is detected at
/// class-loading time via [RouteMatcher] static-init check, throwing
/// [ExceptionInInitializerError].
public enum ManagementRoute {
    //
    // Health — handled locally on the receiving node (LB or core)
    //
    HEALTH_LIVE             (GET,    "/health/live",                List.of(),                                         LOCAL),
    HEALTH_READY            (GET,    "/health/ready",               List.of(),                                         LOCAL),

    //
    // Cluster status (read-only, any core node)
    //
    CLUSTER_STATUS          (GET,    "/api/status",                 List.of(),                                         ANY),
    NODES_LIST              (GET,    "/api/nodes",                  List.of(),                                         ANY),
    CLUSTER_TOPOLOGY        (GET,    "/api/cluster/topology",       List.of(),                                         ANY),
    CLUSTER_HEALTH          (GET,    "/api/health",                 List.of(),                                         ANY),
    EVENTS                  (GET,    "/api/events",                 List.of(),                                         ANY),
    CERTIFICATE             (GET,    "/api/certificate",            List.of(),                                         ANY),

    //
    // Deployment strategies (STRATEGIES task group)
    //
    DEPLOY_START            (POST,   "/api/deploy",                 List.of(),                                         taskGroup(TaskGroup.STRATEGIES)),
    DEPLOY_LIST             (GET,    "/api/deploy",                 List.of(),                                         taskGroup(TaskGroup.STRATEGIES)),
    DEPLOY_STATUS           (GET,    "/api/deploy",                 List.of("deploymentId"),                           taskGroup(TaskGroup.STRATEGIES)),
    DEPLOY_PROMOTE          (POST,   "/api/deploy/promote",         List.of("deploymentId"),                           taskGroup(TaskGroup.STRATEGIES)),
    DEPLOY_ROLLBACK         (POST,   "/api/deploy/rollback",        List.of("deploymentId"),                           taskGroup(TaskGroup.STRATEGIES)),
    DEPLOY_COMPLETE         (POST,   "/api/deploy/complete",        List.of("deploymentId"),                           taskGroup(TaskGroup.STRATEGIES)),

    //
    // Cluster deployment manager (DEPLOYMENT task group)
    //
    BLUEPRINT_DEPLOY        (POST,   "/api/blueprint/deploy",       List.of(),                                         taskGroup(TaskGroup.DEPLOYMENT)),
    CLUSTER_CONFIG_GET      (GET,    "/api/cluster/config",         List.of(),                                         taskGroup(TaskGroup.DEPLOYMENT)),
    CLUSTER_CONFIG_APPLY    (POST,   "/api/cluster/config",         List.of(),                                         taskGroup(TaskGroup.DEPLOYMENT)),

    //
    // Scaling (SCALING task group)
    //
    CLUSTER_SCALE           (POST,   "/api/cluster/scale",          List.of(),                                         taskGroup(TaskGroup.SCALING)),

    ;

    private final HttpMethod method;
    private final String prefix;
    private final List<String> paramNames;
    private final RouteTarget target;

    ManagementRoute(HttpMethod method, String prefix, List<String> paramNames, RouteTarget target) {
        this.method = method;
        this.prefix = prefix;
        this.paramNames = List.copyOf(paramNames);
        this.target = target;
    }

    public HttpMethod method() {
        return method;
    }

    public String prefix() {
        return prefix;
    }

    public List<String> paramNames() {
        return paramNames;
    }

    public int paramCount() {
        return paramNames.size();
    }

    public RouteTarget target() {
        return target;
    }

    public Result<String> assemble(List<String> values) {
        return RouteAssembler.assemble(this, values);
    }

    public Result<String> assemble(String... values) {
        return assemble(Arrays.asList(values));
    }

    public static Result<MatchedRoute> match(HttpMethod method, String path) {
        return RouteMatcher.shared().match(method, path);
    }
}
