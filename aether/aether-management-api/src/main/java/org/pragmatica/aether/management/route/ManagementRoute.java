// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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
    HEALTH_LIVE(GET, raw("/health/live"), List.of(), LOCAL),
    HEALTH_LIVE_GET(GET, raw("/health/live"), List.of("id"), RouteTarget.nodeIdParam(0)),
    HEALTH_READY(GET, raw("/health/ready"), List.of(), LOCAL),
    HEALTH_READY_GET(GET, raw("/health/ready"), List.of("id"), RouteTarget.nodeIdParam(0)),
    NODE_STATUS(GET, "/nodes/status", List.of(), LEADER),
    NODE_STATUS_GET(GET, "/nodes/status", List.of("id"), RouteTarget.nodeIdParam(0)),
    NODE_ENDPOINT_GET(GET, "/nodes/endpoint", List.of("id"), RouteTarget.nodeIdParam(0)),
    NODES_LIVE(GET, "/nodes/live", List.of(), RouteTarget.ANY),
    NODES_LIST(GET, "/nodes", List.of(), LEADER),
    WHOAMI(GET, "/whoami", List.of(), LOCAL),
    CLUSTER_HEALTH(GET, "/health", List.of(), LEADER),
    // #267: served from ANY core node, not leader-bound. cluster-events is a replicated single-partition
    // stream; a non-replica core node read-forwards to a CAUGHT_UP replica (forward-capable consumer),
    // so `/api/events` stays available during leader churn/election instead of returning 503 — exactly
    // when operators most need events. Replica-read correctness rests on #260 (offset verification) and
    // #261 (real backfill). Staleness: a forwarded read reflects the replica's CAUGHT_UP watermark,
    // which may trail the owner by the in-flight replication window (sub-second under steady load).
    EVENTS(GET, "/events", List.of(), ANY),
    CERTIFICATES_LIST(GET, "/certificates", List.of(), LOCAL),
    CLUSTER_TOPOLOGY(GET, "/cluster/topology", List.of(), LEADER),
    CLUSTER_GENERATION(GET, "/cluster/generation", List.of(), LEADER),
    CLUSTER_AWAIT_QUIESCED(POST, "/cluster/await-quiesced", List.of(), LEADER),
    CLUSTER_GOVERNORS(GET, "/cluster/governors", List.of(), LEADER),
    CLUSTER_JOURNAL(GET, "/cluster/journal", List.of(), LOCAL),
    CLUSTER_CONFIG_GET(GET, "/cluster/config", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_PROVISIONING_GET(GET, "/cluster/provisioning", List.of(), taskGroup(DEPLOYMENT)),
    // PER-NODE local view (LOCAL, never leader/owner-forwarded): each survivor answers from its OWN
    // MembershipFsm + QuorumLossDetector, so an operator can query a specific node and see THAT
    // node's per-peer SUSPECT/DEAD states + self-drain armed/below-quorum signal. taskGroup/LEADER
    // would forward and collapse the per-node distinction this endpoint exists to expose.
    CLUSTER_MEMBERSHIP_GET(GET, "/cluster/membership", List.of(), LOCAL),
    // #345 item 1f: per-node committed ownership/fence view. `domain` (community|dht|stream) is a path
    // param; LOCAL (never leader/owner-forwarded) like membership — each node answers from its OWN
    // committed KV-Store, so an operator can read the owner NodeId + fence Epoch per partition/key that
    // THIS node has applied. The committed ownership atoms are Rabia-replicated, so a LOCAL read off any
    // caught-up node reflects the fenced owner without forwarding.
    CLUSTER_OWNERSHIP_GET(GET, "/ownership", List.of("domain"), LOCAL),
    CLUSTER_STATUS(GET, "/cluster/status", List.of(), LEADER),
    CLUSTER_CONFIG_APPLY(POST, "/cluster/config", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_SCALE(POST, "/cluster/scale", List.of(), LEADER),
    CLUSTER_CIRCUIT_BREAKER_STATUS(GET, "/cluster/topology/circuit-breaker", List.of(), LEADER),
    CLUSTER_CIRCUIT_BREAKER_RESET(POST, "/cluster/topology/circuit-breaker/reset", List.of(), LEADER),
    CLUSTER_AUTO_HEAL_STATUS(GET, "/cluster/topology/auto-heal", List.of(), LEADER),
    CLUSTER_AUTO_HEAL_ENABLE(POST, "/cluster/topology/auto-heal/enable", List.of(), LEADER),
    CLUSTER_AUTO_HEAL_DISABLE(POST, "/cluster/topology/auto-heal/disable", List.of(), LEADER),
    CLUSTER_UPGRADE(POST, "/cluster/upgrade", List.of(), taskGroup(DEPLOYMENT)),
    DEPLOY_START(POST, "/deploy", List.of(), taskGroup(STRATEGIES)),
    DEPLOY_LIST(GET, "/deploy", List.of(), taskGroup(STRATEGIES)),
    DEPLOY_STATUS(GET, "/deploy", List.of("id"), taskGroup(STRATEGIES)),
    DEPLOY_PROMOTE(POST, "/deploy/promote", List.of("id"), taskGroup(STRATEGIES)),
    DEPLOY_ROLLBACK(POST, "/deploy/rollback", List.of("id"), taskGroup(STRATEGIES)),
    DEPLOY_COMPLETE(POST, "/deploy/complete", List.of("id"), taskGroup(STRATEGIES)),
    AB_TEST_LIST(GET, "/ab-tests", List.of(), taskGroup(STRATEGIES)),
    AB_TEST_GET(GET, "/ab-tests", List.of("id"), taskGroup(STRATEGIES)),
    AB_TEST_METRICS(GET, "/ab-tests/metrics", List.of("id"), taskGroup(STRATEGIES)),
    AB_TEST_CREATE(POST, "/ab-tests/create", List.of(), taskGroup(STRATEGIES)),
    AB_TEST_CONCLUDE(POST, "/ab-tests/conclude", List.of("id"), taskGroup(STRATEGIES)),
    BLUEPRINT_LIST(GET, "/blueprints", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_PUBLISH_BODY(POST, "/blueprints", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_GET(GET, "/blueprints", List.of("id"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_STATUS(GET, "/blueprints/status", List.of("id"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_DELETE(DELETE, "/blueprints", List.of("id"), taskGroup(DEPLOYMENT)),
    BLUEPRINT_DEPLOY(POST, "/blueprints/deploy", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_PUBLISH_ARTIFACT(POST, "/blueprints/publish", List.of(), taskGroup(DEPLOYMENT)),
    BLUEPRINT_VALIDATE(POST, "/blueprints/validate", List.of(), taskGroup(DEPLOYMENT)),
    SLICES_LIST(GET, "/slices", List.of(), LEADER),
    SLICES_STATUS(GET, "/slices/status", List.of(), LEADER),
    SLICE_TOPOLOGY(GET, "/slices/topology", List.of(), LEADER),
    SLICE_CONFIG(GET, "/slices/config", List.of("id"), LEADER),
    NODE_SLICES(GET, "/nodes/slices", List.of(), LEADER),
    NODE_SLICES_GET(GET, "/nodes/slices", List.of("id"), RouteTarget.nodeIdParam(0)),
    NODE_ROUTES(GET, "/nodes/routes", List.of(), LEADER),
    NODE_ROUTES_GET(GET, "/nodes/routes", List.of("id"), RouteTarget.nodeIdParam(0)),
    ROUTES_LIST(GET, "/routes", List.of(), LEADER),
    // PER-NODE local view (#198 §11.3): each node lists the versioned slices IT has deployed, read
    // from its own HttpRoutePublisher registry. LOCAL (not LEADER/forwarded) so an operator can query
    // a specific node and see that node's served version metadata + deprecation/sunset knobs.
    VERSIONS(GET, "/versions", List.of(), LOCAL),
    SLICE_SCALE(POST, "/scale", List.of(), taskGroup(SCALING)),
    WORKERS_LIST(GET, "/workers", List.of(), LEADER),
    // #525: declared but not built. Per-worker health and per-worker endpoints are never published
    // to consensus — the only worker facts that reach the KV-Store are the community roster and the
    // GOVERNOR's tcpAddress (GovernorAnnouncementValue), which WORKERS_LIST already serves. Both are
    // answered by NotImplementedRoutes with an honest 501 naming the missing capability, rather than
    // being left unserved (a bare 404 tells the operator nothing) or deleted (worker mode is live —
    // see AetherNode.activateWorkerMode). Implementing them requires workers to publish health and
    // endpoint facts; that is a feature, not a repair.
    WORKERS_HEALTH(GET, "/workers/health", List.of(), LEADER),
    WORKERS_ENDPOINTS(GET, "/workers/endpoints", List.of(), LEADER),
    // #525: declared but not built — cloud migration has no server-side implementation at all.
    // Answered by NotImplementedRoutes with an honest 501. These two hid from the #525 sweep because
    // they ARE referenced under aether/node/src/main — but only in the ManagementRoutePermissions
    // table, which grants authorization for a handler that does not exist. Permission-table presence
    // is not service; ManagementRouteCoverageTest keys on handler registration for exactly this reason.
    CLUSTER_MIGRATE(POST, "/cluster/migrate", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_MIGRATE_PLAN(POST, "/cluster/migrate/plan", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_CREATE(POST, "/cluster/keys", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_LIST(GET, "/cluster/keys", List.of(), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_REVOKE(POST, "/cluster/keys/revoke", List.of("id"), taskGroup(DEPLOYMENT)),
    CLUSTER_KEYS_AUDIT(GET, "/cluster/keys/audit", List.of(), taskGroup(DEPLOYMENT)),
    REPOSITORY_ARTIFACTS_LIST(GET, raw("/repository/artifacts"), List.of(), taskGroup(DEPLOYMENT)),
    MAVEN_METADATA(GET, raw("/repository"), List.of("groupPath", "artifactId", "file"), taskGroup(DEPLOYMENT)),
    NODE_LIFECYCLE_LIST(GET, "/nodes/lifecycle", List.of(), LEADER),
    NODE_LIFECYCLE_GET(GET, "/nodes/lifecycle", List.of("id"), LEADER),
    NODE_DRAIN(POST, "/nodes/drain", List.of("id"), taskGroup(DEPLOYMENT)),
    NODE_SHUTDOWN(POST, "/nodes/shutdown", List.of("id"), taskGroup(DEPLOYMENT)),
    NODE_PROMOTE(POST, "/nodes/promote", List.of("id"), LEADER),
    NODE_INFLIGHT(GET, "/nodes/inflight", List.of(), LOCAL),
    NODE_INFLIGHT_GET(GET, "/nodes/inflight", List.of("id"), RouteTarget.nodeIdParam(0)),
    SCHEMA_STATUS_ALL(GET, "/schema/status", List.of(), taskGroup(DEPLOYMENT)),
    SCHEMA_STATUS_ONE(GET, "/schema/status", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_HISTORY(GET, "/schema/history", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_MIGRATE(POST, "/schema/migrate", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_UNDO(POST, "/schema/undo", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_BASELINE(POST, "/schema/baseline", List.of("datasource"), taskGroup(DEPLOYMENT)),
    SCHEMA_RETRY(POST, "/schema/retry", List.of("datasource"), taskGroup(DEPLOYMENT)),
    STORAGE_LIST(GET, "/storage", List.of(), LOCAL),
    STORAGE_GET(GET, "/storage", List.of("name"), LOCAL),
    STORAGE_SNAPSHOT(POST, "/storage/snapshot", List.of("name"), taskGroup(STORAGE)),
    STORAGE_RETENTION(GET, "/storage/retention", List.of(), LOCAL),
    CLUSTER_STORAGE_LIST(GET, "/cluster/storage", List.of(), LEADER),
    CLUSTER_STORAGE_GET(GET, "/cluster/storage", List.of("name"), LEADER),
    STREAM_CREATE(POST, "/streams", List.of(), taskGroup(STREAMING)),
    STREAM_LIST(GET, "/streams", List.of(), taskGroup(STREAMING)),
    STREAM_GET(GET, "/streams", List.of("name"), taskGroup(STREAMING)),
    STREAM_PARTITION(GET, "/streams", List.of("name", "partition"), taskGroup(STREAMING)),
    STREAM_PUBLISH(POST, "/streams/publish", List.of("name"), taskGroup(STREAMING)),
    STREAM_DELETE(DELETE, "/streams", List.of("name"), taskGroup(STREAMING)),
    STREAM_CONSUMERS(GET, "/streams/consumers", List.of("name"), taskGroup(STREAMING)),
    STREAM_READ(GET, "/streams/read", List.of("name", "partition"), taskGroup(STREAMING)),
    // #260/#261/#333 replica-state observability. taskGroup(STREAMING) lands the request on a
    // STREAMING-capable node; the handler then resolves the partition's deterministic HRW owner and
    // assembles the replica-set view from the local `ReplicaRegistry` (authoritative only ON the
    // owner — see `servedByOwner` in the response). Per-partition-owner management forwarding is not
    // a `RouteTarget` variant (the owner is computed from name+partition, not a path param), so the
    // response is owner-aware rather than owner-forwarded.
    STREAM_REPLICAS(GET, "/streams/replicas", List.of("name", "partition"), taskGroup(STREAMING)),
    // #490 per-node LOCAL variant of STREAM_REPLICAS (the membership-endpoint pattern): the RECEIVING
    // node answers from its OWN ReplicaRegistry/owner resolver — never delegate-routed — so querying a
    // specific node's management port observes THAT node's view, and `servedByOwner=true` is actually
    // reachable over HTTP by querying the resolved owner's port. The delegate-routed variant above
    // structurally cannot return it unless the delegate happens to be the owner (probe-proven: all 5
    // ports answered with one identical delegate view). Static prefix `/api/streams/replicas/local` is
    // matched before `/api/streams/replicas/{name}/{partition}` by the longest-static-prefix rule.
    STREAM_REPLICAS_LOCAL(GET, "/streams/replicas/local", List.of("name", "partition"), LOCAL),
    // #345 I3 per-node durable-entity checkpoint observability. LOCAL, not delegate-routed: each node
    // checkpoints only the partitions IT folds, so a delegate's answer would describe a different node's
    // work. The surface exists because a checkpoint driver that silently stopped is otherwise
    // indistinguishable from a working one — writes and reads keep succeeding, and the only symptom is an
    // entity log that is never reclaimed, surfacing hours later as disk growth.
    ENTITY_CHECKPOINTS(GET, "/entity/checkpoints", List.of(), LOCAL),
    ENTITY_KEYSPACES(GET, "/entity/keyspaces", List.of(), LOCAL),
    // #265 increment 0 per-node hydration observability. Static prefix `/api/streams/hydration` (0
    // params) is matched before `/api/streams/{name}` (STREAM_GET, 1 param) by the longest-static-prefix
    // rule in RouteMatcher, so there is no collision. taskGroup(STREAMING) lands it on a STREAMING-capable
    // node; the handler assembles the snapshot from that node's local StreamPartitionManager (per-node
    // materialized-ring / floor-byte / placement-role view — the §6 regression sensor).
    STREAM_HYDRATION(GET, "/streams/hydration", List.of(), taskGroup(STREAMING)),
    // #488 declarative-consumer observability: which `[streams.X]` consumers this node has actually
    // attached, on which partitions, at which committed offsets — plus the two ways a declared consumer
    // ends up receiving nothing (this node owns partitions whose slice is not deployed here, or the
    // event type is unpublishable per #526). LOCAL, not taskGroup: subscriptions are per-node truth,
    // since a node consumes exactly the partitions it owns. Static prefix
    // `/api/streams/declarative-consumers` (0 params) is matched before `/api/streams/{name}`
    // (STREAM_GET) by the longest-static-prefix rule, and shares no segment with
    // `/api/streams/consumers/{name}` (STREAM_CONSUMERS), so neither collides.
    STREAM_DECLARATIVE_CONSUMERS(GET, "/streams/declarative-consumers", List.of(), LOCAL),
    CONSUMER_GROUP_JOIN(POST, "/streams/groups/join", List.of(), taskGroup(STREAMING)),
    CONSUMER_GROUP_LEAVE(POST, "/streams/groups/leave", List.of(), taskGroup(STREAMING)),
    CONSUMER_GROUP_STATUS(GET, "/streams/groups", List.of("id"), taskGroup(STREAMING)),
    STREAMS_LIST(GET, "/streams/list", List.of(), taskGroup(STREAMING)),
    STREAMS_VERSIONS_LIST(GET, "/streams/versions", List.of("namespace", "stream"), taskGroup(STREAMING)),
    STREAMS_LATEST(GET, "/streams/latest", List.of("namespace", "stream"), taskGroup(STREAMING)),
    STREAMS_METADATA(GET, "/streams/metadata", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_TAIL(GET, "/streams/tail", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_EVENTS(GET, "/streams/events", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_GROUPS_LIST(GET, "/streams/groups", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_PUBLISH(POST, "/streams/publish", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_PUBLISH_BATCH(POST,
                          "/streams/publish-batch",
                          List.of("namespace", "stream", "version"),
                          taskGroup(STREAMING)),
    STREAMS_GROUP_CREATE(POST, "/streams/groups/create", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAMS_GROUP_DELETE(DELETE,
                         "/streams/groups/delete",
                         List.of("namespace", "stream", "version", "group"),
                         taskGroup(STREAMING)),
    STREAMS_DELETE(DELETE, "/streams/delete", List.of("namespace", "stream", "version"), taskGroup(STREAMING)),
    STREAM_NAMESPACES_LIST(GET, "/stream-namespaces/list", List.of(), LOCAL),
    STREAM_NAMESPACES_GET(GET, "/stream-namespaces/get", List.of("namespace", "stream", "version"), LOCAL),
    SCHEDULED_TASKS_LIST(GET, "/scheduled-tasks", List.of(), LEADER),
    SCHEDULED_TASKS_BY_SECTION(GET, "/scheduled-tasks", List.of("section"), LEADER),
    SCHEDULED_TASK_STATE(GET, "/scheduled-tasks/state", List.of("section", "artifact", "methodName"), LEADER),
    SCHEDULED_TASK_PAUSE(POST, "/scheduled-tasks/pause", List.of("section", "artifact", "methodName"), LEADER),
    SCHEDULED_TASK_RESUME(POST, "/scheduled-tasks/resume", List.of("section", "artifact", "methodName"), LEADER),
    SCHEDULED_TASK_TRIGGER(POST,
                           "/scheduled-tasks/trigger",
                           List.of("section", "artifact", "methodName"),
                           taskGroup(STRATEGIES)),
    SCHEDULED_TASK_INJECT(POST, "/scheduled-tasks/inject", List.of(), LOCAL),
    SCHEDULED_TASK_EXECUTIONS_BY_NODE(GET,
                                      "/scheduled-tasks/executions-by-node",
                                      List.of("section", "artifact", "methodName"),
                                      LEADER),
    CERT_CONFIGURE_SHORT_VALIDITY(POST, "/certificates/configure-short-validity", List.of(), LOCAL),
    ARTIFACT_GET(GET, raw("/repository"), List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_PUT(PUT, raw("/repository"), List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_POST(POST, raw("/repository"), List.of("groupPath", "artifactId", "version", "file"), taskGroup(DEPLOYMENT)),
    ARTIFACT_INFO(GET, raw("/repository/info"), List.of("groupPath", "artifactId", "version"), taskGroup(DEPLOYMENT)),
    ARTIFACT_DELETE(DELETE, raw("/repository"), List.of("groupPath", "artifactId", "version"), taskGroup(DEPLOYMENT)),
    METRICS(GET, "/metrics", List.of(), LOCAL),
    METRICS_COMPREHENSIVE(GET, "/metrics/comprehensive", List.of(), LOCAL),
    METRICS_DERIVED(GET, "/metrics/derived", List.of(), LOCAL),
    METRICS_PROMETHEUS(GET, "/metrics/prometheus", List.of(), LOCAL),
    METRICS_HISTORY(GET, "/metrics/history", List.of(), LOCAL),
    METRICS_TRANSPORT(GET, "/metrics/transport", List.of(), LOCAL),
    METRICS_TIMEOUTS(GET, "/metrics/timeouts", List.of(), LOCAL),
    METRICS_BACKFILL(POST, "/metrics/backfill", List.of(), LOCAL),
    NODE_METRICS(GET, "/nodes/metrics", List.of(), LOCAL),
    NODE_METRICS_GET(GET, "/nodes/metrics", List.of("id"), RouteTarget.nodeIdParam(0)),
    ARTIFACT_METRICS(GET, "/artifacts/metrics", List.of(), LOCAL),
    INVOCATION_METRICS(GET, "/invocations/metrics", List.of(), LOCAL),
    INVOCATION_METRICS_SLOW(GET, "/invocations/metrics/slow", List.of(), LOCAL),
    INVOCATION_METRICS_STRATEGY_GET(GET, "/invocations/metrics/strategy", List.of(), LOCAL),
    INVOCATION_METRICS_STRATEGY_SET(POST, "/invocations/metrics/strategy", List.of(), LOCAL),
    THRESHOLDS_LIST(GET, "/thresholds", List.of(), LEADER),
    THRESHOLD_SET(POST, "/thresholds", List.of(), LEADER),
    THRESHOLD_DELETE(DELETE, "/thresholds", List.of("metric"), LEADER),
    ALERTS(GET, "/alerts", List.of(), LOCAL),
    ALERTS_ACTIVE(GET, "/alerts/active", List.of(), LOCAL),
    ALERTS_HISTORY(GET, "/alerts/history", List.of(), LOCAL),
    ALERTS_CLEAR(POST, "/alerts/clear", List.of(), LOCAL),
    ALERTS_INJECT(POST, "/alerts/inject", List.of(), LOCAL),
    BACKUP_TRIGGER(POST, "/backups", List.of(), taskGroup(DEPLOYMENT)),
    BACKUPS_LIST(GET, "/backups", List.of(), LOCAL),
    BACKUP_RESTORE(POST, "/backups/restore", List.of(), taskGroup(DEPLOYMENT)),
    CONFIG_LIST(GET, "/config", List.of(), LEADER),
    CONFIG_OVERRIDES(GET, "/config/overrides", List.of(), LEADER),
    CONFIG_SET(POST, "/config", List.of(), taskGroup(DEPLOYMENT)),
    CONFIG_DELETE(DELETE, "/config", List.of("key"), taskGroup(DEPLOYMENT)),
    CONFIG_NODE_DELETE(DELETE, "/config/nodes", List.of("id", "key"), taskGroup(DEPLOYMENT)),
    CONTROLLER_CONFIG_GET(GET, "/controller/config", List.of(), LEADER),
    CONTROLLER_STATUS(GET, "/controller/status", List.of(), LEADER),
    // #425 per-slice scaling decision snapshot. LEADER-bound: the control loop runs on the leader,
    // so the decision map + cluster-CPU context live there. Pure snapshot read (no hot-path cost).
    CONTROLLER_DECISIONS(GET, "/controller/decisions", List.of(), LEADER),
    CONTROLLER_CONFIG_SET(POST, "/controller/config", List.of(), LEADER),
    CONTROLLER_EVALUATE(POST, "/controller/evaluate", List.of(), LEADER),
    TTM_STATUS(GET, "/ttm/status", List.of(), LOCAL),
    TTM_TRAINING_DATA(GET, "/ttm/training-data", List.of(), LOCAL),
    LOG_LEVELS_LIST(GET, "/logging/levels", List.of(), LOCAL),
    LOG_LEVEL_SET(POST, "/logging/levels", List.of(), LOCAL),
    LOG_LEVEL_RESET(DELETE, "/logging/levels", List.of("logger"), LOCAL),
    TRACES_QUERY(GET, "/traces", List.of(), LOCAL),
    TRACES_STATS(GET, "/traces/stats", List.of(), LOCAL),
    TRACES_INJECT(POST, "/traces/inject", List.of(), LOCAL),
    TRACE_BY_REQUEST_ID(GET, "/traces", List.of("id"), LOCAL),
    OBSERVABILITY_DEPTH_GET(GET, "/observability/depth", List.of(), LEADER),
    OBSERVABILITY_DEPTH_SET(POST, "/observability/depth", List.of(), LEADER),
    OBSERVABILITY_DEPTH_DELETE(DELETE, "/observability/depth", List.of("artifact", "methodName"), LEADER),
    OBSERVABILITY_CONFIG_GET(GET, "/observability/config", List.of(), LEADER),
    OBSERVABILITY_CONFIG_GET_ONE(GET, "/observability/config", List.of("artifact", "method"), LEADER),
    OBSERVABILITY_CONFIG_SET(POST, "/observability/config", List.of(), LEADER),
    OBSERVABILITY_CONFIG_DELETE(DELETE, "/observability/config", List.of("artifact", "method"), LEADER),
    DHT_INJECT(POST, "/dht/inject", List.of(), LOCAL),
    DHT_REPLICATION_MAP(GET, "/dht/replication-map", List.of(), LOCAL);
    /// Composed at this ONE site (management-api-versioning-spec.md §2.1): every entry above whose
    /// second argument is a plain `String` gets `API_BASE` prepended automatically by the canonical
    /// constructor. §2.2 carve-outs (health probes, `/repository/**`) opt out by wrapping their
    /// literal in [#raw(String)] instead, which routes through the distinct [Raw] constructor
    /// overload and is stored verbatim — unversioned, per spec.
    private static final String API_BASE = "/api/v1";
    /// Marker type for the distinct-type-overload carve-out constructor — see [#raw(String)].
    private record Raw(String value) {}
    private static Raw raw(String path) {
        return new Raw(path);
    }
    private final HttpMethod method;
    private final String prefix;
    private final List<String> paramNames;
    private final RouteTarget target;
    private final List<PathToken> tokens;
    ManagementRoute(HttpMethod method, String suffix, List<String> paramNames, RouteTarget target) {
        this.method = method;
        this.prefix = API_BASE + suffix;
        this.paramNames = List.copyOf(paramNames);
        this.target = target;
        this.tokens = tailParamTokens(this.prefix, this.paramNames);
    }
    ManagementRoute(HttpMethod method, Raw prefix, List<String> paramNames, RouteTarget target) {
        this.method = method;
        this.prefix = prefix.value();
        this.paramNames = List.copyOf(paramNames);
        this.target = target;
        this.tokens = tailParamTokens(this.prefix, this.paramNames);
    }
    /// Carve-out for routes whose params interleave with literal segments (a literal after or
    /// between params) -- the shape the two constructors above cannot express, since they always
    /// place every param at the tail. `suffixTokens` describes only the path AFTER [#API_BASE],
    /// which is prepended as leading spacer tokens the same way the plain-`suffix` constructor
    /// prepends it to a literal string, so composition stays at the one site (spec Sec 2.1).
    /// `paramNames()`/`paramCount()` are derived from the `Param` tokens, in order, so
    /// [MatchedRoute] and existing param-name-driven callers see no difference from the old shape.
    ManagementRoute(HttpMethod method, List<PathToken> suffixTokens, RouteTarget target) {
        this.method = method;
        this.tokens = interleavedTokens(suffixTokens);
        this.prefix = leadingLiteralPrefix(this.tokens);
        this.paramNames = paramNamesOf(this.tokens);
        this.target = target;
    }
    private static List<PathToken> tailParamTokens(String prefix, List<String> paramNames) {
        var tokens = new ArrayList<PathToken>();

        for (var segment : prefix.split("/")) {
            if (!segment.isEmpty()) {
                tokens.add(PathToken.spacer(segment));
            }
        }

        for (var name : paramNames) {
            tokens.add(PathToken.param(name));
        }

        return List.copyOf(tokens);
    }
    /// Package-private (not `private`) so the blank-`Spacer` guard below is directly pinnable
    /// without constructing a real enum constant -- a bad token reaching the enum constructor
    /// would fail `ManagementRoute`'s static init and take every other test down with it.
    /// Scoped to interleaved routes only: the tail-only constructors above build their tokens from
    /// a `String` prefix (`tailParamTokens`), which can't produce a blank segment in the first
    /// place, so they need no equivalent guard and their ~150 existing routes need no audit.
    static List<PathToken> interleavedTokens(List<PathToken> suffixTokens) {
        validateNoBlankSpacer(suffixTokens).expect("interleaved ManagementRoute suffix tokens");
        var tokens = new ArrayList<PathToken>();

        for (var segment : API_BASE.split("/")) {
            if (!segment.isEmpty()) {
                tokens.add(PathToken.spacer(segment));
            }
        }

        tokens.addAll(suffixTokens);

        return List.copyOf(tokens);
    }
    private static Result<Unit> validateNoBlankSpacer(List<PathToken> suffixTokens) {
        for (var token : suffixTokens) {
            if (token instanceof PathToken.Spacer(var text) && text.isBlank()) {
                return ManagementRouteError.blankSpacerText(suffixTokens).result();
            }
        }

        return Result.success(Unit.unit());
    }
    /// The leading run of literal tokens, joined -- the longest static prefix a caller can rely on
    /// (e.g. for filtering routes by area). Always `{`-free by construction, for every constructor:
    /// interleaved routes simply stop the join at their first `Param` token.
    private static String leadingLiteralPrefix(List<PathToken> tokens) {
        var sb = new StringBuilder();

        for (var token : tokens) {
            if (token instanceof PathToken.Spacer(var text)) {
                sb.append('/').append(text);
            } else {
                break;
            }
        }

        return sb.toString();
    }
    private static List<String> paramNamesOf(List<PathToken> tokens) {
        return tokens.stream()
                     .filter(PathToken.Param.class::isInstance)
                     .map(t -> ((PathToken.Param) t).name())
                     .toList();
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
    List<PathToken> tokens() {
        return tokens;
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
