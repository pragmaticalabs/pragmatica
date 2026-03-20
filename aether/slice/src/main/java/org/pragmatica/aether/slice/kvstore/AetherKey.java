package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Aether KV-Store structured keys for cluster state management
@Codec
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-NAM-01"})
public sealed interface AetherKey extends StructuredKey {
    /// String representation of the key
    String asString();

    /// Slice target key format:
    /// ```
    /// slice-target/{groupId}:{artifactId}
    /// ```
    /// Stores runtime scaling targets for slices (instance count, current version, owning blueprint).
    record SliceTargetKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "slice-target/";

        @Override
        public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static SliceTargetKey sliceTargetKey(ArtifactBase artifactBase) {
            return new SliceTargetKey(artifactBase);
        }

        public static Result<SliceTargetKey> sliceTargetKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return SLICE_TARGET_KEY_FORMAT_ERROR.apply(key)
                                                    .result();
            }
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart)
                               .map(SliceTargetKey::new);
        }
    }

    /// Application blueprint key format:
    /// ```
    /// app-blueprint/{name}:{version}
    /// ```
    record AppBlueprintKey(BlueprintId blueprintId) implements AetherKey {
        private static final String PREFIX = "app-blueprint/";

        @Override
        public String asString() {
            return PREFIX + blueprintId.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<AppBlueprintKey> appBlueprintKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return APP_BLUEPRINT_KEY_FORMAT_ERROR.apply(key)
                                                     .result();
            }
            var blueprintIdPart = key.substring(PREFIX.length());
            return BlueprintId.blueprintId(blueprintIdPart)
                              .map(AppBlueprintKey::new);
        }

        @SuppressWarnings("JBCT-VO-02")
        public static AppBlueprintKey appBlueprintKey(BlueprintId blueprintId) {
            return new AppBlueprintKey(blueprintId);
        }
    }

    /// Slice-node-key format:
    /// ```
    /// slices/{nodeId}/{groupId}:{artifactId}:{version}
    /// ```
    record SliceNodeKey(Artifact artifact, NodeId nodeId) implements AetherKey {
        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override
        public String asString() {
            return "slices/" + nodeId.id() + "/" + artifact.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static SliceNodeKey sliceNodeKey(Artifact artifact, NodeId nodeId) {
            return new SliceNodeKey(artifact, nodeId);
        }

        public static Result<SliceNodeKey> sliceNodeKey(String key) {
            var parts = key.split("/");
            if (parts.length != 3) {
                return SLICE_KEY_FORMAT_ERROR.apply(key)
                                             .result();
            }
            if (!"slices".equals(parts[0])) {
                return SLICE_KEY_FORMAT_ERROR.apply(key)
                                             .result();
            }
            if (parts[1].isEmpty()) {
                return SLICE_KEY_FORMAT_ERROR.apply(key)
                                             .result();
            }
            return Result.all(Artifact.artifact(parts[2]),
                              NodeId.nodeId(parts[1]))
                         .map(SliceNodeKey::new);
        }
    }

    /// Endpoint-key format (for slice instance endpoints):
    /// ```
    /// endpoints/{groupId}:{artifactId}:{version}/{methodName}:{instanceNumber}
    /// ```
    record EndpointKey(Artifact artifact, MethodName methodName, int instanceNumber) implements AetherKey {
        private static final String PREFIX = "endpoints/";

        @Override
        public String asString() {
            return PREFIX + artifact.asString() + "/" + methodName.name() + ":" + instanceNumber;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<EndpointKey> endpointKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key)
                                                .result();
            }
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key)
                                                .result();
            }
            var artifactPart = content.substring(0, slashIndex);
            var endpointPart = content.substring(slashIndex + 1);
            var colonIndex = endpointPart.lastIndexOf(':');
            if (colonIndex == - 1) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key)
                                                .result();
            }
            var methodNamePart = endpointPart.substring(0, colonIndex);
            var instancePart = endpointPart.substring(colonIndex + 1);
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodNamePart),
                              Number.parseInt(instancePart))
                         .map(EndpointKey::new);
        }
    }

    /// Version routing key format:
    /// ```
    /// version-routing/{groupId}:{artifactId}
    /// ```
    /// Stores routing configuration between old and new versions during rolling updates.
    record VersionRoutingKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "version-routing/";

        @Override
        public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static VersionRoutingKey versionRoutingKey(ArtifactBase artifactBase) {
            return new VersionRoutingKey(artifactBase);
        }

        public static Result<VersionRoutingKey> versionRoutingKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return VERSION_ROUTING_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart)
                               .map(VersionRoutingKey::new);
        }
    }

    /// Rolling update key format:
    /// ```
    /// rolling-update/{updateId}
    /// ```
    /// Stores rolling update state for tracking update progress.
    record RollingUpdateKey(String updateId) implements AetherKey {
        private static final String PREFIX = "rolling-update/";

        @Override
        public String asString() {
            return PREFIX + updateId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<RollingUpdateKey> rollingUpdateKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return ROLLING_UPDATE_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            var updateId = key.substring(PREFIX.length());
            if (updateId.isEmpty()) {
                return ROLLING_UPDATE_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            return success(new RollingUpdateKey(updateId));
        }
    }

    /// Canary deployment key format:
    /// ```
    /// canary-deployment/{canaryId}
    /// ```
    record CanaryDeploymentKey(String canaryId) implements AetherKey {
        private static final String PREFIX = "canary-deployment/";

        @Override
        public String asString() {
            return PREFIX + canaryId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<CanaryDeploymentKey> canaryDeploymentKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return CANARY_DEPLOYMENT_KEY_FORMAT_ERROR.apply(key)
                                                         .result();
            }
            var canaryId = key.substring(PREFIX.length());
            if (canaryId.isEmpty()) {
                return CANARY_DEPLOYMENT_KEY_FORMAT_ERROR.apply(key)
                                                         .result();
            }
            return success(new CanaryDeploymentKey(canaryId));
        }
    }

    /// Blue-green deployment key format:
    /// ```
    /// blue-green-deployment/{deploymentId}
    /// ```
    record BlueGreenDeploymentKey(String deploymentId) implements AetherKey {
        private static final String PREFIX = "blue-green-deployment/";

        @Override
        public String asString() {
            return PREFIX + deploymentId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<BlueGreenDeploymentKey> blueGreenDeploymentKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return BLUE_GREEN_DEPLOYMENT_KEY_FORMAT_ERROR.apply(key)
                                                             .result();
            }
            var id = key.substring(PREFIX.length());
            if (id.isEmpty()) {
                return BLUE_GREEN_DEPLOYMENT_KEY_FORMAT_ERROR.apply(key)
                                                             .result();
            }
            return success(new BlueGreenDeploymentKey(id));
        }
    }

    /// Previous version key format:
    /// ```
    /// previous-version/{groupId}:{artifactId}
    /// ```
    /// Stores the previous version of an artifact before a deployment update.
    /// Used for automatic rollback support.
    record PreviousVersionKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "previous-version/";

        @Override
        public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static PreviousVersionKey previousVersionKey(ArtifactBase artifactBase) {
            return new PreviousVersionKey(artifactBase);
        }

        public static Result<PreviousVersionKey> previousVersionKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return PREVIOUS_VERSION_KEY_FORMAT_ERROR.apply(key)
                                                        .result();
            }
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart)
                               .map(PreviousVersionKey::new);
        }
    }

    /// HTTP node route key format:
    /// ```
    /// http-node-routes/{httpMethod}:{pathPrefix}:{nodeId}
    /// ```
    /// Per-node HTTP route registration. Each node writes only its own key — no read-modify-write races.
    /// Consumers (LB, HttpRouteRegistry) reconstruct node sets from flat keys in-memory.
    record HttpNodeRouteKey(String httpMethod, String pathPrefix, NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "http-node-routes/";

        @Override
        public String asString() {
            return PREFIX + httpMethod + ":" + pathPrefix + ":" + nodeId.id();
        }

        @Override
        public String toString() {
            return asString();
        }

        /// Returns a route-level identity key (method + prefix) for grouping by route.
        public String routeIdentity() {
            return httpMethod + ":" + pathPrefix;
        }

        @SuppressWarnings("JBCT-VO-02")
        public static HttpNodeRouteKey httpNodeRouteKey(String httpMethod, String pathPrefix, NodeId nodeId) {
            return new HttpNodeRouteKey(httpMethod.toUpperCase(), normalizePrefix(pathPrefix), nodeId);
        }

        public static Result<HttpNodeRouteKey> httpNodeRouteKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            var content = key.substring(PREFIX.length());
            // Format: {method}:{path/with/slashes/}:{nodeId}
            // Find first colon (after method), then last colon (before nodeId)
            var firstColon = content.indexOf(':');
            var lastColon = content.lastIndexOf(':');
            if (firstColon == - 1 || lastColon == - 1 || firstColon == lastColon) {
                return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            var httpMethod = content.substring(0, firstColon);
            var pathPrefix = content.substring(firstColon + 1, lastColon);
            var nodeIdPart = content.substring(lastColon + 1);
            if (httpMethod.isEmpty() || pathPrefix.isEmpty() || nodeIdPart.isEmpty()) {
                return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            return NodeId.nodeId(nodeIdPart)
                         .map(nodeId -> new HttpNodeRouteKey(httpMethod, pathPrefix, nodeId));
        }

        private static String normalizePrefix(String path) {
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

    /// Log level key format:
    /// ```
    /// log-level/{loggerName}
    /// ```
    /// Stores runtime log level overrides per logger.
    record LogLevelKey(String loggerName) implements AetherKey {
        private static final String PREFIX = "log-level/";

        @Override
        public String asString() {
            return PREFIX + loggerName;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static LogLevelKey forLogger(String loggerName) {
            return new LogLevelKey(loggerName);
        }

        public static Result<LogLevelKey> logLevelKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return LOG_LEVEL_KEY_FORMAT_ERROR.apply(key)
                                                 .result();
            }
            var loggerName = key.substring(PREFIX.length());
            if (loggerName.isEmpty()) {
                return LOG_LEVEL_KEY_FORMAT_ERROR.apply(key)
                                                 .result();
            }
            return success(new LogLevelKey(loggerName));
        }
    }

    /// Observability depth key format:
    /// ```
    /// obs-depth/{artifactBase}/{methodName}
    /// ```
    /// Stores per-method observability depth threshold configuration.
    record ObservabilityDepthKey(String artifactBase, String methodName) implements AetherKey {
        private static final String PREFIX = "obs-depth/";

        @Override
        public String asString() {
            return PREFIX + artifactBase + "/" + methodName;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ObservabilityDepthKey observabilityDepthKey(String artifactBase, String methodName) {
            return new ObservabilityDepthKey(artifactBase, methodName);
        }

        public static Result<ObservabilityDepthKey> observabilityDepthKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return OBSERVABILITY_DEPTH_KEY_FORMAT_ERROR.apply(key)
                                                           .result();
            }
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return OBSERVABILITY_DEPTH_KEY_FORMAT_ERROR.apply(key)
                                                           .result();
            }
            var artifactBase = content.substring(0, slashIndex);
            var methodName = content.substring(slashIndex + 1);
            return success(new ObservabilityDepthKey(artifactBase, methodName));
        }
    }

    /// Alert threshold key format:
    /// ```
    /// alert-threshold/{metricName}
    /// ```
    /// Stores alert threshold configuration for metrics.
    record AlertThresholdKey(String metricName) implements AetherKey {
        private static final String PREFIX = "alert-threshold/";

        @Override
        public String asString() {
            return PREFIX + metricName;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<AlertThresholdKey> alertThresholdKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            var metricName = key.substring(PREFIX.length());
            if (metricName.isEmpty()) {
                return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            return success(new AlertThresholdKey(metricName));
        }
    }

    /// Topic subscription key format:
    /// ```
    /// topic-sub/{topicName}/{groupId}:{artifactId}:{version}/{methodName}
    /// ```
    /// Maps topic subscriptions to slice method handlers for pub/sub messaging.
    record TopicSubscriptionKey(String topicName, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "topic-sub/";

        @Override
        public String asString() {
            return PREFIX + topicName + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static TopicSubscriptionKey topicSubscriptionKey(String topicName,
                                                                Artifact artifact,
                                                                MethodName methodName) {
            return new TopicSubscriptionKey(topicName, artifact, methodName);
        }

        public static Result<TopicSubscriptionKey> topicSubscriptionKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key)
                                                          .result();
            }
            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');
            if (firstSlash == - 1) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key)
                                                          .result();
            }
            var topicName = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');
            if (lastSlash == - 1) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key)
                                                          .result();
            }
            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);
            if (topicName.isEmpty() || methodPart.isEmpty()) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key)
                                                          .result();
            }
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
                         .map((artifact, method) -> new TopicSubscriptionKey(topicName, artifact, method));
        }
    }

    /// Scheduled task key format:
    /// ```
    /// scheduled-task/{configSection}/{groupId}:{artifactId}:{version}/{methodName}
    /// ```
    /// Maps scheduled task configuration to slice method handlers for periodic invocation.
    record ScheduledTaskKey(String configSection, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "scheduled-task/";

        @Override
        public String asString() {
            return PREFIX + configSection + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ScheduledTaskKey scheduledTaskKey(String configSection,
                                                        Artifact artifact,
                                                        MethodName methodName) {
            return new ScheduledTaskKey(configSection, artifact, methodName);
        }

        public static Result<ScheduledTaskKey> scheduledTaskKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');
            if (firstSlash == - 1) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            var configSection = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');
            if (lastSlash == - 1) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);
            if (configSection.isEmpty() || methodPart.isEmpty()) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
                         .map((artifact, method) -> new ScheduledTaskKey(configSection, artifact, method));
        }
    }

    /// Scheduled task state key format:
    /// ```
    /// scheduled-task-state/{configSection}/{groupId}:{artifactId}:{version}/{methodName}
    /// ```
    /// Stores execution state for scheduled tasks (last run, next fire, failures).
    record ScheduledTaskStateKey(String configSection, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "scheduled-task-state/";

        @Override
        public String asString() {
            return PREFIX + configSection + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ScheduledTaskStateKey scheduledTaskStateKey(String configSection,
                                                                  Artifact artifact,
                                                                  MethodName methodName) {
            return new ScheduledTaskStateKey(configSection, artifact, methodName);
        }

        public static Result<ScheduledTaskStateKey> scheduledTaskStateKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key)
                                                            .result();
            }
            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');
            if (firstSlash == - 1) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key)
                                                            .result();
            }
            var configSection = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');
            if (lastSlash == - 1) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key)
                                                            .result();
            }
            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);
            if (configSection.isEmpty() || methodPart.isEmpty()) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key)
                                                            .result();
            }
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
                         .map((artifact, method) -> new ScheduledTaskStateKey(configSection, artifact, method));
        }
    }

    /// Node lifecycle key format:
    /// ```
    /// node-lifecycle/{nodeId}
    /// ```
    /// Stores the lifecycle state of a cluster node (ON_DUTY, DRAINING, DECOMMISSIONED, SHUTTING_DOWN).
    record NodeLifecycleKey(NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "node-lifecycle/";

        @Override
        public String asString() {
            return PREFIX + nodeId.id();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static NodeLifecycleKey nodeLifecycleKey(NodeId nodeId) {
            return new NodeLifecycleKey(nodeId);
        }

        public static Result<NodeLifecycleKey> nodeLifecycleKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return NODE_LIFECYCLE_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            var nodeIdPart = key.substring(PREFIX.length());
            if (nodeIdPart.isEmpty()) {
                return NODE_LIFECYCLE_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            return NodeId.nodeId(nodeIdPart)
                         .map(NodeLifecycleKey::new);
        }
    }

    /// Config key format:
    /// ```
    /// config/{key}                    — cluster-wide
    /// config/node/{nodeId}/{key}      — node-specific
    /// ```
    /// Stores dynamic configuration values in the cluster KV store.
    record ConfigKey(String key, Option<NodeId> nodeScope) implements AetherKey {
        private static final String CLUSTER_PREFIX = "config/";
        private static final String NODE_PREFIX = "config/node/";

        @Override
        public String asString() {
            return nodeScope.fold(() -> CLUSTER_PREFIX + key, nodeId -> NODE_PREFIX + nodeId.id() + "/" + key);
        }

        @Override
        public String toString() {
            return asString();
        }

        public boolean isClusterWide() {
            return nodeScope.isEmpty();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ConfigKey forKey(String key) {
            return new ConfigKey(key, none());
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ConfigKey forKey(String key, NodeId nodeId) {
            return new ConfigKey(key, some(nodeId));
        }

        public static Result<ConfigKey> configKey(String raw) {
            if (raw.startsWith(NODE_PREFIX)) {
                var content = raw.substring(NODE_PREFIX.length());
                var slashIndex = content.indexOf('/');
                if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                    return CONFIG_KEY_FORMAT_ERROR.apply(raw)
                                                  .result();
                }
                var nodeIdPart = content.substring(0, slashIndex);
                var keyPart = content.substring(slashIndex + 1);
                return NodeId.nodeId(nodeIdPart)
                             .map(nodeId -> new ConfigKey(keyPart,
                                                          some(nodeId)));
            }
            if (raw.startsWith(CLUSTER_PREFIX)) {
                var keyPart = raw.substring(CLUSTER_PREFIX.length());
                if (keyPart.isEmpty()) {
                    return CONFIG_KEY_FORMAT_ERROR.apply(raw)
                                                  .result();
                }
                return success(new ConfigKey(keyPart, none()));
            }
            return CONFIG_KEY_FORMAT_ERROR.apply(raw)
                                          .result();
        }
    }

    /// Worker slice directive key format:
    /// ```
    /// worker-directive/{groupId}:{artifactId}:{version}
    /// worker-directive/{communityId}/{groupId}:{artifactId}:{version}
    /// ```
    /// CDM writes directives for worker pools to load/unload slices.
    /// The optional communityId enables per-community directives.
    record WorkerSliceDirectiveKey(Artifact artifact, Option<String> communityId) implements AetherKey {
        private static final String PREFIX = "worker-directive/";

        @Override
        public String asString() {
            return communityId.map(c -> PREFIX + c + "/" + artifact.asString())
                              .or(PREFIX + artifact.asString());
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static WorkerSliceDirectiveKey workerSliceDirectiveKey(Artifact artifact) {
            return new WorkerSliceDirectiveKey(artifact, Option.none());
        }

        @SuppressWarnings("JBCT-VO-02")
        public static WorkerSliceDirectiveKey workerSliceDirectiveKey(Artifact artifact, String communityId) {
            return new WorkerSliceDirectiveKey(artifact, Option.option(communityId));
        }

        public static Result<WorkerSliceDirectiveKey> workerSliceDirectiveKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return WORKER_DIRECTIVE_KEY_FORMAT_ERROR.apply(key)
                                                        .result();
            }
            var rest = key.substring(PREFIX.length());
            var slashIndex = rest.indexOf('/');
            if (slashIndex >= 0) {
                return parseCommunityKey(rest, slashIndex);
            }
            return Artifact.artifact(rest)
                           .map(art -> new WorkerSliceDirectiveKey(art,
                                                                   Option.none()));
        }

        private static Result<WorkerSliceDirectiveKey> parseCommunityKey(String rest, int slashIndex) {
            var communityPart = rest.substring(0, slashIndex);
            var artifactPart = rest.substring(slashIndex + 1);
            return Artifact.artifact(artifactPart)
                           .map(art -> new WorkerSliceDirectiveKey(art,
                                                                   Option.some(communityPart)));
        }
    }

    /// Activation directive key format:
    /// ```
    /// activation/{nodeId}
    /// ```
    /// CDM writes activation directives for joining nodes (core or worker role).
    record ActivationDirectiveKey(NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "activation/";

        @Override
        public String asString() {
            return PREFIX + nodeId.id();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ActivationDirectiveKey activationDirectiveKey(NodeId nodeId) {
            return new ActivationDirectiveKey(nodeId);
        }

        public static Result<ActivationDirectiveKey> activationDirectiveKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR.apply(key)
                                                            .result();
            }
            var nodeIdPart = key.substring(PREFIX.length());
            if (nodeIdPart.isEmpty()) {
                return ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR.apply(key)
                                                            .result();
            }
            return NodeId.nodeId(nodeIdPart)
                         .map(ActivationDirectiveKey::new);
        }
    }

    /// Blueprint resources key format:
    /// ```
    /// blueprint-resources/{blueprintId}
    /// ```
    /// Stores the raw resources.toml content for a deployed blueprint.
    record BlueprintResourcesKey(BlueprintId blueprintId) implements AetherKey {
        private static final String PREFIX = "blueprint-resources/";

        @Override
        public String asString() {
            return PREFIX + blueprintId.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static BlueprintResourcesKey blueprintResourcesKey(BlueprintId blueprintId) {
            return new BlueprintResourcesKey(blueprintId);
        }

        public static Result<BlueprintResourcesKey> blueprintResourcesKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return BLUEPRINT_RESOURCES_KEY_FORMAT_ERROR.apply(key)
                                                           .result();
            }
            var idPart = key.substring(PREFIX.length());
            return BlueprintId.blueprintId(idPart)
                              .map(BlueprintResourcesKey::new);
        }
    }

    /// Schema version key format:
    /// ```
    /// schema-version/{datasourceName}
    /// ```
    /// Tracks the current schema version for a datasource.
    record SchemaVersionKey(String datasourceName) implements AetherKey {
        private static final String PREFIX = "schema-version/";

        @Override
        public String asString() {
            return PREFIX + datasourceName;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static SchemaVersionKey schemaVersionKey(String datasourceName) {
            return new SchemaVersionKey(datasourceName);
        }

        public static Result<SchemaVersionKey> schemaVersionKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {
                return SCHEMA_VERSION_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            var name = key.substring(PREFIX.length());
            if (name.isEmpty()) {
                return SCHEMA_VERSION_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            return success(new SchemaVersionKey(name));
        }
    }

    /// Schema migration lock key format:
    /// ```
    /// schema-lock/{datasourceName}
    /// ```
    /// Distributed lock for schema migration execution (prevents concurrent migrations).
    record SchemaMigrationLockKey(String datasourceName) implements AetherKey {
        private static final String PREFIX = "schema-lock/";

        @Override
        public String asString() {
            return PREFIX + datasourceName;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static SchemaMigrationLockKey schemaMigrationLockKey(String datasourceName) {
            return new SchemaMigrationLockKey(datasourceName);
        }

        public static Result<SchemaMigrationLockKey> schemaMigrationLockKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {
                return SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR.apply(key)
                                                             .result();
            }
            var name = key.substring(PREFIX.length());
            if (name.isEmpty()) {
                return SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR.apply(key)
                                                             .result();
            }
            return success(new SchemaMigrationLockKey(name));
        }
    }

    /// Gossip key rotation key format:
    /// ```
    /// gossip-key-rotation
    /// ```
    /// Stores the current gossip encryption key rotation state.
    /// Leader initiates rotation; all nodes watch for updates.
    record GossipKeyRotationKey() implements AetherKey {
        private static final String KEY = "gossip-key-rotation";

        @Override
        public String asString() {
            return KEY;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static GossipKeyRotationKey gossipKeyRotationKey() {
            return new GossipKeyRotationKey();
        }

        public static Result<GossipKeyRotationKey> gossipKeyRotationKey(String key) {
            if (!KEY.equals(key)) {
                return GOSSIP_KEY_ROTATION_KEY_FORMAT_ERROR.apply(key)
                                                           .result();
            }
            return success(new GossipKeyRotationKey());
        }
    }

    /// Governor announcement key format:
    /// ```
    /// governor-announcement/{communityId}
    /// ```
    /// Governors write their identity to consensus so core nodes and GovernorDiscovery
    /// can track active worker communities.
    record GovernorAnnouncementKey(String communityId) implements AetherKey {
        private static final String PREFIX = "governor-announcement/";

        @Override
        public String asString() {
            return PREFIX + communityId;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static GovernorAnnouncementKey forCommunity(String communityId) {
            return new GovernorAnnouncementKey(communityId);
        }

        public static Result<GovernorAnnouncementKey> governorAnnouncementKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return GOVERNOR_ANNOUNCEMENT_KEY_FORMAT_ERROR.apply(key)
                                                             .result();
            }
            var communityId = key.substring(PREFIX.length());
            if (communityId.isEmpty()) {
                return GOVERNOR_ANNOUNCEMENT_KEY_FORMAT_ERROR.apply(key)
                                                             .result();
            }
            return success(new GovernorAnnouncementKey(communityId));
        }
    }

    /// A/B test key format:
    /// ```
    /// ab-test/{testId}
    /// ```
    /// Stores A/B test deployment state.
    record ABTestKey(String testId) implements AetherKey {
        private static final String PREFIX = "ab-test/";

        @Override
        public String asString() {
            return PREFIX + testId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<ABTestKey> abTestKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return AB_TEST_KEY_FORMAT_ERROR.apply(key)
                                               .result();
            }
            var id = key.substring(PREFIX.length());
            if (id.isEmpty()) {
                return AB_TEST_KEY_FORMAT_ERROR.apply(key)
                                               .result();
            }
            return success(new ABTestKey(id));
        }
    }

    /// A/B test routing key format:
    /// ```
    /// ab-test-routing/{groupId}:{artifactId}
    /// ```
    /// Stores routing configuration for A/B test traffic splitting.
    record ABTestRoutingKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "ab-test-routing/";

        @Override
        public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ABTestRoutingKey abTestRoutingKey(ArtifactBase artifactBase) {
            return new ABTestRoutingKey(artifactBase);
        }

        public static Result<ABTestRoutingKey> abTestRoutingKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return AB_TEST_ROUTING_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart)
                               .map(ABTestRoutingKey::new);
        }
    }

    /// Node-artifact key format:
    /// ```
    /// node-artifact/{nodeId}/{groupId}:{artifactId}:{version}
    /// ```
    /// Compound key combining deployment state and endpoint registration per node per artifact.
    /// Replaces separate EndpointKey and SliceNodeKey — single writer (hosting node), no races.
    record NodeArtifactKey(NodeId nodeId, Artifact artifact) implements AetherKey {
        private static final String PREFIX = "node-artifact/";

        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override
        public String asString() {
            return PREFIX + nodeId.id() + "/" + artifact.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static NodeArtifactKey nodeArtifactKey(NodeId nodeId, Artifact artifact) {
            return new NodeArtifactKey(nodeId, artifact);
        }

        public static Result<NodeArtifactKey> nodeArtifactKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return NODE_ARTIFACT_KEY_FORMAT_ERROR.apply(key)
                                                     .result();
            }
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return NODE_ARTIFACT_KEY_FORMAT_ERROR.apply(key)
                                                     .result();
            }
            var nodeIdPart = content.substring(0, slashIndex);
            var artifactPart = content.substring(slashIndex + 1);
            return Result.all(NodeId.nodeId(nodeIdPart),
                              Artifact.artifact(artifactPart))
                         .map((nid, art) -> new NodeArtifactKey(nid, art));
        }
    }

    /// Node-routes key format:
    /// ```
    /// node-routes/{nodeId}/{groupId}:{artifactId}:{version}
    /// ```
    /// HTTP route registrations grouped by artifact per node.
    /// Single writer (hosting node) — one entry per artifact per node replaces N route entries.
    record NodeRoutesKey(NodeId nodeId, Artifact artifact) implements AetherKey {
        private static final String PREFIX = "node-routes/";

        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override
        public String asString() {
            return PREFIX + nodeId.id() + "/" + artifact.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static NodeRoutesKey nodeRoutesKey(NodeId nodeId, Artifact artifact) {
            return new NodeRoutesKey(nodeId, artifact);
        }

        public static Result<NodeRoutesKey> nodeRoutesKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return NODE_ROUTES_KEY_FORMAT_ERROR.apply(key)
                                                   .result();
            }
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return NODE_ROUTES_KEY_FORMAT_ERROR.apply(key)
                                                   .result();
            }
            var nodeIdPart = content.substring(0, slashIndex);
            var artifactPart = content.substring(slashIndex + 1);
            return Result.all(NodeId.nodeId(nodeIdPart),
                              Artifact.artifact(artifactPart))
                         .map((nid, art) -> new NodeRoutesKey(nid, art));
        }
    }

    Fn1<Cause, String> NODE_ARTIFACT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid node-artifact key format: %s");
    Fn1<Cause, String> NODE_ROUTES_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid node-routes key format: %s");
    Fn1<Cause, String> GOVERNOR_ANNOUNCEMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid governor-announcement key format: %s");
    Fn1<Cause, String> GOSSIP_KEY_ROTATION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid gossip-key-rotation key format: %s");
    Fn1<Cause, String> SCHEDULED_TASK_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid scheduled-task key format: %s");
    Fn1<Cause, String> SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid scheduled-task-state key format: %s");
    Fn1<Cause, String> TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid topic-sub key format: %s");
    Fn1<Cause, String> SLICE_TARGET_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice-target key format: %s");
    Fn1<Cause, String> APP_BLUEPRINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid app-blueprint key format: %s");
    Fn1<Cause, String> SLICE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice key format: %s");
    Fn1<Cause, String> ENDPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid endpoint key format: %s");
    Fn1<Cause, String> VERSION_ROUTING_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid version-routing key format: %s");
    Fn1<Cause, String> ROLLING_UPDATE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid rolling-update key format: %s");
    Fn1<Cause, String> CANARY_DEPLOYMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid canary-deployment key format: %s");
    Fn1<Cause, String> BLUE_GREEN_DEPLOYMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid blue-green-deployment key format: %s");
    Fn1<Cause, String> PREVIOUS_VERSION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid previous-version key format: %s");
    Fn1<Cause, String> HTTP_NODE_ROUTE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid http-node-routes key format: %s");
    Fn1<Cause, String> ALERT_THRESHOLD_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid alert-threshold key format: %s");
    Fn1<Cause, String> LOG_LEVEL_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid log-level key format: %s");

    Fn1<Cause, String> OBSERVABILITY_DEPTH_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid obs-depth key format: %s");
    Fn1<Cause, String> CONFIG_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid config key format: %s");
    Fn1<Cause, String> NODE_LIFECYCLE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid node-lifecycle key format: %s");
    Fn1<Cause, String> WORKER_DIRECTIVE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid worker-directive key format: %s");
    Fn1<Cause, String> ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid activation key format: %s");
    Fn1<Cause, String> BLUEPRINT_RESOURCES_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid blueprint-resources key format: %s");
    Fn1<Cause, String> SCHEMA_VERSION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid schema-version key format: %s");
    Fn1<Cause, String> SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid schema-lock key format: %s");
    Fn1<Cause, String> AB_TEST_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid ab-test key format: %s");
    Fn1<Cause, String> AB_TEST_ROUTING_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid ab-test-routing key format: %s");
}
