package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Aether KV-Store structured keys for cluster state management
@Codec@CodecFor(MethodName.class) @SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-NAM-01"}) public sealed interface AetherKey extends StructuredKey {
    String asString();

    record SliceTargetKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "slice-target/";

        @Override public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static SliceTargetKey sliceTargetKey(ArtifactBase artifactBase) {
            return new SliceTargetKey(artifactBase);
        }

        public static Result<SliceTargetKey> sliceTargetKey(String key) {
            if (!key.startsWith(PREFIX)) {return SLICE_TARGET_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart).map(SliceTargetKey::new);
        }
    }

    record AppBlueprintKey(BlueprintId blueprintId) implements AetherKey {
        private static final String PREFIX = "app-blueprint/";

        @Override public String asString() {
            return PREFIX + blueprintId.asString();
        }

        @Override public String toString() {
            return asString();
        }

        public static Result<AppBlueprintKey> appBlueprintKey(String key) {
            if (!key.startsWith(PREFIX)) {return APP_BLUEPRINT_KEY_FORMAT_ERROR.apply(key).result();}
            var blueprintIdPart = key.substring(PREFIX.length());
            return BlueprintId.blueprintId(blueprintIdPart).map(AppBlueprintKey::new);
        }

        @SuppressWarnings("JBCT-VO-02") public static AppBlueprintKey appBlueprintKey(BlueprintId blueprintId) {
            return new AppBlueprintKey(blueprintId);
        }
    }

    record SliceNodeKey(Artifact artifact, NodeId nodeId) implements AetherKey {
        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override public String asString() {
            return "slices/" + nodeId.id() + "/" + artifact.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static SliceNodeKey sliceNodeKey(Artifact artifact, NodeId nodeId) {
            return new SliceNodeKey(artifact, nodeId);
        }

        public static Result<SliceNodeKey> sliceNodeKey(String key) {
            var parts = key.split("/");
            if (parts.length != 3) {return SLICE_KEY_FORMAT_ERROR.apply(key).result();}
            if (!"slices".equals(parts[0])) {return SLICE_KEY_FORMAT_ERROR.apply(key).result();}
            if (parts[1].isEmpty()) {return SLICE_KEY_FORMAT_ERROR.apply(key).result();}
            return Result.all(Artifact.artifact(parts[2]), NodeId.nodeId(parts[1])).map(SliceNodeKey::new);
        }
    }

    record EndpointKey(Artifact artifact, MethodName methodName, int instanceNumber) implements AetherKey {
        private static final String PREFIX = "endpoints/";

        @Override public String asString() {
            return PREFIX + artifact.asString() + "/" + methodName.name() + ":" + instanceNumber;
        }

        @Override public String toString() {
            return asString();
        }

        public static Result<EndpointKey> endpointKey(String key) {
            if (!key.startsWith(PREFIX)) {return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1) {return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactPart = content.substring(0, slashIndex);
            var endpointPart = content.substring(slashIndex + 1);
            var colonIndex = endpointPart.lastIndexOf(':');
            if (colonIndex == - 1) {return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();}
            var methodNamePart = endpointPart.substring(0, colonIndex);
            var instancePart = endpointPart.substring(colonIndex + 1);
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodNamePart),
                              Number.parseInt(instancePart))
            .map(EndpointKey::new);
        }
    }

    record VersionRoutingKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "version-routing/";

        @Override public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static VersionRoutingKey versionRoutingKey(ArtifactBase artifactBase) {
            return new VersionRoutingKey(artifactBase);
        }

        public static Result<VersionRoutingKey> versionRoutingKey(String key) {
            if (!key.startsWith(PREFIX)) {return VERSION_ROUTING_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart).map(VersionRoutingKey::new);
        }
    }

    record DeploymentKey(String deploymentId) implements AetherKey {
        private static final String PREFIX = "deployment/";

        @Override public String asString() {
            return PREFIX + deploymentId;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static DeploymentKey deploymentKey(String deploymentId) {
            return new DeploymentKey(deploymentId);
        }

        public static Result<DeploymentKey> parseDeploymentKey(String key) {
            if (!key.startsWith(PREFIX)) {return DEPLOYMENT_KEY_FORMAT_ERROR.apply(key).result();}
            var id = key.substring(PREFIX.length());
            if (id.isEmpty()) {return DEPLOYMENT_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new DeploymentKey(id));
        }
    }

    record PreviousVersionKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "previous-version/";

        @Override public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static PreviousVersionKey previousVersionKey(ArtifactBase artifactBase) {
            return new PreviousVersionKey(artifactBase);
        }

        public static Result<PreviousVersionKey> previousVersionKey(String key) {
            if (!key.startsWith(PREFIX)) {return PREVIOUS_VERSION_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart).map(PreviousVersionKey::new);
        }
    }

    record HttpNodeRouteKey(String httpMethod, String pathPrefix, NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "http-node-routes/";

        @Override public String asString() {
            return PREFIX + httpMethod + ":" + pathPrefix + ":" + nodeId.id();
        }

        @Override public String toString() {
            return asString();
        }

        public String routeIdentity() {
            return httpMethod + ":" + pathPrefix;
        }

        @SuppressWarnings("JBCT-VO-02") public static HttpNodeRouteKey httpNodeRouteKey(String httpMethod,
                                                                                        String pathPrefix,
                                                                                        NodeId nodeId) {
            return new HttpNodeRouteKey(httpMethod.toUpperCase(), normalizePrefix(pathPrefix), nodeId);
        }

        public static Result<HttpNodeRouteKey> httpNodeRouteKey(String key) {
            if (!key.startsWith(PREFIX)) {return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var firstColon = content.indexOf(':');
            var lastColon = content.lastIndexOf(':');
            if (firstColon == - 1 || lastColon == - 1 || firstColon == lastColon) {return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                .result();}
            var httpMethod = content.substring(0, firstColon);
            var pathPrefix = content.substring(firstColon + 1, lastColon);
            var nodeIdPart = content.substring(lastColon + 1);
            if (httpMethod.isEmpty() || pathPrefix.isEmpty() || nodeIdPart.isEmpty()) {return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                    .result();}
            return NodeId.nodeId(nodeIdPart).map(nodeId -> new HttpNodeRouteKey(httpMethod, pathPrefix, nodeId));
        }

        private static String normalizePrefix(String path) {
            if (path == null || path.isBlank()) {return "/";}
            var normalized = path.strip();
            if (!normalized.startsWith("/")) {normalized = "/" + normalized;}
            if (!normalized.endsWith("/")) {normalized = normalized + "/";}
            return normalized;
        }
    }

    record LogLevelKey(String loggerName) implements AetherKey {
        private static final String PREFIX = "log-level/";

        @Override public String asString() {
            return PREFIX + loggerName;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static LogLevelKey forLogger(String loggerName) {
            return new LogLevelKey(loggerName);
        }

        public static Result<LogLevelKey> logLevelKey(String key) {
            if (!key.startsWith(PREFIX)) {return LOG_LEVEL_KEY_FORMAT_ERROR.apply(key).result();}
            var loggerName = key.substring(PREFIX.length());
            if (loggerName.isEmpty()) {return LOG_LEVEL_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new LogLevelKey(loggerName));
        }
    }

    record ObservabilityDepthKey(String artifactBase, String methodName) implements AetherKey {
        private static final String PREFIX = "obs-depth/";

        @Override public String asString() {
            return PREFIX + artifactBase + "/" + methodName;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static ObservabilityDepthKey observabilityDepthKey(String artifactBase,
                                                                                                  String methodName) {
            return new ObservabilityDepthKey(artifactBase, methodName);
        }

        public static Result<ObservabilityDepthKey> observabilityDepthKey(String key) {
            if (!key.startsWith(PREFIX)) {return OBSERVABILITY_DEPTH_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return OBSERVABILITY_DEPTH_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                              .result();}
            var artifactBase = content.substring(0, slashIndex);
            var methodName = content.substring(slashIndex + 1);
            return success(new ObservabilityDepthKey(artifactBase, methodName));
        }
    }

    record AlertThresholdKey(String metricName) implements AetherKey {
        private static final String PREFIX = "alert-threshold/";

        @Override public String asString() {
            return PREFIX + metricName;
        }

        @Override public String toString() {
            return asString();
        }

        public static Result<AlertThresholdKey> alertThresholdKey(String key) {
            if (!key.startsWith(PREFIX)) {return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key).result();}
            var metricName = key.substring(PREFIX.length());
            if (metricName.isEmpty()) {return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new AlertThresholdKey(metricName));
        }
    }

    record TopicSubscriptionKey(String topicName, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "topic-sub/";

        @Override public String asString() {
            return PREFIX + topicName + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static TopicSubscriptionKey topicSubscriptionKey(String topicName,
                                                                                                Artifact artifact,
                                                                                                MethodName methodName) {
            return new TopicSubscriptionKey(topicName, artifact, methodName);
        }

        public static Result<TopicSubscriptionKey> topicSubscriptionKey(String key) {
            if (!key.startsWith(PREFIX)) {return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');
            if (firstSlash == - 1) {return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();}
            var topicName = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');
            if (lastSlash == - 1) {return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);
            if (topicName.isEmpty() || methodPart.isEmpty()) {return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key)
                                                                                                              .result();}
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
            .map((artifact, method) -> new TopicSubscriptionKey(topicName, artifact, method));
        }
    }

    record ScheduledTaskKey(String configSection, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "scheduled-task/";

        @Override public String asString() {
            return PREFIX + configSection + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static ScheduledTaskKey scheduledTaskKey(String configSection,
                                                                                        Artifact artifact,
                                                                                        MethodName methodName) {
            return new ScheduledTaskKey(configSection, artifact, methodName);
        }

        public static Result<ScheduledTaskKey> scheduledTaskKey(String key) {
            if (!key.startsWith(PREFIX)) {return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');
            if (firstSlash == - 1) {return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key).result();}
            var configSection = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');
            if (lastSlash == - 1) {return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);
            if (configSection.isEmpty() || methodPart.isEmpty()) {return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key)
                                                                                                              .result();}
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
            .map((artifact, method) -> new ScheduledTaskKey(configSection, artifact, method));
        }
    }

    record ScheduledTaskStateKey(String configSection, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "scheduled-task-state/";

        @Override public String asString() {
            return PREFIX + configSection + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static ScheduledTaskStateKey scheduledTaskStateKey(String configSection,
                                                                                                  Artifact artifact,
                                                                                                  MethodName methodName) {
            return new ScheduledTaskStateKey(configSection, artifact, methodName);
        }

        public static Result<ScheduledTaskStateKey> scheduledTaskStateKey(String key) {
            if (!key.startsWith(PREFIX)) {return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');
            if (firstSlash == - 1) {return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key).result();}
            var configSection = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');
            if (lastSlash == - 1) {return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);
            if (configSection.isEmpty() || methodPart.isEmpty()) {return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key)
                                                                                                                    .result();}
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
            .map((artifact, method) -> new ScheduledTaskStateKey(configSection, artifact, method));
        }
    }

    record NodeLifecycleKey(NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "node-lifecycle/";

        @Override public String asString() {
            return PREFIX + nodeId.id();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static NodeLifecycleKey nodeLifecycleKey(NodeId nodeId) {
            return new NodeLifecycleKey(nodeId);
        }

        public static Result<NodeLifecycleKey> nodeLifecycleKey(String key) {
            if (!key.startsWith(PREFIX)) {return NODE_LIFECYCLE_KEY_FORMAT_ERROR.apply(key).result();}
            var nodeIdPart = key.substring(PREFIX.length());
            if (nodeIdPart.isEmpty()) {return NODE_LIFECYCLE_KEY_FORMAT_ERROR.apply(key).result();}
            return NodeId.nodeId(nodeIdPart).map(NodeLifecycleKey::new);
        }
    }

    record ConfigKey(String key, Option<NodeId> nodeScope) implements AetherKey {
        private static final String CLUSTER_PREFIX = "config/";

        private static final String NODE_PREFIX = "config/node/";

        @Override public String asString() {
            return nodeScope.fold(() -> CLUSTER_PREFIX + key, nodeId -> NODE_PREFIX + nodeId.id() + "/" + key);
        }

        @Override public String toString() {
            return asString();
        }

        public boolean isClusterWide() {
            return nodeScope.isEmpty();
        }

        @SuppressWarnings("JBCT-VO-02") public static ConfigKey forKey(String key) {
            return new ConfigKey(key, none());
        }

        @SuppressWarnings("JBCT-VO-02") public static ConfigKey forKey(String key, NodeId nodeId) {
            return new ConfigKey(key, some(nodeId));
        }

        public static Result<ConfigKey> configKey(String raw) {
            if (raw.startsWith(NODE_PREFIX)) {
                var content = raw.substring(NODE_PREFIX.length());
                var slashIndex = content.indexOf('/');
                if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return CONFIG_KEY_FORMAT_ERROR.apply(raw)
                                                                                                                                     .result();}
                var nodeIdPart = content.substring(0, slashIndex);
                var keyPart = content.substring(slashIndex + 1);
                return NodeId.nodeId(nodeIdPart).map(nodeId -> new ConfigKey(keyPart, some(nodeId)));
            }
            if (raw.startsWith(CLUSTER_PREFIX)) {
                var keyPart = raw.substring(CLUSTER_PREFIX.length());
                if (keyPart.isEmpty()) {return CONFIG_KEY_FORMAT_ERROR.apply(raw).result();}
                return success(new ConfigKey(keyPart, none()));
            }
            return CONFIG_KEY_FORMAT_ERROR.apply(raw).result();
        }
    }

    record WorkerSliceDirectiveKey(Artifact artifact, Option<String> communityId) implements AetherKey {
        private static final String PREFIX = "worker-directive/";

        @Override public String asString() {
            return communityId.map(c -> PREFIX + c + "/" + artifact.asString()).or(PREFIX + artifact.asString());
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static WorkerSliceDirectiveKey workerSliceDirectiveKey(Artifact artifact) {
            return new WorkerSliceDirectiveKey(artifact, Option.none());
        }

        @SuppressWarnings("JBCT-VO-02") public static WorkerSliceDirectiveKey workerSliceDirectiveKey(Artifact artifact,
                                                                                                      String communityId) {
            return new WorkerSliceDirectiveKey(artifact, Option.option(communityId));
        }

        public static Result<WorkerSliceDirectiveKey> workerSliceDirectiveKey(String key) {
            if (!key.startsWith(PREFIX)) {return WORKER_DIRECTIVE_KEY_FORMAT_ERROR.apply(key).result();}
            var rest = key.substring(PREFIX.length());
            var slashIndex = rest.indexOf('/');
            if (slashIndex >= 0) {return parseCommunityKey(rest, slashIndex);}
            return Artifact.artifact(rest).map(art -> new WorkerSliceDirectiveKey(art, Option.none()));
        }

        private static Result<WorkerSliceDirectiveKey> parseCommunityKey(String rest, int slashIndex) {
            var communityPart = rest.substring(0, slashIndex);
            var artifactPart = rest.substring(slashIndex + 1);
            return Artifact.artifact(artifactPart)
                                    .map(art -> new WorkerSliceDirectiveKey(art,
                                                                            Option.some(communityPart)));
        }
    }

    record ActivationDirectiveKey(NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "activation/";

        @Override public String asString() {
            return PREFIX + nodeId.id();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static ActivationDirectiveKey activationDirectiveKey(NodeId nodeId) {
            return new ActivationDirectiveKey(nodeId);
        }

        public static Result<ActivationDirectiveKey> activationDirectiveKey(String key) {
            if (!key.startsWith(PREFIX)) {return ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR.apply(key).result();}
            var nodeIdPart = key.substring(PREFIX.length());
            if (nodeIdPart.isEmpty()) {return ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR.apply(key).result();}
            return NodeId.nodeId(nodeIdPart).map(ActivationDirectiveKey::new);
        }
    }

    record BlueprintResourcesKey(BlueprintId blueprintId) implements AetherKey {
        private static final String PREFIX = "blueprint-resources/";

        @Override public String asString() {
            return PREFIX + blueprintId.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static BlueprintResourcesKey blueprintResourcesKey(BlueprintId blueprintId) {
            return new BlueprintResourcesKey(blueprintId);
        }

        public static Result<BlueprintResourcesKey> blueprintResourcesKey(String key) {
            if (!key.startsWith(PREFIX)) {return BLUEPRINT_RESOURCES_KEY_FORMAT_ERROR.apply(key).result();}
            var idPart = key.substring(PREFIX.length());
            return BlueprintId.blueprintId(idPart).map(BlueprintResourcesKey::new);
        }
    }

    record SchemaVersionKey(String datasourceName) implements AetherKey {
        private static final String PREFIX = "schema-version/";

        @Override public String asString() {
            return PREFIX + datasourceName;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static SchemaVersionKey schemaVersionKey(String datasourceName) {
            return new SchemaVersionKey(datasourceName);
        }

        public static Result<SchemaVersionKey> schemaVersionKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {return SCHEMA_VERSION_KEY_FORMAT_ERROR.apply(key).result();}
            var name = key.substring(PREFIX.length());
            if (name.isEmpty()) {return SCHEMA_VERSION_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new SchemaVersionKey(name));
        }
    }

    record SchemaMigrationLockKey(String datasourceName) implements AetherKey {
        private static final String PREFIX = "schema-lock/";

        @Override public String asString() {
            return PREFIX + datasourceName;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static SchemaMigrationLockKey schemaMigrationLockKey(String datasourceName) {
            return new SchemaMigrationLockKey(datasourceName);
        }

        public static Result<SchemaMigrationLockKey> schemaMigrationLockKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {return SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR.apply(key).result();}
            var name = key.substring(PREFIX.length());
            if (name.isEmpty()) {return SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new SchemaMigrationLockKey(name));
        }
    }

    record GossipKeyRotationKey() implements AetherKey {
        private static final String KEY = "gossip-key-rotation";

        @Override public String asString() {
            return KEY;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static GossipKeyRotationKey gossipKeyRotationKey() {
            return new GossipKeyRotationKey();
        }

        public static Result<GossipKeyRotationKey> gossipKeyRotationKey(String key) {
            if (!KEY.equals(key)) {return GOSSIP_KEY_ROTATION_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new GossipKeyRotationKey());
        }
    }

    record GovernorAnnouncementKey(String communityId) implements AetherKey {
        private static final String PREFIX = "governor-announcement/";

        @Override public String asString() {
            return PREFIX + communityId;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static GovernorAnnouncementKey forCommunity(String communityId) {
            return new GovernorAnnouncementKey(communityId);
        }

        public static Result<GovernorAnnouncementKey> governorAnnouncementKey(String key) {
            if (!key.startsWith(PREFIX)) {return GOVERNOR_ANNOUNCEMENT_KEY_FORMAT_ERROR.apply(key).result();}
            var communityId = key.substring(PREFIX.length());
            if (communityId.isEmpty()) {return GOVERNOR_ANNOUNCEMENT_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new GovernorAnnouncementKey(communityId));
        }
    }

    record AbTestKey(String testId) implements AetherKey {
        private static final String PREFIX = "ab-test/";

        @Override public String asString() {
            return PREFIX + testId;
        }

        @Override public String toString() {
            return asString();
        }

        public static Result<AbTestKey> abTestKey(String key) {
            if (!key.startsWith(PREFIX)) {return AB_TEST_KEY_FORMAT_ERROR.apply(key).result();}
            var id = key.substring(PREFIX.length());
            if (id.isEmpty()) {return AB_TEST_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new AbTestKey(id));
        }
    }

    record AbTestRoutingKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "ab-test-routing/";

        @Override public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static AbTestRoutingKey abTestRoutingKey(ArtifactBase artifactBase) {
            return new AbTestRoutingKey(artifactBase);
        }

        public static Result<AbTestRoutingKey> abTestRoutingKey(String key) {
            if (!key.startsWith(PREFIX)) {return AB_TEST_ROUTING_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactBasePart = key.substring(PREFIX.length());
            return ArtifactBase.artifactBase(artifactBasePart).map(AbTestRoutingKey::new);
        }
    }

    record NodeArtifactKey(NodeId nodeId, Artifact artifact) implements AetherKey {
        private static final String PREFIX = "node-artifact/";

        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override public String asString() {
            return PREFIX + nodeId.id() + "/" + artifact.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static NodeArtifactKey nodeArtifactKey(NodeId nodeId,
                                                                                      Artifact artifact) {
            return new NodeArtifactKey(nodeId, artifact);
        }

        public static Result<NodeArtifactKey> nodeArtifactKey(String key) {
            if (!key.startsWith(PREFIX)) {return NODE_ARTIFACT_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return NODE_ARTIFACT_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                        .result();}
            var nodeIdPart = content.substring(0, slashIndex);
            var artifactPart = content.substring(slashIndex + 1);
            return Result.all(NodeId.nodeId(nodeIdPart),
                              Artifact.artifact(artifactPart))
            .map((nid, art) -> new NodeArtifactKey(nid, art));
        }
    }

    record NodeRoutesKey(NodeId nodeId, Artifact artifact) implements AetherKey {
        private static final String PREFIX = "node-routes/";

        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override public String asString() {
            return PREFIX + nodeId.id() + "/" + artifact.asString();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static NodeRoutesKey nodeRoutesKey(NodeId nodeId, Artifact artifact) {
            return new NodeRoutesKey(nodeId, artifact);
        }

        public static Result<NodeRoutesKey> nodeRoutesKey(String key) {
            if (!key.startsWith(PREFIX)) {return NODE_ROUTES_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return NODE_ROUTES_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                      .result();}
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

    Fn1<Cause, String> DEPLOYMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid deployment key format: %s");

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

    Fn1<Cause, String> CLUSTER_CONFIG_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid cluster-config key format: %s");

    Fn1<Cause, String> STORAGE_BLOCK_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid storage-block key format: %s");

    Fn1<Cause, String> STORAGE_REF_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid storage-ref key format: %s");

    Fn1<Cause, String> STORAGE_STATUS_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid storage-status key format: %s");

    Fn1<Cause, String> STREAM_METADATA_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-meta key format: %s");

    Fn1<Cause, String> STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-assign key format: %s");

    Fn1<Cause, String> STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-cursor key format: %s");

    Fn1<Cause, String> STREAM_REGISTRATION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-reg key format: %s");

    record StreamMetadataKey(String streamName) implements AetherKey {
        private static final String PREFIX = "stream-meta/";

        @Override public String asString() {
            return PREFIX + streamName;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static StreamMetadataKey streamMetadataKey(String streamName) {
            return new StreamMetadataKey(streamName);
        }

        public static Result<StreamMetadataKey> streamMetadataKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {return STREAM_METADATA_KEY_FORMAT_ERROR.apply(key).result();}
            var name = key.substring(PREFIX.length());
            if (name.isEmpty()) {return STREAM_METADATA_KEY_FORMAT_ERROR.apply(key).result();}
            return success(new StreamMetadataKey(name));
        }
    }

    record StreamPartitionAssignmentKey(String streamName, String consumerGroup) implements AetherKey {
        private static final String PREFIX = "stream-assign/";

        @Override public String asString() {
            return PREFIX + streamName + "/" + consumerGroup;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static StreamPartitionAssignmentKey streamPartitionAssignmentKey(String streamName,
                                                                                                                String consumerGroup) {
            return new StreamPartitionAssignmentKey(streamName, consumerGroup);
        }

        public static Result<StreamPartitionAssignmentKey> streamPartitionAssignmentKey(String key) {
            if (!key.startsWith(PREFIX)) {return STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                                      .result();}
            var streamName = content.substring(0, slashIndex);
            var consumerGroup = content.substring(slashIndex + 1);
            return success(new StreamPartitionAssignmentKey(streamName, consumerGroup));
        }
    }

    record StreamCursorCheckpointKey(String streamName, int partitionIndex, String consumerGroup) implements AetherKey {
        private static final String PREFIX = "stream-cursor/";

        @Override public String asString() {
            return PREFIX + streamName + "/" + partitionIndex + "/" + consumerGroup;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static StreamCursorCheckpointKey streamCursorCheckpointKey(String streamName,
                                                                                                          int partitionIndex,
                                                                                                          String consumerGroup) {
            return new StreamCursorCheckpointKey(streamName, partitionIndex, consumerGroup);
        }

        public static Result<StreamCursorCheckpointKey> streamCursorCheckpointKey(String key) {
            if (!key.startsWith(PREFIX)) {return STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var parts = content.split("/");
            if (parts.length != 3) {return STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR.apply(key).result();}
            return Number.parseInt(parts[1])
                                  .map(partition -> new StreamCursorCheckpointKey(parts[0], partition, parts[2]));
        }
    }

    record StreamRegistrationKey(String streamName, String configSection, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "stream-reg/";

        @Override public String asString() {
            return PREFIX + streamName + "/" + configSection + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static StreamRegistrationKey streamRegistrationKey(String streamName,
                                                                                                  String configSection,
                                                                                                  Artifact artifact,
                                                                                                  MethodName methodName) {
            return new StreamRegistrationKey(streamName, configSection, artifact, methodName);
        }

        public static Result<StreamRegistrationKey> streamRegistrationKey(String key) {
            if (!key.startsWith(PREFIX)) {return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');
            if (firstSlash == - 1) {return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();}
            var streamName = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var secondSlash = rest.indexOf('/');
            if (secondSlash == - 1) {return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();}
            var configSection = rest.substring(0, secondSlash);
            var rest2 = rest.substring(secondSlash + 1);
            var lastSlash = rest2.lastIndexOf('/');
            if (lastSlash == - 1) {return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();}
            var artifactPart = rest2.substring(0, lastSlash);
            var methodPart = rest2.substring(lastSlash + 1);
            if (streamName.isEmpty() || configSection.isEmpty() || methodPart.isEmpty()) {return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                           .result();}
            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
            .map((artifact, method) -> new StreamRegistrationKey(streamName, configSection, artifact, method));
        }
    }

    record StorageBlockKey(String instanceName, String blockIdHex) implements AetherKey {
        private static final String PREFIX = "storage-block/";

        @Override public String asString() {
            return PREFIX + instanceName + "/" + blockIdHex;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static StorageBlockKey storageBlockKey(String instanceName,
                                                                                      String blockIdHex) {
            return new StorageBlockKey(instanceName, blockIdHex);
        }

        public static Result<StorageBlockKey> storageBlockKey(String key) {
            if (!key.startsWith(PREFIX)) {return STORAGE_BLOCK_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return STORAGE_BLOCK_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                        .result();}
            return success(new StorageBlockKey(content.substring(0, slashIndex), content.substring(slashIndex + 1)));
        }
    }

    record StorageRefKey(String instanceName, String referenceName) implements AetherKey {
        private static final String PREFIX = "storage-ref/";

        @Override public String asString() {
            return PREFIX + instanceName + "/" + referenceName;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static StorageRefKey storageRefKey(String instanceName,
                                                                                  String referenceName) {
            return new StorageRefKey(instanceName, referenceName);
        }

        public static Result<StorageRefKey> storageRefKey(String key) {
            if (!key.startsWith(PREFIX)) {return STORAGE_REF_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return STORAGE_REF_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                      .result();}
            return success(new StorageRefKey(content.substring(0, slashIndex), content.substring(slashIndex + 1)));
        }
    }

    record StorageStatusKey(NodeId nodeId, String instanceName) implements AetherKey {
        private static final String PREFIX = "storage-status/";

        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override public String asString() {
            return PREFIX + nodeId.id() + "/" + instanceName;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static StorageStatusKey storageStatusKey(NodeId nodeId,
                                                                                        String instanceName) {
            return new StorageStatusKey(nodeId, instanceName);
        }

        public static Result<StorageStatusKey> storageStatusKey(String key) {
            if (!key.startsWith(PREFIX)) {return STORAGE_STATUS_KEY_FORMAT_ERROR.apply(key).result();}
            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');
            if (slashIndex == - 1 || slashIndex == 0 || slashIndex == content.length() - 1) {return STORAGE_STATUS_KEY_FORMAT_ERROR.apply(key)
                                                                                                                                         .result();}
            var nodeIdPart = content.substring(0, slashIndex);
            var instanceNamePart = content.substring(slashIndex + 1);
            return NodeId.nodeId(nodeIdPart).map(nid -> new StorageStatusKey(nid, instanceNamePart));
        }
    }

    record ClusterConfigKey(long configVersion) implements AetherKey {
        private static final String PREFIX = "cluster-config/";

        @Override public String asString() {
            return PREFIX + configVersion;
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static final ClusterConfigKey CURRENT = new ClusterConfigKey(0);

        @SuppressWarnings("JBCT-VO-02") public static ClusterConfigKey clusterConfigKey(long configVersion) {
            return new ClusterConfigKey(configVersion);
        }

        public static Result<ClusterConfigKey> clusterConfigKey(String key) {
            if (!key.startsWith(PREFIX)) {return CLUSTER_CONFIG_KEY_FORMAT_ERROR.apply(key).result();}
            var versionPart = key.substring(PREFIX.length());
            if (versionPart.isEmpty()) {return CLUSTER_CONFIG_KEY_FORMAT_ERROR.apply(key).result();}
            return Number.parseLong(versionPart).map(ClusterConfigKey::new);
        }
    }

    record TaskAssignmentKey(TaskGroup taskGroup) implements AetherKey {
        private static final String PREFIX = "task-assignment/";

        @Override public String asString() {
            return PREFIX + taskGroup.name();
        }

        @Override public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02") public static TaskAssignmentKey taskAssignmentKey(TaskGroup taskGroup) {
            return new TaskAssignmentKey(taskGroup);
        }

        public static Result<TaskAssignmentKey> taskAssignmentKey(String key) {
            if (!key.startsWith(PREFIX)) {return TASK_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key).result();}
            var enumName = key.substring(PREFIX.length());
            if (enumName.isEmpty()) {return TASK_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key).result();}
            return Result.lift1(_ -> TASK_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key),
                                TaskGroup::valueOf,
                                enumName)
            .map(TaskAssignmentKey::new);
        }
    }

    Fn1<Cause, String> TASK_ASSIGNMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid task-assignment key format: %s");
}
