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
import static org.pragmatica.aether.slice.delegation.TaskGroup.DEPLOYMENT;
import static org.pragmatica.aether.slice.delegation.TaskGroup.SCALING;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STORAGE;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STRATEGIES;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STREAMING;
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
    HEALTH_LIVE(GET, "/health/live", List.of(), LOCAL),
    HEALTH_READY(GET, "/health/ready", List.of(), LOCAL),
    CLUSTER_STATUS(GET, "/api/status", List.of(), ANY),
    NODES_LIST(GET, "/api/nodes", List.of(), ANY),
    CLUSTER_HEALTH(GET, "/api/health", List.of(), ANY),
    EVENTS(GET, "/api/events", List.of(), ANY),
    CERTIFICATE(GET, "/api/certificate", List.of(), ANY),
    CLUSTER_TOPOLOGY(GET, "/api/cluster/topology", List.of(), ANY),
    CLUSTER_GOVERNORS(GET, "/api/cluster/governors", List.of(), ANY),
    CLUSTER_TASKS_LIST(GET, "/api/cluster/tasks", List.of(), ANY),
    CLUSTER_CONFIG_GET(GET, "/api/cluster/config", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_CONFIG_STATUS(GET, "/api/cluster/status", List.of(), ANY),
    CLUSTER_CONFIG_APPLY(POST, "/api/cluster/config", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_SCALE(POST, "/api/cluster/scale", List.of(), taskGroup(SCALING)),
    CLUSTER_UPGRADE(POST, "/api/cluster/upgrade", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_TASK_REASSIGN(PUT, "/api/cluster/tasks/reassign", List.of("group"), taskGroup(DEPLOYMENT)),
    DEPLOY_START(POST, "/api/deploy", List.of(), taskGroup(STRATEGIES)),
    DEPLOY_LIST(GET, "/api/deploy", List.of(), taskGroup(STRATEGIES)),
    DEPLOY_STATUS(GET, "/api/deploy", List.of("deploymentId"), taskGroup(STRATEGIES)),
    DEPLOY_PROMOTE(POST, "/api/deploy/promote", List.of("deploymentId"), taskGroup(STRATEGIES)),
    DEPLOY_ROLLBACK(POST, "/api/deploy/rollback", List.of("deploymentId"), taskGroup(STRATEGIES)),
    DEPLOY_COMPLETE(POST, "/api/deploy/complete", List.of("deploymentId"), taskGroup(STRATEGIES)),
    AB_TEST_LIST(GET, "/api/ab-tests", List.of(), taskGroup(STRATEGIES)),
    AB_TEST_GET(GET, "/api/ab-test", List.of("testId"), taskGroup(STRATEGIES)),
    AB_TEST_METRICS(GET, "/api/ab-test/metrics", List.of("testId"), taskGroup(STRATEGIES)),
    AB_TEST_CREATE(POST, "/api/ab-test/create", List.of(), taskGroup(STRATEGIES)),
    AB_TEST_CONCLUDE(POST, "/api/ab-test/conclude", List.of("testId"), taskGroup(STRATEGIES)),
    BLUEPRINT_LIST(GET, "/api/blueprints", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_PUBLISH_BODY(POST, "/api/blueprint", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_GET(GET, "/api/blueprint", List.of("blueprintId"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_STATUS(GET, "/api/blueprint/status", List.of("blueprintId"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_DELETE(DELETE, "/api/blueprint", List.of("blueprintId"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_DEPLOY(POST, "/api/blueprint/deploy", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_PUBLISH_ARTIFACT(POST, "/api/blueprint/publish", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_VALIDATE(POST, "/api/blueprint/validate", List.of(), taskGroup(DEPLOYMENT)),
    SLICES_LIST(GET, "/api/slices", List.of(), ANY),
    SLICES_STATUS(GET, "/api/slices/status", List.of(), ANY),
    NODE_SLICES(GET, "/api/node/slices", List.of(), ANY),
    NODE_ROUTES(GET, "/api/node/routes", List.of(), ANY),
    ROUTES_LIST(GET, "/api/routes", List.of(), ANY),
    TOPOLOGY(GET, "/api/topology", List.of(), ANY),
    SLICE_SCALE(POST, "/api/scale", List.of(), taskGroup(SCALING)),
    WORKERS_LIST(GET, "/api/workers", List.of(), ANY),
    WORKERS_HEALTH(GET, "/api/workers/health", List.of(), ANY),
    WORKERS_ENDPOINTS(GET, "/api/workers/endpoints", List.of(), ANY),
    CLUSTER_MIGRATE(POST, "/api/cluster/migrate", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_MIGRATE_PLAN(POST, "/api/cluster/migrate/plan", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_API_KEY_SET(POST, "/api/cluster/api-key", List.of(), taskGroup(DEPLOYMENT)),
    REPOSITORY_ARTIFACTS_LIST(GET, "/repository/artifacts", List.of(), taskGroup(DEPLOYMENT)),
    MAVEN_METADATA(GET, "/repository", List.of("groupPath", "artifactId", "file"), taskGroup(DEPLOYMENT)),
    NODE_LIFECYCLE_LIST(GET, "/api/nodes/lifecycle", List.of(), ANY),
    NODE_LIFECYCLE_GET(GET, "/api/node/lifecycle", List.of("nodeId"), ANY),
    NODE_DRAIN(POST, "/api/node/drain", List.of("nodeId"), taskGroup(DEPLOYMENT)),
    NODE_ACTIVATE(POST, "/api/node/activate", List.of("nodeId"), taskGroup(DEPLOYMENT)),
    NODE_SHUTDOWN(POST, "/api/node/shutdown", List.of("nodeId"), taskGroup(DEPLOYMENT)),
    SCHEMA_STATUS_ALL(GET, "/api/schema/status", List.of(), taskGroup(DEPLOYMENT)),
    SCHEMA_STATUS_ONE(GET, "/api/schema/status", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_HISTORY(GET, "/api/schema/history", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_MIGRATE(POST, "/api/schema/migrate", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_UNDO(POST, "/api/schema/undo", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_BASELINE(POST, "/api/schema/baseline", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_RETRY(POST, "/api/schema/retry", List.of("datasource"), taskGroup(DEPLOYMENT)),
    STORAGE_LIST(GET, "/api/storage", List.of(), ANY),
    STORAGE_GET(GET, "/api/storage", List.of("name"), ANY),
    STORAGE_SNAPSHOT(POST, "/api/storage/snapshot", List.of("name"), taskGroup(STORAGE)),
    CLUSTER_STORAGE_LIST(GET, "/api/cluster/storage", List.of(), ANY),
    CLUSTER_STORAGE_GET(GET, "/api/cluster/storage", List.of("name"), ANY),
    STREAM_CREATE(POST, "/api/streams", List.of(), taskGroup(STREAMING)),
    STREAM_LIST(GET, "/api/streams", List.of(), taskGroup(STREAMING)),
    STREAM_GET(GET, "/api/streams", List.of("streamName"), taskGroup(STREAMING)),
    STREAM_PARTITION(GET, "/api/streams", List.of("streamName", "partition"), taskGroup(STREAMING)),
    STREAM_PUBLISH(POST, "/api/streams/publish", List.of("streamName"), taskGroup(STREAMING)),
    STREAM_DELETE(DELETE, "/api/streams", List.of("streamName"), taskGroup(STREAMING)),
    STREAM_CONSUMERS(GET, "/api/streams/consumers", List.of("streamName"), taskGroup(STREAMING)),
    STREAM_READ(GET, "/api/streams/read", List.of("streamName", "partition"), taskGroup(STREAMING)),
    CONSUMER_GROUP_JOIN(POST, "/api/streams/groups/join", List.of(), taskGroup(STREAMING)),
    CONSUMER_GROUP_LEAVE(POST, "/api/streams/groups/leave", List.of(), taskGroup(STREAMING)),
    CONSUMER_GROUP_STATUS(GET, "/api/streams/groups", List.of("groupId"), taskGroup(STREAMING)),
    SCHEDULED_TASKS_LIST(GET, "/api/scheduled-tasks", List.of(), ANY),
    SCHEDULED_TASKS_BY_SECTION(GET, "/api/scheduled-tasks", List.of("section"), ANY),
    SCHEDULED_TASK_STATE(GET, "/api/scheduled-tasks/state", List.of("section", "artifact", "methodName"), ANY),
    SCHEDULED_TASK_PAUSE(POST,
                         "/api/scheduled-tasks/pause",
                         List.of("section", "artifact", "methodName"),
                         taskGroup(STRATEGIES)),
    SCHEDULED_TASK_RESUME(POST,
                          "/api/scheduled-tasks/resume",
                          List.of("section", "artifact", "methodName"),
                          taskGroup(STRATEGIES)),
    SCHEDULED_TASK_TRIGGER(POST,
                           "/api/scheduled-tasks/trigger",
                           List.of("section", "artifact", "methodName"),
                           taskGroup(STRATEGIES)),
    ARTIFACT_GET(GET, "/repository", List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_PUT(PUT, "/repository", List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_POST(POST, "/repository", List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_INFO(GET, "/repository/info", List.of("groupPath", "artifactId", "version"), taskGroup(DEPLOYMENT)),
    ARTIFACT_DELETE(DELETE, "/repository", List.of("groupPath", "artifactId", "version"), taskGroup(DEPLOYMENT)),
    METRICS(GET, "/api/metrics", List.of(), ANY),
    METRICS_COMPREHENSIVE(GET, "/api/metrics/comprehensive", List.of(), ANY),
    METRICS_DERIVED(GET, "/api/metrics/derived", List.of(), ANY),
    METRICS_PROMETHEUS(GET, "/api/metrics/prometheus", List.of(), ANY),
    METRICS_HISTORY(GET, "/api/metrics/history", List.of(), ANY),
    METRICS_TRANSPORT(GET, "/api/metrics/transport", List.of(), ANY),
    NODE_METRICS(GET, "/api/node-metrics", List.of(), ANY),
    ARTIFACT_METRICS(GET, "/api/artifact-metrics", List.of(), ANY),
    INVOCATION_METRICS(GET, "/api/invocation-metrics", List.of(), ANY),
    INVOCATION_METRICS_SLOW(GET, "/api/invocation-metrics/slow", List.of(), ANY),
    INVOCATION_METRICS_STRATEGY_GET(GET, "/api/invocation-metrics/strategy", List.of(), ANY),
    INVOCATION_METRICS_STRATEGY_SET(POST, "/api/invocation-metrics/strategy", List.of(), ANY),
    THRESHOLDS_LIST(GET, "/api/thresholds", List.of(), ANY),
    THRESHOLD_SET(POST, "/api/thresholds", List.of(), ANY),
    THRESHOLD_DELETE(DELETE, "/api/thresholds", List.of("metric"), ANY),
    ALERTS(GET, "/api/alerts", List.of(), ANY),
    ALERTS_ACTIVE(GET, "/api/alerts/active", List.of(), ANY),
    ALERTS_HISTORY(GET, "/api/alerts/history", List.of(), ANY),
    ALERTS_CLEAR(POST, "/api/alerts/clear", List.of(), ANY),
    BACKUP_TRIGGER(POST, "/api/backup", List.of(), taskGroup(DEPLOYMENT)),
    BACKUPS_LIST(GET, "/api/backups", List.of(), ANY),
    BACKUP_RESTORE(POST, "/api/backup/restore", List.of(), taskGroup(DEPLOYMENT)),
    CONFIG_LIST(GET, "/api/config", List.of(), ANY),
    CONFIG_OVERRIDES(GET, "/api/config/overrides", List.of(), ANY),
    CONFIG_SET(POST, "/api/config", List.of(), taskGroup(DEPLOYMENT)),
    CONFIG_DELETE(DELETE, "/api/config", List.of("key"), taskGroup(DEPLOYMENT)),
    CONFIG_NODE_DELETE(DELETE, "/api/config/node", List.of("nodeId", "key"), taskGroup(DEPLOYMENT)),
    CONTROLLER_CONFIG_GET(GET, "/api/controller/config", List.of(), ANY),
    CONTROLLER_STATUS(GET, "/api/controller/status", List.of(), ANY),
    CONTROLLER_CONFIG_SET(POST, "/api/controller/config", List.of(), ANY),
    CONTROLLER_EVALUATE(POST, "/api/controller/evaluate", List.of(), ANY),
    TTM_STATUS(GET, "/api/ttm/status", List.of(), ANY),
    TTM_TRAINING_DATA(GET, "/api/ttm/training-data", List.of(), ANY),
    LOG_LEVELS_LIST(GET, "/api/logging/levels", List.of(), ANY),
    LOG_LEVEL_SET(POST, "/api/logging/levels", List.of(), ANY),
    LOG_LEVEL_RESET(DELETE, "/api/logging/levels", List.of("logger"), ANY),
    TRACES_QUERY(GET, "/api/traces", List.of(), ANY),
    TRACES_STATS(GET, "/api/traces/stats", List.of(), ANY),
    TRACE_BY_REQUEST_ID(GET, "/api/traces", List.of("requestId"), ANY),
    OBSERVABILITY_DEPTH_GET(GET, "/api/observability/depth", List.of(), ANY),
    OBSERVABILITY_DEPTH_SET(POST, "/api/observability/depth", List.of(), ANY),
    OBSERVABILITY_DEPTH_DELETE(DELETE, "/api/observability/depth", List.of("artifact", "methodName"), ANY);
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
