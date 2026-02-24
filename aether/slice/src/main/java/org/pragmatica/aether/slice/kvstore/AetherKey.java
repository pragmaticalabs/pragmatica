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

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Aether KV-Store structured keys for cluster state management
@SuppressWarnings({"JBCT-VO-01", "JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-NAM-01", "JBCT-STY-04"})
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

    /// HTTP route key format:
    /// ```
    /// http-routes/{httpMethod}:{pathPrefix}
    /// ```
    /// Maps HTTP method + path prefix to artifact + slice method for cluster-wide HTTP routing.
    record HttpRouteKey(String httpMethod, String pathPrefix) implements AetherKey {
        private static final String PREFIX = "http-routes/";

        @Override
        public String asString() {
            return PREFIX + httpMethod + ":" + pathPrefix;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static HttpRouteKey httpRouteKey(String httpMethod, String pathPrefix) {
            return new HttpRouteKey(httpMethod.toUpperCase(), normalizePrefix(pathPrefix));
        }

        public static Result<HttpRouteKey> httpRouteKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return HTTP_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                  .result();
            }
            var content = key.substring(PREFIX.length());
            var colonIndex = content.indexOf(':');
            if (colonIndex == - 1) {
                return HTTP_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                  .result();
            }
            var httpMethod = content.substring(0, colonIndex);
            var pathPrefix = content.substring(colonIndex + 1);
            if (httpMethod.isEmpty() || pathPrefix.isEmpty()) {
                return HTTP_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                  .result();
            }
            return success(new HttpRouteKey(httpMethod, pathPrefix));
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

    Fn1<Cause, String> SCHEDULED_TASK_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid scheduled-task key format: %s");
    Fn1<Cause, String> TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid topic-sub key format: %s");
    Fn1<Cause, String> SLICE_TARGET_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice-target key format: %s");
    Fn1<Cause, String> APP_BLUEPRINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid app-blueprint key format: %s");
    Fn1<Cause, String> SLICE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice key format: %s");
    Fn1<Cause, String> ENDPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid endpoint key format: %s");
    Fn1<Cause, String> VERSION_ROUTING_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid version-routing key format: %s");
    Fn1<Cause, String> ROLLING_UPDATE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid rolling-update key format: %s");
    Fn1<Cause, String> PREVIOUS_VERSION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid previous-version key format: %s");
    Fn1<Cause, String> HTTP_ROUTE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid http-routes key format: %s");
    Fn1<Cause, String> ALERT_THRESHOLD_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid alert-threshold key format: %s");
    Fn1<Cause, String> LOG_LEVEL_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid log-level key format: %s");

    Fn1<Cause, String> OBSERVABILITY_DEPTH_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid obs-depth key format: %s");
    Fn1<Cause, String> CONFIG_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid config key format: %s");
}
