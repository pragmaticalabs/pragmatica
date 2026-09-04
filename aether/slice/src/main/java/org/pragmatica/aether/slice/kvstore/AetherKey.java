// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


@Codec
@CodecFor(MethodName.class)
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-NAM-01"})
public sealed interface AetherKey extends StructuredKey {
    String asString();

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

        public static SliceTargetKey sliceTargetKey(ArtifactBase artifactBase) {
            return new SliceTargetKey(artifactBase);
        }

        public static Result<SliceTargetKey> sliceTargetKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return SLICE_TARGET_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactBasePart = key.substring(PREFIX.length());

            return ArtifactBase.artifactBase(artifactBasePart).map(SliceTargetKey::new);
        }
    }

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
                return APP_BLUEPRINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var blueprintIdPart = key.substring(PREFIX.length());

            return BlueprintId.blueprintId(blueprintIdPart).map(AppBlueprintKey::new);
        }

        public static AppBlueprintKey appBlueprintKey(BlueprintId blueprintId) {
            return new AppBlueprintKey(blueprintId);
        }
    }

    /// Durable terminal outcome of a blueprint's deployment attempt. One record per blueprint id —
    /// a later {@link org.pragmatica.cluster.state.kvstore.KVCommand.Put} at the same key overwrites
    /// the previous outcome, so the store holds exactly the latest outcome per blueprint id, never a
    /// history. Unlike {@link AppBlueprintKey}, this key is never removed on rollback: it is the
    /// record of what happened to the rollback, not part of the blueprint's active configuration.
    record DeploymentOutcomeKey(BlueprintId blueprintId) implements AetherKey {
        private static final String PREFIX = "deployment-outcome/";

        @Override
        public String asString() {
            return PREFIX + blueprintId.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<DeploymentOutcomeKey> deploymentOutcomeKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return DEPLOYMENT_OUTCOME_KEY_FORMAT_ERROR.apply(key).result();
            }

            var blueprintIdPart = key.substring(PREFIX.length());

            return BlueprintId.blueprintId(blueprintIdPart).map(DeploymentOutcomeKey::new);
        }

        public static DeploymentOutcomeKey deploymentOutcomeKey(BlueprintId blueprintId) {
            return new DeploymentOutcomeKey(blueprintId);
        }
    }

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

        public static SliceNodeKey sliceNodeKey(Artifact artifact, NodeId nodeId) {
            return new SliceNodeKey(artifact, nodeId);
        }

        public static Result<SliceNodeKey> sliceNodeKey(String key) {
            var parts = key.split("/");

            if (parts.length != 3) {
                return SLICE_KEY_FORMAT_ERROR.apply(key).result();
            }

            if (!"slices".equals(parts[0])) {
                return SLICE_KEY_FORMAT_ERROR.apply(key).result();
            }

            if (parts[1].isEmpty()) {
                return SLICE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Result.all(Artifact.artifact(parts[2]),
                              NodeId.nodeId(parts[1]))
                         .map(SliceNodeKey::new);
        }
    }

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
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactPart = content.substring(0, slashIndex);
            var endpointPart = content.substring(slashIndex + 1);
            var colonIndex = endpointPart.lastIndexOf(':');

            if (colonIndex == -1) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

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

        @Override
        public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static VersionRoutingKey versionRoutingKey(ArtifactBase artifactBase) {
            return new VersionRoutingKey(artifactBase);
        }

        public static Result<VersionRoutingKey> versionRoutingKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return VERSION_ROUTING_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactBasePart = key.substring(PREFIX.length());

            return ArtifactBase.artifactBase(artifactBasePart).map(VersionRoutingKey::new);
        }
    }

    record DeploymentKey(String deploymentId) implements AetherKey {
        private static final String PREFIX = "deployment/";

        @Override
        public String asString() {
            return PREFIX + deploymentId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static DeploymentKey deploymentKey(String deploymentId) {
            return new DeploymentKey(deploymentId);
        }

        public static Result<DeploymentKey> parseDeploymentKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return DEPLOYMENT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var id = key.substring(PREFIX.length());

            if (id.isEmpty()) {
                return DEPLOYMENT_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new DeploymentKey(id));
        }
    }

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

        public static PreviousVersionKey previousVersionKey(ArtifactBase artifactBase) {
            return new PreviousVersionKey(artifactBase);
        }

        public static Result<PreviousVersionKey> previousVersionKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return PREVIOUS_VERSION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactBasePart = key.substring(PREFIX.length());

            return ArtifactBase.artifactBase(artifactBasePart).map(PreviousVersionKey::new);
        }
    }

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

        public String routeIdentity() {
            return httpMethod + ":" + pathPrefix;
        }

        public static HttpNodeRouteKey httpNodeRouteKey(String httpMethod, String pathPrefix, NodeId nodeId) {
            return new HttpNodeRouteKey(httpMethod.toUpperCase(), normalizePrefix(pathPrefix), nodeId);
        }

        public static Result<HttpNodeRouteKey> httpNodeRouteKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var firstColon = content.indexOf(':');
            var lastColon = content.lastIndexOf(':');

            if (firstColon == -1 || lastColon == -1 || firstColon == lastColon) {
                return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key).result();
            }

            var httpMethod = content.substring(0, firstColon);
            var pathPrefix = content.substring(firstColon + 1, lastColon);
            var nodeIdPart = content.substring(lastColon + 1);

            if (httpMethod.isEmpty() || pathPrefix.isEmpty() || nodeIdPart.isEmpty()) {
                return HTTP_NODE_ROUTE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return NodeId.nodeId(nodeIdPart).map(nodeId -> new HttpNodeRouteKey(httpMethod, pathPrefix, nodeId));
        }

        private static String normalizePrefix(String path) {
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

        public static LogLevelKey forLogger(String loggerName) {
            return new LogLevelKey(loggerName);
        }

        public static Result<LogLevelKey> logLevelKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return LOG_LEVEL_KEY_FORMAT_ERROR.apply(key).result();
            }

            var loggerName = key.substring(PREFIX.length());

            if (loggerName.isEmpty()) {
                return LOG_LEVEL_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new LogLevelKey(loggerName));
        }
    }

    record ObservabilityConfigKey(String artifactBase, String methodName) implements AetherKey {
        private static final String PREFIX = "obs-config/";

        @Override
        public String asString() {
            return PREFIX + artifactBase + "/" + methodName;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static ObservabilityConfigKey observabilityConfigKey(String artifactBase, String methodName) {
            return new ObservabilityConfigKey(artifactBase, methodName);
        }

        public static Result<ObservabilityConfigKey> observabilityConfigKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return OBSERVABILITY_CONFIG_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return OBSERVABILITY_CONFIG_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactBase = content.substring(0, slashIndex);
            var methodName = content.substring(slashIndex + 1);

            return success(new ObservabilityConfigKey(artifactBase, methodName));
        }
    }

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
                return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key).result();
            }

            var metricName = key.substring(PREFIX.length());

            if (metricName.isEmpty()) {
                return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new AlertThresholdKey(metricName));
        }
    }

    /// Per-subscription registry key, now namespaced: `topic-sub/{namespace}/{topic}/{version}/{artifact}/{method}`.
    ///
    /// Carries a full [ResourceAddress] (namespace + topic + version) so pub/sub mirrors the stream
    /// addressing model. Runtime routing still matches on the bare topic name ([ResourceAddress#topic])
    /// — see [TopicSubscriptionRegistry] — so existing un-namespaced topics keep working; the
    /// namespace/version travel as addressing metadata in the key's wire form. `asString`/parse are
    /// symmetric so the `topic-sub` serializer arm round-trips automatically.
    record TopicSubscriptionKey(ResourceAddress address, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "topic-sub/";

        @Override
        public String asString() {
            return PREFIX + address.namespace()
                                   .value()
                 + "/" + address.name()
                                .value()
                 + "/" + address.version()
                                .asString()
                 + "/" + artifact.asString()
                 + "/" + methodName.name();
        }

        @Override
        public String toString() {
            return asString();
        }

        /// Bare topic name — the runtime routing identity, kept for back-compat with publishers that
        /// route on the un-namespaced name.
        public String topicName() {
            return address.name()
                          .value();
        }

        public static TopicSubscriptionKey topicSubscriptionKey(ResourceAddress address,
                                                                Artifact artifact,
                                                                MethodName methodName) {
            return new TopicSubscriptionKey(address, artifact, methodName);
        }

        public static Result<TopicSubscriptionKey> topicSubscriptionKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');

            if (firstSlash <= 0) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var namespace = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var secondSlash = rest.indexOf('/');

            if (secondSlash <= 0) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var topic = rest.substring(0, secondSlash);
            var rest2 = rest.substring(secondSlash + 1);
            var thirdSlash = rest2.indexOf('/');

            if (thirdSlash <= 0) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var version = rest2.substring(0, thirdSlash);
            var rest3 = rest2.substring(thirdSlash + 1);
            var lastSlash = rest3.lastIndexOf('/');

            if (lastSlash <= 0) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactPart = rest3.substring(0, lastSlash);
            var methodPart = rest3.substring(lastSlash + 1);

            if (methodPart.isEmpty()) {
                return TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Result.all(ResourceAddress.resourceAddress(namespace, topic, version),
                              Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
                         .map(TopicSubscriptionKey::new);
        }
    }

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

        public static ScheduledTaskKey scheduledTaskKey(String configSection,
                                                        Artifact artifact,
                                                        MethodName methodName) {
            return new ScheduledTaskKey(configSection, artifact, methodName);
        }

        public static Result<ScheduledTaskKey> scheduledTaskKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');

            if (firstSlash == -1) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key).result();
            }

            var configSection = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');

            if (lastSlash == -1) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);

            if (configSection.isEmpty() || methodPart.isEmpty()) {
                return SCHEDULED_TASK_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
                         .map((artifact, method) -> new ScheduledTaskKey(configSection, artifact, method));
        }
    }

    /// `node` is `Option.none()` for SINGLE-mode tasks (one leader-owned counter, cluster-wide)
    /// and for any ALL-mode row written before #841 — both share the pre-#841 wire shape
    /// byte-for-byte, since [#scheduledTaskStateKey(String,Artifact,MethodName)] never changes.
    /// ALL-mode tasks (#841) are scoped to the executing node via
    /// [#scheduledTaskStateKey(String,Artifact,MethodName,NodeId)] so concurrent executions of the
    /// same task on different nodes never share-and-clobber one counter row. The node segment is a
    /// literal `node/<id>/` marker right after [#PREFIX] — the same disambiguation `ConfigKey`
    /// uses for its own node scope — rather than a trailing segment, because
    /// [#scheduledTaskStateKey(String)] already finds the artifact/method boundary by last-slash
    /// search and a trailing node segment would collide with that search. Consequence: a
    /// pre-upgrade global row (`node` absent) can never parse into the node-scoped shape, so the
    /// new per-node aggregation scan — which filters on `node().isPresent()` — can never misread
    /// it as a node's row.
    record ScheduledTaskStateKey(String configSection, Artifact artifact, MethodName methodName, Option<NodeId> node) implements AetherKey {
        private static final String PREFIX = "scheduled-task-state/";
        private static final String NODE_PREFIX = PREFIX + "node/";

        @Override
        public String asString() {
            var suffix = configSection + "/" + artifact.asString() + "/" + methodName.name();

            return node.fold(() -> PREFIX + suffix,
                             nodeId -> NODE_PREFIX + nodeId.id() + "/" + suffix);
        }

        @Override
        public String toString() {
            return asString();
        }

        public static ScheduledTaskStateKey scheduledTaskStateKey(String configSection,
                                                                  Artifact artifact,
                                                                  MethodName methodName) {
            return new ScheduledTaskStateKey(configSection, artifact, methodName, Option.none());
        }

        public static ScheduledTaskStateKey scheduledTaskStateKey(String configSection,
                                                                  Artifact artifact,
                                                                  MethodName methodName,
                                                                  NodeId node) {
            return new ScheduledTaskStateKey(configSection, artifact, methodName, Option.some(node));
        }

        public static Result<ScheduledTaskStateKey> scheduledTaskStateKey(String key) {
            if (key.startsWith(NODE_PREFIX)) {
                var rest = key.substring(NODE_PREFIX.length());
                var slashIndex = rest.indexOf('/');

                if (slashIndex == -1) {
                    return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key).result();
                }

                var nodeIdPart = rest.substring(0, slashIndex);
                var tail = rest.substring(slashIndex + 1);

                return NodeId.nodeId(nodeIdPart).flatMap(nodeId -> parseSectionArtifactMethod(tail,
                                                                                              key,
                                                                                              Option.some(nodeId)));
            }

            if (!key.startsWith(PREFIX)) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return parseSectionArtifactMethod(key.substring(PREFIX.length()),
                                              key,
                                              Option.none());
        }

        private static Result<ScheduledTaskStateKey> parseSectionArtifactMethod(String content,
                                                                                String originalKey,
                                                                                Option<NodeId> node) {
            var firstSlash = content.indexOf('/');

            if (firstSlash == -1) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(originalKey).result();
            }

            var configSection = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var lastSlash = rest.lastIndexOf('/');

            if (lastSlash == -1) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(originalKey).result();
            }

            var artifactPart = rest.substring(0, lastSlash);
            var methodPart = rest.substring(lastSlash + 1);

            if (configSection.isEmpty() || methodPart.isEmpty()) {
                return SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR.apply(originalKey).result();
            }

            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
                         .map((artifact, method) -> new ScheduledTaskStateKey(configSection, artifact, method, node));
        }
    }

    /// Phase 1 step J — observability atom mirroring the in-process `JOIN_DEADLINE`
    /// scheduler entry the leader's `MembershipFsm` arms when a peer enters JOINING.
    /// Replicated via Rabia so a new leader on takeover can reconstruct the deadline from
    /// KV state instead of relying on the prior leader's in-memory scheduler. The atom is
    /// pure observability — the scheduler is still the trigger; the KV `Remove` on
    /// JOINING-exit is what stops the new leader from re-arming a stale timer.
    record JoinDeadlineKey(NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "join-deadline/";

        @Override
        public String asString() {
            return PREFIX + nodeId.id();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static JoinDeadlineKey joinDeadlineKey(NodeId nodeId) {
            return new JoinDeadlineKey(nodeId);
        }

        public static Result<JoinDeadlineKey> joinDeadlineKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return JOIN_DEADLINE_KEY_FORMAT_ERROR.apply(key).result();
            }

            var nodeIdPart = key.substring(PREFIX.length());

            if (nodeIdPart.isEmpty()) {
                return JOIN_DEADLINE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return NodeId.nodeId(nodeIdPart).map(JoinDeadlineKey::new);
        }
    }

    /// Phase 1 step J — observability atom mirroring the in-process `DRAIN_DEADLINE`
    /// scheduler entry. Written on DRAINING entry, removed on any DRAINING-exit
    /// (DECOMMISSIONED, FAILED_DRAIN). The new leader inspects this atom on takeover to
    /// resume the drain hard-deadline countdown against wall-clock instead of the prior
    /// leader's elapsed timer.
    record DrainDeadlineKey(NodeId nodeId) implements AetherKey {
        private static final String PREFIX = "drain-deadline/";

        @Override
        public String asString() {
            return PREFIX + nodeId.id();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static DrainDeadlineKey drainDeadlineKey(NodeId nodeId) {
            return new DrainDeadlineKey(nodeId);
        }

        public static Result<DrainDeadlineKey> drainDeadlineKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return DRAIN_DEADLINE_KEY_FORMAT_ERROR.apply(key).result();
            }

            var nodeIdPart = key.substring(PREFIX.length());

            if (nodeIdPart.isEmpty()) {
                return DRAIN_DEADLINE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return NodeId.nodeId(nodeIdPart).map(DrainDeadlineKey::new);
        }
    }

    record ConfigKey(String key, Option<NodeId> nodeScope) implements AetherKey {
        private static final String CLUSTER_PREFIX = "config/";
        private static final String NODE_PREFIX = "config/node/";

        @Override
        public String asString() {
            return nodeScope.fold(() -> CLUSTER_PREFIX + key,
                                  nodeId -> NODE_PREFIX + nodeId.id() + "/" + key);
        }

        @Override
        public String toString() {
            return asString();
        }

        public boolean isClusterWide() {
            return nodeScope.isEmpty();
        }

        public static ConfigKey forKey(String key) {
            return new ConfigKey(key, none());
        }

        public static ConfigKey forKey(String key, NodeId nodeId) {
            return new ConfigKey(key, some(nodeId));
        }

        public static Result<ConfigKey> configKey(String raw) {
            if (raw.startsWith(NODE_PREFIX)) {
                var content = raw.substring(NODE_PREFIX.length());
                var slashIndex = content.indexOf('/');

                if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                    return CONFIG_KEY_FORMAT_ERROR.apply(raw).result();
                }

                var nodeIdPart = content.substring(0, slashIndex);
                var keyPart = content.substring(slashIndex + 1);

                return NodeId.nodeId(nodeIdPart).map(nodeId -> new ConfigKey(keyPart, some(nodeId)));
            }

            if (raw.startsWith(CLUSTER_PREFIX)) {
                var keyPart = raw.substring(CLUSTER_PREFIX.length());

                if (keyPart.isEmpty()) {
                    return CONFIG_KEY_FORMAT_ERROR.apply(raw).result();
                }

                return success(new ConfigKey(keyPart, none()));
            }

            return CONFIG_KEY_FORMAT_ERROR.apply(raw).result();
        }
    }

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

        public static WorkerSliceDirectiveKey workerSliceDirectiveKey(Artifact artifact) {
            return new WorkerSliceDirectiveKey(artifact, Option.none());
        }

        public static WorkerSliceDirectiveKey workerSliceDirectiveKey(Artifact artifact, String communityId) {
            return new WorkerSliceDirectiveKey(artifact, Option.option(communityId));
        }

        public static Result<WorkerSliceDirectiveKey> workerSliceDirectiveKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return WORKER_DIRECTIVE_KEY_FORMAT_ERROR.apply(key).result();
            }

            var rest = key.substring(PREFIX.length());
            var slashIndex = rest.indexOf('/');

            if (slashIndex >= 0) {
                return parseCommunityKey(rest, slashIndex);
            }

            return Artifact.artifact(rest).map(art -> new WorkerSliceDirectiveKey(art, Option.none()));
        }

        private static Result<WorkerSliceDirectiveKey> parseCommunityKey(String rest, int slashIndex) {
            var communityPart = rest.substring(0, slashIndex);
            var artifactPart = rest.substring(slashIndex + 1);

            return Artifact.artifact(artifactPart).map(art -> new WorkerSliceDirectiveKey(art,
                                                                                          Option.some(communityPart)));
        }
    }

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

        public static ActivationDirectiveKey activationDirectiveKey(NodeId nodeId) {
            return new ActivationDirectiveKey(nodeId);
        }

        public static Result<ActivationDirectiveKey> activationDirectiveKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR.apply(key).result();
            }

            var nodeIdPart = key.substring(PREFIX.length());

            if (nodeIdPart.isEmpty()) {
                return ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return NodeId.nodeId(nodeIdPart).map(ActivationDirectiveKey::new);
        }
    }

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

        public static SchemaVersionKey schemaVersionKey(String datasourceName) {
            return new SchemaVersionKey(datasourceName);
        }

        public static Result<SchemaVersionKey> schemaVersionKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {
                return SCHEMA_VERSION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var name = key.substring(PREFIX.length());

            if (name.isEmpty()) {
                return SCHEMA_VERSION_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new SchemaVersionKey(name));
        }
    }

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

        public static SchemaMigrationLockKey schemaMigrationLockKey(String datasourceName) {
            return new SchemaMigrationLockKey(datasourceName);
        }

        public static Result<SchemaMigrationLockKey> schemaMigrationLockKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {
                return SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR.apply(key).result();
            }

            var name = key.substring(PREFIX.length());

            if (name.isEmpty()) {
                return SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new SchemaMigrationLockKey(name));
        }
    }

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

        public static GossipKeyRotationKey gossipKeyRotationKey() {
            return new GossipKeyRotationKey();
        }

        public static Result<GossipKeyRotationKey> gossipKeyRotationKey(String key) {
            if (!KEY.equals(key)) {
                return GOSSIP_KEY_ROTATION_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new GossipKeyRotationKey());
        }
    }

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

        public static GovernorAnnouncementKey forCommunity(String communityId) {
            return new GovernorAnnouncementKey(communityId);
        }

        public static Result<GovernorAnnouncementKey> governorAnnouncementKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return GOVERNOR_ANNOUNCEMENT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var communityId = key.substring(PREFIX.length());

            if (communityId.isEmpty()) {
                return GOVERNOR_ANNOUNCEMENT_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new GovernorAnnouncementKey(communityId));
        }
    }

    /// Desired-state community identity (worker-membership-spec §2, D1): a leader-minted, stable,
    /// committed KV fact keyed on the immutable `communityId`. Mirrors [GovernorAnnouncementKey]
    /// (the governor-owned *observed* statement for the same community) at the key level.
    record CommunityKey(String communityId) implements AetherKey {
        private static final String PREFIX = "community/";

        @Override
        public String asString() {
            return PREFIX + communityId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static CommunityKey communityKey(String communityId) {
            return new CommunityKey(communityId);
        }

        public static Result<CommunityKey> parseCommunityKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return COMMUNITY_KEY_FORMAT_ERROR.apply(key).result();
            }

            var communityId = key.substring(PREFIX.length());

            if (communityId.isEmpty()) {
                return COMMUNITY_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new CommunityKey(communityId));
        }
    }

    record AbTestKey(String testId) implements AetherKey {
        private static final String PREFIX = "ab-test/";

        @Override
        public String asString() {
            return PREFIX + testId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<AbTestKey> abTestKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return AB_TEST_KEY_FORMAT_ERROR.apply(key).result();
            }

            var id = key.substring(PREFIX.length());

            if (id.isEmpty()) {
                return AB_TEST_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new AbTestKey(id));
        }
    }

    record AbTestRoutingKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "ab-test-routing/";

        @Override
        public String asString() {
            return PREFIX + artifactBase.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static AbTestRoutingKey abTestRoutingKey(ArtifactBase artifactBase) {
            return new AbTestRoutingKey(artifactBase);
        }

        public static Result<AbTestRoutingKey> abTestRoutingKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return AB_TEST_ROUTING_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactBasePart = key.substring(PREFIX.length());

            return ArtifactBase.artifactBase(artifactBasePart).map(AbTestRoutingKey::new);
        }
    }

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

        public static NodeArtifactKey nodeArtifactKey(NodeId nodeId, Artifact artifact) {
            return new NodeArtifactKey(nodeId, artifact);
        }

        public static Result<NodeArtifactKey> nodeArtifactKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return NODE_ARTIFACT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return NODE_ARTIFACT_KEY_FORMAT_ERROR.apply(key).result();
            }

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

        @Override
        public String asString() {
            return PREFIX + nodeId.id() + "/" + artifact.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static NodeRoutesKey nodeRoutesKey(NodeId nodeId, Artifact artifact) {
            return new NodeRoutesKey(nodeId, artifact);
        }

        public static Result<NodeRoutesKey> nodeRoutesKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return NODE_ROUTES_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return NODE_ROUTES_KEY_FORMAT_ERROR.apply(key).result();
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

    Fn1<Cause, String> COMMUNITY_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid community key format: %s");

    Fn1<Cause, String> GOSSIP_KEY_ROTATION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid gossip-key-rotation key format: %s");

    Fn1<Cause, String> SCHEDULED_TASK_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid scheduled-task key format: %s");

    Fn1<Cause, String> SCHEDULED_TASK_STATE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid scheduled-task-state key format: %s");

    Fn1<Cause, String> TOPIC_SUBSCRIPTION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid topic-sub key format: %s");

    Fn1<Cause, String> SLICE_TARGET_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice-target key format: %s");

    Fn1<Cause, String> APP_BLUEPRINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid app-blueprint key format: %s");

    Fn1<Cause, String> DEPLOYMENT_OUTCOME_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid deployment-outcome key format: %s");

    Fn1<Cause, String> SLICE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice key format: %s");
    Fn1<Cause, String> ENDPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid endpoint key format: %s");

    Fn1<Cause, String> VERSION_ROUTING_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid version-routing key format: %s");

    Fn1<Cause, String> DEPLOYMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid deployment key format: %s");

    Fn1<Cause, String> PREVIOUS_VERSION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid previous-version key format: %s");

    Fn1<Cause, String> HTTP_NODE_ROUTE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid http-node-routes key format: %s");

    Fn1<Cause, String> ALERT_THRESHOLD_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid alert-threshold key format: %s");

    Fn1<Cause, String> LOG_LEVEL_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid log-level key format: %s");

    Fn1<Cause, String> OBSERVABILITY_CONFIG_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid obs-config key format: %s");

    Fn1<Cause, String> CONFIG_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid config key format: %s");

    Fn1<Cause, String> JOIN_DEADLINE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid join-deadline key format: %s");

    Fn1<Cause, String> DRAIN_DEADLINE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid drain-deadline key format: %s");

    Fn1<Cause, String> WORKER_DIRECTIVE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid worker-directive key format: %s");

    Fn1<Cause, String> ACTIVATION_DIRECTIVE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid activation key format: %s");

    Fn1<Cause, String> SCHEMA_VERSION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid schema-version key format: %s");

    Fn1<Cause, String> SCHEMA_MIGRATION_LOCK_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid schema-lock key format: %s");

    Fn1<Cause, String> AB_TEST_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid ab-test key format: %s");

    Fn1<Cause, String> AB_TEST_ROUTING_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid ab-test-routing key format: %s");

    Fn1<Cause, String> CLUSTER_CONFIG_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid cluster-config key format: %s");

    Fn1<Cause, String> STORAGE_BLOCK_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid storage-block key format: %s");

    Fn1<Cause, String> ENTITY_KEYSPACE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid entity-keyspace key format: %s");

    Fn1<Cause, String> ENTITY_CHECKPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid entity-checkpoint key format: %s");

    Fn1<Cause, String> STORAGE_REF_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid storage-ref key format: %s");

    Fn1<Cause, String> STORAGE_STATUS_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid storage-status key format: %s");

    Fn1<Cause, String> STREAM_METADATA_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-meta key format: %s");

    Fn1<Cause, String> STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-assign key format: %s");

    Fn1<Cause, String> STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-cursor key format: %s");

    Fn1<Cause, String> STREAM_REGISTRATION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-reg key format: %s");

    record StreamMetadataKey(String streamName) implements AetherKey {
        private static final String PREFIX = "stream-meta/";

        @Override
        public String asString() {
            return PREFIX + streamName;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StreamMetadataKey streamMetadataKey(String streamName) {
            return new StreamMetadataKey(streamName);
        }

        public static Result<StreamMetadataKey> streamMetadataKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {
                return STREAM_METADATA_KEY_FORMAT_ERROR.apply(key).result();
            }

            var name = key.substring(PREFIX.length());

            if (name.isEmpty()) {
                return STREAM_METADATA_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new StreamMetadataKey(name));
        }
    }

    record StreamPartitionAssignmentKey(String streamName, String consumerGroup) implements AetherKey {
        private static final String PREFIX = "stream-assign/";

        @Override
        public String asString() {
            return PREFIX + streamName + "/" + consumerGroup;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StreamPartitionAssignmentKey streamPartitionAssignmentKey(String streamName,
                                                                                String consumerGroup) {
            return new StreamPartitionAssignmentKey(streamName, consumerGroup);
        }

        public static Result<StreamPartitionAssignmentKey> streamPartitionAssignmentKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var streamName = content.substring(0, slashIndex);
            var consumerGroup = content.substring(slashIndex + 1);

            return success(new StreamPartitionAssignmentKey(streamName, consumerGroup));
        }
    }

    record StreamCursorCheckpointKey(String streamName, int partitionIndex, String consumerGroup) implements AetherKey {
        private static final String PREFIX = "stream-cursor/";

        @Override
        public String asString() {
            return PREFIX + streamName + "/" + partitionIndex + "/" + consumerGroup;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StreamCursorCheckpointKey streamCursorCheckpointKey(String streamName,
                                                                          int partitionIndex,
                                                                          String consumerGroup) {
            return new StreamCursorCheckpointKey(streamName, partitionIndex, consumerGroup);
        }

        public static Result<StreamCursorCheckpointKey> streamCursorCheckpointKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var parts = content.split("/");

            if (parts.length != 3) {
                return STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Number.parseInt(parts[1]).map(partition -> new StreamCursorCheckpointKey(parts[0],
                                                                                            partition,
                                                                                            parts[2]));
        }
    }

    record StreamRegistrationKey(String streamName, String configSection, Artifact artifact, MethodName methodName) implements AetherKey {
        private static final String PREFIX = "stream-reg/";

        @Override
        public String asString() {
            return PREFIX + streamName + "/" + configSection + "/" + artifact.asString() + "/" + methodName.name();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StreamRegistrationKey streamRegistrationKey(String streamName,
                                                                  String configSection,
                                                                  Artifact artifact,
                                                                  MethodName methodName) {
            return new StreamRegistrationKey(streamName, configSection, artifact, methodName);
        }

        public static Result<StreamRegistrationKey> streamRegistrationKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');

            if (firstSlash == -1) {
                return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var streamName = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var secondSlash = rest.indexOf('/');

            if (secondSlash == -1) {
                return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var configSection = rest.substring(0, secondSlash);
            var rest2 = rest.substring(secondSlash + 1);
            var lastSlash = rest2.lastIndexOf('/');

            if (lastSlash == -1) {
                return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactPart = rest2.substring(0, lastSlash);
            var methodPart = rest2.substring(lastSlash + 1);

            if (streamName.isEmpty() || configSection.isEmpty() || methodPart.isEmpty()) {
                return STREAM_REGISTRATION_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Result.all(Artifact.artifact(artifactPart),
                              MethodName.methodName(methodPart))
                         .map((artifact, method) -> new StreamRegistrationKey(streamName,
                                                                              configSection,
                                                                              artifact,
                                                                              method));
        }
    }

    /// PER-NODE declaration that a durable-entity keyspace is live ON `node` (#345 I1, narrow C). It
    /// exists for two reasons: the per-`(keyspace, partition)` ownership records that fence entity writes
    /// are minted by a LEADER-ONLY reconcile pass, and the leader has no other way to learn that a
    /// keyspace exists — `DurableEntityConfig` is per-slice and node-local; and the leader must mint
    /// owners ONLY over the nodes that actually host the keyspace's declaring slice, which it can only
    /// know from the set of committed per-node records. A keyspace-wide record (the original shape of this
    /// key) could not carry the hosting set: with `instances` below the cluster size the leader minted
    /// owners over ALL nodes, and every partition owned by a non-hosting node refused every write.
    ///
    /// Each hosting node writes ITS OWN `(keyspace, node)` record, so the put stays idempotent with no
    /// read-modify-write race, and the node's level-triggered reconcile can make "my committed records"
    /// equal "my locally-provisioned keyspaces" in both directions — asserting on provision, pruning on
    /// unload or on a restart that no longer hosts the slice.
    ///
    /// `keyspace` is the RAW name from `resources.toml`; the `entity:` ownership-arc prefix is applied by
    /// `EntityPartitionArc`, not stored here, so this key stays a statement about the DECLARATION and the
    /// arc naming has exactly one owner. `keyspace` never contains `/` — ENFORCED at the one entry point
    /// every keyspace passes through, `DurableEntityConfig.durableEntityConfig` (a `/` is refused at bind
    /// time with `InvalidKeyspace`) — and [#fromIdentity] relies on that to split the identity at the
    /// FIRST separator.
    ///
    /// The REGISTRATION is deliberately not a stream record, even though the keyspace's LOG has been a
    /// real stream (`entity:<keyspace>`, created by `StreamEntityLogSubstrate.ensureLog`) since #345 I3:
    /// a keyspace must be declared and owned before — and independently of — its log's replica
    /// lifecycle, and deriving the hosting set from stream records would couple the declaration to
    /// whichever nodes happen to hold log replicas rather than to where the SLICE is provisioned.
    record EntityKeyspaceRegistrationKey(String keyspace, NodeId node) implements AetherKey {
        private static final String PREFIX = "entity-keyspace/";
        private static final String SEPARATOR = "/";

        @Override
        public String asString() {
            return PREFIX + keyspace + SEPARATOR + node.id();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static EntityKeyspaceRegistrationKey entityKeyspaceRegistrationKey(String keyspace, NodeId node) {
            return new EntityKeyspaceRegistrationKey(keyspace, node);
        }

        /// Rebuild from the snapshot IDENTITY (the part after the section prefix):
        /// `<keyspace>/<nodeId>`. The split is at the FIRST `/` — legal because a keyspace never
        /// contains one (see the type comment) while nothing constrains the node-id tail.
        public static Result<EntityKeyspaceRegistrationKey> fromIdentity(String identity) {
            var separator = identity.indexOf(SEPARATOR);

            if (separator <= 0 || separator == identity.length() - 1) {
                return ENTITY_KEYSPACE_KEY_FORMAT_ERROR.apply(PREFIX + identity).result();
            }

            return NodeId.nodeId(identity.substring(separator + 1)).map(node -> new EntityKeyspaceRegistrationKey(identity.substring(0,
                                                                                                                                     separator),
                                                                                                                  node));
        }
    }

    record StorageBlockKey(String instanceName, String blockIdHex) implements AetherKey {
        private static final String PREFIX = "storage-block/";

        @Override
        public String asString() {
            return PREFIX + instanceName + "/" + blockIdHex;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StorageBlockKey storageBlockKey(String instanceName, String blockIdHex) {
            return new StorageBlockKey(instanceName, blockIdHex);
        }

        public static Result<StorageBlockKey> storageBlockKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STORAGE_BLOCK_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return STORAGE_BLOCK_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new StorageBlockKey(content.substring(0, slashIndex), content.substring(slashIndex + 1)));
        }
    }

    record StorageRefKey(String instanceName, String referenceName) implements AetherKey {
        private static final String PREFIX = "storage-ref/";

        @Override
        public String asString() {
            return PREFIX + instanceName + "/" + referenceName;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StorageRefKey storageRefKey(String instanceName, String referenceName) {
            return new StorageRefKey(instanceName, referenceName);
        }

        public static Result<StorageRefKey> storageRefKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STORAGE_REF_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return STORAGE_REF_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new StorageRefKey(content.substring(0, slashIndex), content.substring(slashIndex + 1)));
        }
    }

    record StorageStatusKey(NodeId nodeId, String instanceName) implements AetherKey {
        private static final String PREFIX = "storage-status/";

        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override
        public String asString() {
            return PREFIX + nodeId.id() + "/" + instanceName;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StorageStatusKey storageStatusKey(NodeId nodeId, String instanceName) {
            return new StorageStatusKey(nodeId, instanceName);
        }

        public static Result<StorageStatusKey> storageStatusKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STORAGE_STATUS_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.indexOf('/');

            if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
                return STORAGE_STATUS_KEY_FORMAT_ERROR.apply(key).result();
            }

            var nodeIdPart = content.substring(0, slashIndex);
            var instanceNamePart = content.substring(slashIndex + 1);

            return NodeId.nodeId(nodeIdPart).map(nid -> new StorageStatusKey(nid, instanceNamePart));
        }
    }

    record ClusterConfigKey(long configVersion) implements AetherKey {
        private static final String PREFIX = "cluster-config/";

        @Override
        public String asString() {
            return PREFIX + configVersion;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static final ClusterConfigKey CURRENT = new ClusterConfigKey(0);
        public static final ClusterConfigKey TEMPLATE = new ClusterConfigKey(-1);

        public static ClusterConfigKey clusterConfigKey(long configVersion) {
            return new ClusterConfigKey(configVersion);
        }

        public static Result<ClusterConfigKey> clusterConfigKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return CLUSTER_CONFIG_KEY_FORMAT_ERROR.apply(key).result();
            }

            var versionPart = key.substring(PREFIX.length());

            if (versionPart.isEmpty()) {
                return CLUSTER_CONFIG_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Number.parseLong(versionPart).map(ClusterConfigKey::new);
        }
    }

    record StreamConfigKey(String streamName) implements AetherKey {
        private static final String PREFIX = "stream-config/";

        @Override
        public String asString() {
            return PREFIX + streamName;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StreamConfigKey streamConfigKey(String streamName) {
            return new StreamConfigKey(streamName);
        }

        public static Result<StreamConfigKey> streamConfigKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {
                return STREAM_CONFIG_KEY_FORMAT_ERROR.apply(key).result();
            }

            var name = key.substring(PREFIX.length());

            if (name.isEmpty()) {
                return STREAM_CONFIG_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new StreamConfigKey(name));
        }
    }

    Fn1<Cause, String> STREAM_CONFIG_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-config key format: %s");

    Fn1<Cause, String> CLOUD_CREDENTIALS_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid cloud-credentials key format: %s");

    Fn1<Cause, String> CONSUMER_GROUP_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid consumer-group key format: %s");

    Fn1<Cause, String> API_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid api-key key format: %s");
    Fn1<Cause, String> API_KEY_AUDIT_FORMAT_ERROR = Causes.forOneValue("Invalid api-key-audit key format: %s");

    record ApiKeyKey(String keyId) implements AetherKey {
        private static final String PREFIX = "api-key/";

        @Override
        public String asString() {
            return PREFIX + keyId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static ApiKeyKey apiKeyKey(String keyId) {
            return new ApiKeyKey(keyId);
        }

        public static Result<ApiKeyKey> parseApiKeyKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return API_KEY_FORMAT_ERROR.apply(key).result();
            }

            var id = key.substring(PREFIX.length());

            if (id.isEmpty()) {
                return API_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new ApiKeyKey(id));
        }
    }

    record ApiKeyAuditKey(String entryId) implements AetherKey {
        private static final String PREFIX = "api-key-audit/";

        @Override
        public String asString() {
            return PREFIX + entryId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static ApiKeyAuditKey apiKeyAuditKey(String entryId) {
            return new ApiKeyAuditKey(entryId);
        }

        public static Result<ApiKeyAuditKey> parseApiKeyAuditKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return API_KEY_AUDIT_FORMAT_ERROR.apply(key).result();
            }

            var id = key.substring(PREFIX.length());

            if (id.isEmpty()) {
                return API_KEY_AUDIT_FORMAT_ERROR.apply(key).result();
            }

            return success(new ApiKeyAuditKey(id));
        }
    }

    record CloudCredentialsKey(String provider) implements AetherKey {
        private static final String PREFIX = "cloud-credentials/";

        @Override
        public String asString() {
            return PREFIX + provider;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static CloudCredentialsKey cloudCredentialsKey(String provider) {
            return new CloudCredentialsKey(provider);
        }

        public static Result<CloudCredentialsKey> parseCloudCredentialsKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return CLOUD_CREDENTIALS_KEY_FORMAT_ERROR.apply(key).result();
            }

            var provider = key.substring(PREFIX.length());

            if (provider.isEmpty()) {
                return CLOUD_CREDENTIALS_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new CloudCredentialsKey(provider));
        }
    }

    record DhtPartitionOwnershipKey(String partitionId) implements AetherKey {
        private static final String PREFIX = "dht-partition-ownership/";

        @Override
        public String asString() {
            return PREFIX + partitionId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static DhtPartitionOwnershipKey dhtPartitionOwnershipKey(String partitionId) {
            return new DhtPartitionOwnershipKey(partitionId);
        }

        public static Result<DhtPartitionOwnershipKey> parseDhtPartitionOwnershipKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return DHT_PARTITION_OWNERSHIP_KEY_FORMAT_ERROR.apply(key).result();
            }

            var partitionPart = key.substring(PREFIX.length());

            if (partitionPart.isEmpty()) {
                return DHT_PARTITION_OWNERSHIP_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new DhtPartitionOwnershipKey(partitionPart));
        }
    }

    /// Per-`(stream, partition)` ownership key (#345 item 1d-i) — the stream-side mirror of
    /// [DhtPartitionOwnershipKey]. The partition is the trailing path segment, so the stream name may
    /// itself contain `/` (`lastIndexOf('/')` splits it off); the partition is an `int`.
    record StreamPartitionOwnershipKey(String stream, int partition) implements AetherKey {
        private static final String PREFIX = "stream-partition-ownership/";

        @Override
        public String asString() {
            return PREFIX + stream + "/" + partition;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StreamPartitionOwnershipKey streamPartitionOwnershipKey(String stream, int partition) {
            return new StreamPartitionOwnershipKey(stream, partition);
        }

        public static Result<StreamPartitionOwnershipKey> parseStreamPartitionOwnershipKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STREAM_PARTITION_OWNERSHIP_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var slashIndex = content.lastIndexOf('/');

            if (slashIndex <= 0 || slashIndex == content.length() - 1) {
                return STREAM_PARTITION_OWNERSHIP_KEY_FORMAT_ERROR.apply(key).result();
            }

            var stream = content.substring(0, slashIndex);

            return Number.parseInt(content.substring(slashIndex + 1)).map(partition -> new StreamPartitionOwnershipKey(stream,
                                                                                                                       partition));
        }
    }

    record SpokesmanKey(NodeId coreNodeId) implements AetherKey {
        private static final String PREFIX = "spokesman/";

        @Override
        public String asString() {
            return PREFIX + coreNodeId.id();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static SpokesmanKey spokesmanKey(NodeId coreNodeId) {
            return new SpokesmanKey(coreNodeId);
        }

        public static Result<SpokesmanKey> spokesmanKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return SPOKESMAN_KEY_FORMAT_ERROR.apply(key).result();
            }

            var nodeIdPart = key.substring(PREFIX.length());

            if (nodeIdPart.isEmpty()) {
                return SPOKESMAN_KEY_FORMAT_ERROR.apply(key).result();
            }

            return NodeId.nodeId(nodeIdPart).map(SpokesmanKey::new);
        }
    }

    Fn1<Cause, String> DHT_PARTITION_OWNERSHIP_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid dht-partition-ownership key format: %s");

    Fn1<Cause, String> STREAM_PARTITION_OWNERSHIP_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-partition-ownership key format: %s");

    Fn1<Cause, String> SPOKESMAN_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid spokesman key format: %s");

    Fn1<Cause, String> PROVISIONING_SLOT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid provisioning-slot key format: %s");

    record ProvisioningSlotKey(String slotId) implements AetherKey {
        private static final String PREFIX = "provisioning-slot/";

        @Override
        public String asString() {
            return PREFIX + slotId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static ProvisioningSlotKey provisioningSlotKey(String slotId) {
            return new ProvisioningSlotKey(slotId);
        }

        public static Result<ProvisioningSlotKey> provisioningSlotKey(String key, boolean isKey) {
            if (!key.startsWith(PREFIX)) {
                return PROVISIONING_SLOT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var id = key.substring(PREFIX.length());

            if (id.isEmpty()) {
                return PROVISIONING_SLOT_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(new ProvisioningSlotKey(id));
        }
    }

    record ClusterPhaseKey() implements AetherKey {
        private static final String KEY = "cluster-phase";

        @SuppressWarnings("JBCT-VO-02")
        public static final ClusterPhaseKey SINGLETON = new ClusterPhaseKey();

        @Override
        public String asString() {
            return KEY;
        }

        @Override
        public String toString() {
            return asString();
        }

        @SuppressWarnings("JBCT-VO-02")
        public static ClusterPhaseKey clusterPhaseKey() {
            return SINGLETON;
        }

        public static Result<ClusterPhaseKey> clusterPhaseKey(String key) {
            if (!KEY.equals(key)) {
                return CLUSTER_PHASE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return success(SINGLETON);
        }
    }

    Fn1<Cause, String> CLUSTER_PHASE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid cluster-phase key format: %s");

    record ConsumerGroupKey(String groupId, String streamName, int partition) implements AetherKey {
        private static final String PREFIX = "consumer-group/";

        @Override
        public String asString() {
            return PREFIX + groupId + "/" + streamName + "/" + partition;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static ConsumerGroupKey consumerGroupKey(String groupId, String streamName, int partition) {
            return new ConsumerGroupKey(groupId, streamName, partition);
        }

        public static Result<ConsumerGroupKey> consumerGroupKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return CONSUMER_GROUP_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var parts = content.split("/");

            if (parts.length != 3) {
                return CONSUMER_GROUP_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Number.parseInt(parts[2]).map(partition -> new ConsumerGroupKey(parts[0], parts[1], partition));
        }
    }

    // Stream-namespaces rebuild: stream-registry key. The earlier rc1 transitional
    // `ClusterEventLogKey` (node-local cluster-event-log KV view) has since been removed —
    // cluster events now flow through the replicated `system:cluster-events` partition stream,
    // not a KV key. This record is the per-stream registry entry that replaced it.
    Fn1<Cause, String> STREAM_REGISTRY_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid stream-registry key format: %s");

    /// Per-stream registry entry persisted under `stream-registry/{namespace}/{stream}/{version}`.
    ///
    /// Carries the consensus-mediated reference count (§8.5) plus the metadata fields described
    /// in spec §7.1. Folds `stream-meta:{addr}` and `stream-refs:{addr}` from the spec into a
    /// single key/value pair so each refcount mutation is a single consensus command instead of
    /// two — the spec separation is conceptual; the implementation collapses them for atomic
    /// piggyback on the SliceNodeValue update.
    record StreamRegistryKey(ResourceAddress address) implements AetherKey {
        private static final String PREFIX = "stream-registry/";

        @Override
        public String asString() {
            return PREFIX + address.namespace()
                                   .value()
                 + "/" + address.name()
                                .value()
                 + "/" + address.version()
                                .asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static StreamRegistryKey streamRegistryKey(ResourceAddress address) {
            return new StreamRegistryKey(address);
        }

        public static Result<StreamRegistryKey> streamRegistryKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return STREAM_REGISTRY_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(PREFIX.length());
            var firstSlash = content.indexOf('/');

            if (firstSlash <= 0) {
                return STREAM_REGISTRY_KEY_FORMAT_ERROR.apply(key).result();
            }

            var namespace = content.substring(0, firstSlash);
            var rest = content.substring(firstSlash + 1);
            var secondSlash = rest.indexOf('/');

            if (secondSlash <= 0 || secondSlash == rest.length() - 1) {
                return STREAM_REGISTRY_KEY_FORMAT_ERROR.apply(key).result();
            }

            var stream = rest.substring(0, secondSlash);
            var version = rest.substring(secondSlash + 1);

            return ResourceAddress.resourceAddress(namespace, stream, version).map(StreamRegistryKey::new);
        }
    }

    // Stage 2 (stream-namespaces) additive graft: per-blueprint alias->ResourceAddress bindings.
    // Persistent/replicated deploy-time state (NOT ephemeral) — written by BlueprintService at
    // deploy and read by the per-slice runtime FSM to resolve refcount targets (spec §8.5).
    Fn1<Cause, String> BLUEPRINT_STREAM_BINDINGS_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid blueprint-stream-bindings key format: %s");

    /// Per-blueprint alias→ResourceAddress map persisted at deploy time so per-slice runtime FSM
    /// transitions (ACTIVE entry / DEACTIVATING / UNLOADING exit) can resolve the slice manifest's
    /// `stream.publisher.<i>.config` / `stream.access.<i>.config` aliases into fully-qualified
    /// `ResourceAddress` values without re-running blueprint validation.
    ///
    /// Spec reference: event-stream-namespaces §8.5 (consensus-mediated refcount accounting requires
    /// the resolved address per slice declaration).
    record BlueprintStreamBindingsKey(BlueprintId blueprintId) implements AetherKey {
        private static final String PREFIX = "blueprint-stream-bindings/";

        @Override
        public String asString() {
            return PREFIX + blueprintId.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static BlueprintStreamBindingsKey blueprintStreamBindingsKey(BlueprintId blueprintId) {
            return new BlueprintStreamBindingsKey(blueprintId);
        }

        public static Result<BlueprintStreamBindingsKey> blueprintStreamBindingsKey(String key) {
            if (!key.startsWith(PREFIX)) {
                return BLUEPRINT_STREAM_BINDINGS_KEY_FORMAT_ERROR.apply(key).result();
            }

            var idPart = key.substring(PREFIX.length());

            return BlueprintId.blueprintId(idPart).map(BlueprintStreamBindingsKey::new);
        }
    }

    /// Locates the newest fold checkpoint of one entity `(keyspace, partition)` (#345 I3).
    ///
    /// ## Why the pointer lives in consensus KV and the snapshot itself does not
    /// The checkpoint's BYTES go into stream storage, whose tier chain ends in a DHT tier, so the block
    /// is retrievable from any node by its content id. What is NOT cluster-visible is the NAME: stream
    /// storage's `MetadataStore` is in-memory and snapshotted to that node's own disk, and `SegmentIndex`
    /// is rebuilt at boot from that same local snapshot. A checkpoint recorded as a storage ref would
    /// therefore be findable only on the node that wrote it — useless in the one case a checkpoint
    /// exists for, which is a DIFFERENT node taking the partition over.
    ///
    /// So the small pointer goes where consensus makes it visible to everyone, and the large payload
    /// stays out of consensus. Splitting them this way keeps the consensus write to a few dozen bytes per
    /// checkpoint rather than the whole folded state.
    ///
    /// `keyspace` is the RAW name from `resources.toml`, matching [EntityKeyspaceRegistrationKey]; the
    /// `entity:` arc prefix belongs to `EntityPartitionArc` and is not stored here.
    record EntityCheckpointKey(String keyspace, int partition) implements AetherKey {
        private static final String PREFIX = "entity-checkpoint/";
        private static final String SEP = "/";

        @Override
        public String asString() {
            return PREFIX + keyspace + SEP + partition;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static EntityCheckpointKey entityCheckpointKey(String keyspace, int partition) {
            return new EntityCheckpointKey(keyspace, partition);
        }

        /// Rebuild from the snapshot IDENTITY (the part after the section prefix), which is
        /// `<keyspace>/<partition>`.
        ///
        /// Split on the LAST separator, not the first: a keyspace name is author-supplied and this key
        /// stores it raw, so splitting on the first separator would mis-parse any keyspace containing
        /// one. The partition is always the final component.
        public static Result<EntityCheckpointKey> fromIdentity(String identity) {
            var lastSep = identity.lastIndexOf(SEP);

            if (lastSep <= 0 || lastSep == identity.length() - 1) {
                return ENTITY_CHECKPOINT_KEY_FORMAT_ERROR.apply(PREFIX + identity).result();
            }

            var keyspace = identity.substring(0, lastSep);

            return Number.parseInt(identity.substring(lastSep + 1))
                         .mapError(_ -> ENTITY_CHECKPOINT_KEY_FORMAT_ERROR.apply(PREFIX + identity))
                         .map(partition -> new EntityCheckpointKey(keyspace, partition));
        }
    }
}
