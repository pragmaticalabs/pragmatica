// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.management.route.RouteTarget.ANY;
import static org.pragmatica.aether.management.route.RouteTarget.LEADER;
import static org.pragmatica.aether.management.route.RouteTarget.LOCAL;
import static org.pragmatica.aether.management.route.RouteTarget.taskGroup;
import static org.pragmatica.aether.slice.delegation.TaskGroup.DEPLOYMENT;
import static org.pragmatica.aether.slice.delegation.TaskGroup.SCALING;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STORAGE;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STRATEGIES;
import static org.pragmatica.aether.slice.delegation.TaskGroup.STREAMING;
import static org.pragmatica.http.HttpMethod.DELETE;
import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.HttpMethod.POST;
import static org.pragmatica.http.HttpMethod.PUT;


public enum ManagementRoute {
    HEALTH_LIVE(GET, "/health/live", List.of(), LOCAL),
    HEALTH_LIVE_GET(GET, "/health/live", List.of("id"), RouteTarget.nodeIdParam(0)),
    HEALTH_READY(GET, "/health/ready", List.of(), LOCAL),
    HEALTH_READY_GET(GET, "/health/ready", List.of("id"), RouteTarget.nodeIdParam(0)),
    NODE_STATUS(GET, "/api/nodes/status", List.of(), LEADER),
    NODE_STATUS_GET(GET, "/api/nodes/status", List.of("id"), RouteTarget.nodeIdParam(0)),
    NODE_ENDPOINT_GET(GET, "/api/nodes/endpoint", List.of("id"), RouteTarget.nodeIdParam(0)),
    NODES_LIVE(GET, "/api/nodes/live", List.of(), RouteTarget.ANY),
    NODES_LIST(GET, "/api/nodes", List.of(), LEADER),
    WHOAMI(GET, "/api/whoami", List.of(), LOCAL),
    CLUSTER_HEALTH(GET, "/api/health", List.of(), LEADER),
    // #267: served from ANY core node, not leader-bound. cluster-events is a replicated single-partition
    // stream; a non-replica core node read-forwards to a CAUGHT_UP replica (forward-capable consumer),
    // so `/api/events` stays available during leader churn/election instead of returning 503 — exactly
    // when operators most need events. Replica-read correctness rests on #260 (offset verification) and
    // #261 (real backfill). Staleness: a forwarded read reflects the replica's CAUGHT_UP watermark,
    // which may trail the owner by the in-flight replication window (sub-second under steady load).
    EVENTS(GET, "/api/events", List.of(), ANY),
    CERTIFICATES_LIST(GET, "/api/certificates", List.of(), LOCAL),
    CLUSTER_TOPOLOGY(GET, "/api/cluster/topology", List.of(), LEADER),
    CLUSTER_GENERATION(GET, "/api/cluster/generation", List.of(), LEADER),
    CLUSTER_AWAIT_QUIESCED(POST, "/api/cluster/await-quiesced", List.of(), LEADER),
    CLUSTER_GOVERNORS(GET, "/api/cluster/governors", List.of(), LEADER),
    CLUSTER_JOURNAL(GET, "/api/cluster/journal", List.of(), LOCAL),
    CLUSTER_CONFIG_GET(GET, "/api/cluster/config", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_PROVISIONING_GET(GET, "/api/cluster/provisioning", List.of(), taskGroup(DEPLOYMENT)),
    // PER-NODE local view (LOCAL, never leader/owner-forwarded): each survivor answers from its OWN
    // MembershipFsm + QuorumLossDetector, so an operator can query a specific node and see THAT
    // node's per-peer SUSPECT/DEAD states + self-drain armed/below-quorum signal. taskGroup/LEADER
    // would forward and collapse the per-node distinction this endpoint exists to expose.
    CLUSTER_MEMBERSHIP_GET(GET, "/api/cluster/membership", List.of(), LOCAL),
    // #345 item 1f: per-node committed ownership/fence view. `domain` (community|dht|stream) is a path
    // param; LOCAL (never leader/owner-forwarded) like membership — each node answers from its OWN
    // committed KV-Store, so an operator can read the owner NodeId + fence Epoch per partition/key that
    // THIS node has applied. The committed ownership atoms are Rabia-replicated, so a LOCAL read off any
    // caught-up node reflects the fenced owner without forwarding.
    CLUSTER_OWNERSHIP_GET(GET, "/api/ownership", List.of("domain"), LOCAL),
    CLUSTER_STATUS(GET, "/api/cluster/status", List.of(), LEADER),
    CLUSTER_CONFIG_APPLY(POST, "/api/cluster/config", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_SCALE(POST, "/api/cluster/scale", List.of(), LEADER),
    CLUSTER_CIRCUIT_BREAKER_STATUS(GET, "/api/cluster/topology/circuit-breaker", List.of(), LEADER),
    CLUSTER_CIRCUIT_BREAKER_RESET(POST, "/api/cluster/topology/circuit-breaker/reset", List.of(), LEADER),
    CLUSTER_AUTO_HEAL_STATUS(GET, "/api/cluster/topology/auto-heal", List.of(), LEADER),
    CLUSTER_AUTO_HEAL_ENABLE(POST, "/api/cluster/topology/auto-heal/enable", List.of(), LEADER),
    CLUSTER_AUTO_HEAL_DISABLE(POST, "/api/cluster/topology/auto-heal/disable", List.of(), LEADER),
    CLUSTER_UPGRADE(POST, "/api/cluster/upgrade", List.of(), taskGroup(DEPLOYMENT)),
    DEPLOY_START(POST, "/api/deploy", List.of(), taskGroup(STRATEGIES)),
    DEPLOY_LIST(GET, "/api/deploy", List.of(), taskGroup(STRATEGIES)),
    DEPLOY_STATUS(GET, "/api/deploy", List.of("id"), taskGroup(STRATEGIES)),
    DEPLOY_PROMOTE(POST, "/api/deploy/promote", List.of("id"), taskGroup(STRATEGIES)),
    DEPLOY_ROLLBACK(POST, "/api/deploy/rollback", List.of("id"), taskGroup(STRATEGIES)),
    DEPLOY_COMPLETE(POST, "/api/deploy/complete", List.of("id"), taskGroup(STRATEGIES)),
    AB_TEST_LIST(GET, "/api/ab-tests", List.of(), taskGroup(STRATEGIES)),
    AB_TEST_GET(GET, "/api/ab-tests", List.of("id"), taskGroup(STRATEGIES)),
    AB_TEST_METRICS(GET, "/api/ab-tests/metrics", List.of("id"), taskGroup(STRATEGIES)),
    AB_TEST_CREATE(POST, "/api/ab-tests/create", List.of(), taskGroup(STRATEGIES)),
    AB_TEST_CONCLUDE(POST, "/api/ab-tests/conclude", List.of("id"), taskGroup(STRATEGIES)),
    BLUEPRINT_LIST(GET, "/api/blueprints", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_PUBLISH_BODY(POST, "/api/blueprints", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_GET(GET, "/api/blueprints", List.of("id"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_STATUS(GET, "/api/blueprints/status", List.of("id"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_DELETE(DELETE, "/api/blueprints", List.of("id"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_DEPLOY(POST, "/api/blueprints/deploy", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_PUBLISH_ARTIFACT(POST, "/api/blueprints/publish", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_VALIDATE(POST, "/api/blueprints/validate", List.of(), taskGroup(DEPLOYMENT)),
    SLICES_LIST(GET, "/api/slices", List.of(), LEADER),
    SLICES_STATUS(GET, "/api/slices/status", List.of(), LEADER),
    SLICE_TOPOLOGY(GET, "/api/slices/topology", List.of(), LEADER),
    SLICE_CONFIG(GET, "/api/slices/config", List.of("id"), LEADER),
    NODE_SLICES(GET, "/api/nodes/slices", List.of(), LEADER),
    NODE_SLICES_GET(GET, "/api/nodes/slices", List.of("id"), RouteTarget.nodeIdParam(0)),
    NODE_ROUTES(GET, "/api/nodes/routes", List.of(), LEADER),
    NODE_ROUTES_GET(GET, "/api/nodes/routes", List.of("id"), RouteTarget.nodeIdParam(0)),
    ROUTES_LIST(GET, "/api/routes", List.of(), LEADER),
    // PER-NODE local view (#198 §11.3): each node lists the versioned slices IT has deployed, read
    // from its own HttpRoutePublisher registry. LOCAL (not LEADER/forwarded) so an operator can query
    // a specific node and see that node's served version metadata + deprecation/sunset knobs.
    VERSIONS(GET, "/api/versions", List.of(), LOCAL),
    SLICE_SCALE(POST, "/api/scale", List.of(), taskGroup(SCALING)),
    WORKERS_LIST(GET, "/api/workers", List.of(), LEADER),
    // #525: declared but not built. Per-worker health and per-worker endpoints are never published
    // to consensus — the only worker facts that reach the KV-Store are the community roster and the
    // GOVERNOR's tcpAddress (GovernorAnnouncementValue), which WORKERS_LIST already serves. Both are
    // answered by NotImplementedRoutes with an honest 501 naming the missing capability, rather than
    // being left unserved (a bare 404 tells the operator nothing) or deleted (worker mode is live —
    // see AetherNode.activateWorkerMode). Implementing them requires workers to publish health and
    // endpoint facts; that is a feature, not a repair.
    WORKERS_HEALTH(GET, "/api/workers/health", List.of(), LEADER),
    WORKERS_ENDPOINTS(GET, "/api/workers/endpoints", List.of(), LEADER),
    // #525: declared but not built — cloud migration has no server-side implementation at all.
    // Answered by NotImplementedRoutes with an honest 501. These two hid from the #525 sweep because
    // they ARE referenced under aether/node/src/main — but only in the ManagementRoutePermissions
    // table, which grants authorization for a handler that does not exist. Permission-table presence
    // is not service; ManagementRouteCoverageTest keys on handler registration for exactly this reason.
    CLUSTER_MIGRATE(POST, "/api/cluster/migrate", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_MIGRATE_PLAN(POST, "/api/cluster/migrate/plan", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_CREATE(POST, "/api/cluster/keys", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_LIST(GET, "/api/cluster/keys", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_REVOKE(POST, "/api/cluster/keys/revoke", List.of("id"), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_AUDIT(GET, "/api/cluster/keys/audit", List.of(), taskGroup(DEPLOYMENT)),
    REPOSITORY_ARTIFACTS_LIST(GET, "/repository/artifacts", List.of(), taskGroup(DEPLOYMENT)),
    MAVEN_METADATA(GET, "/repository", List.of("groupPath", "artifactId", "file"), taskGroup(DEPLOYMENT)),
    NODE_LIFECYCLE_LIST(GET, "/api/nodes/lifecycle", List.of(), LEADER),
    NODE_LIFECYCLE_GET(GET, "/api/nodes/lifecycle", List.of("id"), LEADER),
    NODE_DRAIN(POST, "/api/nodes/drain", List.of("id"), taskGroup(DEPLOYMENT)),
    NODE_SHUTDOWN(POST, "/api/nodes/shutdown", List.of("id"), taskGroup(DEPLOYMENT)),
    NODE_PROMOTE(POST, "/api/nodes/promote", List.of("id"), LEADER),
    NODE_INFLIGHT(GET, "/api/nodes/inflight", List.of(), LOCAL),
    NODE_INFLIGHT_GET(GET, "/api/nodes/inflight", List.of("id"), RouteTarget.nodeIdParam(0)),
    SCHEMA_STATUS_ALL(GET, "/api/schema/status", List.of(), taskGroup(DEPLOYMENT)),
    SCHEMA_STATUS_ONE(GET, "/api/schema/status", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_HISTORY(GET, "/api/schema/history", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_MIGRATE(POST, "/api/schema/migrate", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_UNDO(POST, "/api/schema/undo", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_BASELINE(POST, "/api/schema/baseline", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_RETRY(POST, "/api/schema/retry", List.of("datasource"), taskGroup(DEPLOYMENT)),
    STORAGE_LIST(GET, "/api/storage", List.of(), LOCAL),
    STORAGE_GET(GET, "/api/storage", List.of("name"), LOCAL),
    STORAGE_SNAPSHOT(POST, "/api/storage/snapshot", List.of("name"), taskGroup(STORAGE)),
    CLUSTER_STORAGE_LIST(GET, "/api/cluster/storage", List.of(), LEADER),
    CLUSTER_STORAGE_GET(GET, "/api/cluster/storage", List.of("name"), LEADER),
    STREAM_CREATE(POST, "/api/streams", List.of(), taskGroup(STREAMING)),
    STREAM_LIST(GET, "/api/streams", List.of(), taskGroup(STREAMING)),
    STREAM_GET(GET, "/api/streams", List.of("name"), taskGroup(STREAMING)),
    STREAM_PARTITION(GET, "/api/streams", List.of("name", "partition"), taskGroup(STREAMING)),
    STREAM_PUBLISH(POST, "/api/streams/publish", List.of("name"), taskGroup(STREAMING)),
    STREAM_DELETE(DELETE, "/api/streams", List.of("name"), taskGroup(STREAMING)),
    STREAM_CONSUMERS(GET, "/api/streams/consumers", List.of("name"), taskGroup(STREAMING)),
    STREAM_READ(GET, "/api/streams/read", List.of("name", "partition"), taskGroup(STREAMING)),
    // #260/#261/#333 replica-state observability. taskGroup(STREAMING) lands the request on a
    // STREAMING-capable node; the handler then resolves the partition's deterministic HRW owner and
    // assembles the replica-set view from the local `ReplicaRegistry` (authoritative only ON the
    // owner — see `servedByOwner` in the response). Per-partition-owner management forwarding is not
    // a `RouteTarget` variant (the owner is computed from name+partition, not a path param), so the
    // response is owner-aware rather than owner-forwarded.
    STREAM_REPLICAS(GET, "/api/streams/replicas", List.of("name", "partition"), taskGroup(STREAMING)),
    // #490 per-node LOCAL variant of STREAM_REPLICAS (the membership-endpoint pattern): the RECEIVING
    // node answers from its OWN ReplicaRegistry/owner resolver — never delegate-routed — so querying a
    // specific node's management port observes THAT node's view, and `servedByOwner=true` is actually
    // reachable over HTTP by querying the resolved owner's port. The delegate-routed variant above
    // structurally cannot return it unless the delegate happens to be the owner (probe-proven: all 5
    // ports answered with one identical delegate view). Static prefix `/api/streams/replicas/local` is
    // matched before `/api/streams/replicas/{name}/{partition}` by the longest-static-prefix rule.
    STREAM_REPLICAS_LOCAL(GET, "/api/streams/replicas/local", List.of("name", "partition"), LOCAL),
    // #345 I3 per-node durable-entity checkpoint observability. LOCAL, not delegate-routed: each node
    // checkpoints only the partitions IT folds, so a delegate's answer would describe a different node's
    // work. The surface exists because a checkpoint driver that silently stopped is otherwise
    // indistinguishable from a working one — writes and reads keep succeeding, and the only symptom is an
    // entity log that is never reclaimed, surfacing hours later as disk growth.
    ENTITY_CHECKPOINTS(GET, "/api/entity/checkpoints", List.of(), LOCAL),
    // #265 increment 0 per-node hydration observability. Static prefix `/api/streams/hydration` (0
    // params) is matched before `/api/streams/{name}` (STREAM_GET, 1 param) by the longest-static-prefix
    // rule in RouteMatcher, so there is no collision. taskGroup(STREAMING) lands it on a STREAMING-capable
    // node; the handler assembles the snapshot from that node's local StreamPartitionManager (per-node
    // materialized-ring / floor-byte / placement-role view — the §6 regression sensor).
    STREAM_HYDRATION(GET, "/api/streams/hydration", List.of(), taskGroup(STREAMING)),
    // #488 declarative-consumer observability: which `[streams.X]` consumers this node has actually
    // attached, on which partitions, at which committed offsets — plus the two ways a declared consumer
    // ends up receiving nothing (this node owns partitions whose slice is not deployed here, or the
    // event type is unpublishable per #526). LOCAL, not taskGroup: subscriptions are per-node truth,
    // since a node consumes exactly the partitions it owns. Static prefix
    // `/api/streams/declarative-consumers` (0 params) is matched before `/api/streams/{name}`
    // (STREAM_GET) by the longest-static-prefix rule, and shares no segment with
    // `/api/streams/consumers/{name}` (STREAM_CONSUMERS), so neither collides.
    STREAM_DECLARATIVE_CONSUMERS(GET, "/api/streams/declarative-consumers", List.of(), LOCAL),
    CONSUMER_GROUP_JOIN(POST, "/api/streams/groups/join", List.of(), taskGroup(STREAMING)),
    CONSUMER_GROUP_LEAVE(POST, "/api/streams/groups/leave", List.of(), taskGroup(STREAMING)),
    CONSUMER_GROUP_STATUS(GET, "/api/streams/groups", List.of("id"), taskGroup(STREAMING)),
    STREAMS_LIST(GET, "/api/streams/list", List.of(), taskGroup(STREAMING)),
    STREAMS_VERSIONS_LIST(GET, "/api/streams/versions", List.of("namespace", "stream"), taskGroup(STREAMING)),
    STREAMS_LATEST(GET, "/api/streams/latest", List.of("namespace", "stream"), taskGroup(STREAMING)),
    STREAMS_METADATA(GET, "/api/streams/metadata", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_TAIL(GET, "/api/streams/tail", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_EVENTS(GET, "/api/streams/events", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_GROUPS_LIST(GET, "/api/streams/groups", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_PUBLISH(POST, "/api/streams/publish", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_PUBLISH_BATCH(POST,
                          "/api/streams/publish-batch",
                          List.of("namespace", "stream", "version"),
                          taskGroup(STREAMING)),
    STREAMS_GROUP_CREATE(POST,
                         "/api/streams/groups/create",
                         List.of("namespace", "stream", "version"),
                         taskGroup(STREAMING)),
    STREAMS_GROUP_DELETE(DELETE,
                         "/api/streams/groups/delete",
                         List.of("namespace", "stream", "version", "group"),
                         taskGroup(STREAMING)),
    STREAMS_DELETE(DELETE, "/api/streams/delete", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAM_NAMESPACES_LIST(GET, "/api/stream-namespaces/list", List.of(), LOCAL),
    STREAM_NAMESPACES_GET(GET, "/api/stream-namespaces/get", List.of("namespace", "stream", "version"), LOCAL),
    SCHEDULED_TASKS_LIST(GET, "/api/scheduled-tasks", List.of(), LEADER),
    SCHEDULED_TASKS_BY_SECTION(GET, "/api/scheduled-tasks", List.of("section"), LEADER),
    SCHEDULED_TASK_STATE(GET, "/api/scheduled-tasks/state", List.of("section", "artifact", "methodName"), LEADER),
    SCHEDULED_TASK_PAUSE(POST, "/api/scheduled-tasks/pause", List.of("section", "artifact", "methodName"), LEADER),
    SCHEDULED_TASK_RESUME(POST, "/api/scheduled-tasks/resume", List.of("section", "artifact", "methodName"), LEADER),
    SCHEDULED_TASK_TRIGGER(POST,
                           "/api/scheduled-tasks/trigger",
                           List.of("section", "artifact", "methodName"),
                           taskGroup(STRATEGIES)),
    SCHEDULED_TASK_INJECT(POST, "/api/scheduled-tasks/inject", List.of(), LOCAL),
    SCHEDULED_TASK_EXECUTIONS_BY_NODE(GET,
                                      "/api/scheduled-tasks/executions-by-node",
                                      List.of("section", "artifact", "methodName"),
                                      LEADER),
    CERT_CONFIGURE_SHORT_VALIDITY(POST, "/api/certificates/configure-short-validity", List.of(), LOCAL),
    ARTIFACT_GET(GET, "/repository", List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_PUT(PUT, "/repository", List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_POST(POST, "/repository", List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_INFO(GET, "/repository/info", List.of("groupPath", "artifactId", "version"), taskGroup(DEPLOYMENT)),
    ARTIFACT_DELETE(DELETE, "/repository", List.of("groupPath", "artifactId", "version"), taskGroup(DEPLOYMENT)),
    METRICS(GET, "/api/metrics", List.of(), LOCAL),
    METRICS_COMPREHENSIVE(GET, "/api/metrics/comprehensive", List.of(), LOCAL),
    METRICS_DERIVED(GET, "/api/metrics/derived", List.of(), LOCAL),
    METRICS_PROMETHEUS(GET, "/api/metrics/prometheus", List.of(), LOCAL),
    METRICS_HISTORY(GET, "/api/metrics/history", List.of(), LOCAL),
    METRICS_TRANSPORT(GET, "/api/metrics/transport", List.of(), LOCAL),
    METRICS_TIMEOUTS(GET, "/api/metrics/timeouts", List.of(), LOCAL),
    METRICS_BACKFILL(POST, "/api/metrics/backfill", List.of(), LOCAL),
    NODE_METRICS(GET, "/api/nodes/metrics", List.of(), LOCAL),
    NODE_METRICS_GET(GET, "/api/nodes/metrics", List.of("id"), RouteTarget.nodeIdParam(0)),
    ARTIFACT_METRICS(GET, "/api/artifacts/metrics", List.of(), LOCAL),
    INVOCATION_METRICS(GET, "/api/invocations/metrics", List.of(), LOCAL),
    INVOCATION_METRICS_SLOW(GET, "/api/invocations/metrics/slow", List.of(), LOCAL),
    INVOCATION_METRICS_STRATEGY_GET(GET, "/api/invocations/metrics/strategy", List.of(), LOCAL),
    INVOCATION_METRICS_STRATEGY_SET(POST, "/api/invocations/metrics/strategy", List.of(), LOCAL),
    THRESHOLDS_LIST(GET, "/api/thresholds", List.of(), LEADER),
    THRESHOLD_SET(POST, "/api/thresholds", List.of(), LEADER),
    THRESHOLD_DELETE(DELETE, "/api/thresholds", List.of("metric"), LEADER),
    ALERTS(GET, "/api/alerts", List.of(), LOCAL),
    ALERTS_ACTIVE(GET, "/api/alerts/active", List.of(), LOCAL),
    ALERTS_HISTORY(GET, "/api/alerts/history", List.of(), LOCAL),
    ALERTS_CLEAR(POST, "/api/alerts/clear", List.of(), LOCAL),
    ALERTS_INJECT(POST, "/api/alerts/inject", List.of(), LOCAL),
    BACKUP_TRIGGER(POST, "/api/backups", List.of(), taskGroup(DEPLOYMENT)),
    BACKUPS_LIST(GET, "/api/backups", List.of(), LOCAL),
    BACKUP_RESTORE(POST, "/api/backups/restore", List.of(), taskGroup(DEPLOYMENT)),
    CONFIG_LIST(GET, "/api/config", List.of(), LEADER),
    CONFIG_OVERRIDES(GET, "/api/config/overrides", List.of(), LEADER),
    CONFIG_SET(POST, "/api/config", List.of(), taskGroup(DEPLOYMENT)),
    CONFIG_DELETE(DELETE, "/api/config", List.of("key"), taskGroup(DEPLOYMENT)),
    CONFIG_NODE_DELETE(DELETE, "/api/config/nodes", List.of("id", "key"), taskGroup(DEPLOYMENT)),
    CONTROLLER_CONFIG_GET(GET, "/api/controller/config", List.of(), LEADER),
    CONTROLLER_STATUS(GET, "/api/controller/status", List.of(), LEADER),
    // #425 per-slice scaling decision snapshot. LEADER-bound: the control loop runs on the leader,
    // so the decision map + cluster-CPU context live there. Pure snapshot read (no hot-path cost).
    CONTROLLER_DECISIONS(GET, "/api/controller/decisions", List.of(), LEADER),
    CONTROLLER_CONFIG_SET(POST, "/api/controller/config", List.of(), LEADER),
    CONTROLLER_EVALUATE(POST, "/api/controller/evaluate", List.of(), LEADER),
    TTM_STATUS(GET, "/api/ttm/status", List.of(), LOCAL),
    TTM_TRAINING_DATA(GET, "/api/ttm/training-data", List.of(), LOCAL),
    LOG_LEVELS_LIST(GET, "/api/logging/levels", List.of(), LOCAL),
    LOG_LEVEL_SET(POST, "/api/logging/levels", List.of(), LOCAL),
    LOG_LEVEL_RESET(DELETE, "/api/logging/levels", List.of("logger"), LOCAL),
    TRACES_QUERY(GET, "/api/traces", List.of(), LOCAL),
    TRACES_STATS(GET, "/api/traces/stats", List.of(), LOCAL),
    TRACES_INJECT(POST, "/api/traces/inject", List.of(), LOCAL),
    TRACE_BY_REQUEST_ID(GET, "/api/traces", List.of("id"), LOCAL),
    OBSERVABILITY_DEPTH_GET(GET, "/api/observability/depth", List.of(), LEADER),
    OBSERVABILITY_DEPTH_SET(POST, "/api/observability/depth", List.of(), LEADER),
    OBSERVABILITY_DEPTH_DELETE(DELETE, "/api/observability/depth", List.of("artifact", "methodName"), LEADER),
    OBSERVABILITY_CONFIG_GET(GET, "/api/observability/config", List.of(), LEADER),
    OBSERVABILITY_CONFIG_GET_ONE(GET, "/api/observability/config", List.of("artifact", "method"), LEADER),
    OBSERVABILITY_CONFIG_SET(POST, "/api/observability/config", List.of(), LEADER),
    OBSERVABILITY_CONFIG_DELETE(DELETE, "/api/observability/config", List.of("artifact", "method"), LEADER),
    DHT_INJECT(POST, "/api/dht/inject", List.of(), LOCAL),
    DHT_REPLICATION_MAP(GET, "/api/dht/replication-map", List.of(), LOCAL);
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
