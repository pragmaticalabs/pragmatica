package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.cluster.state.kvstore.StructuredPattern;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

/// Aether KV-Store structured keys for cluster state management
public sealed interface AetherKey extends StructuredKey {
    /// String representation of the key
    String asString();

    /// Blueprint-key format (deprecated - use AppBlueprintKey):
    /// ```
    /// blueprint/{groupId}:{artifactId}:{version}
    /// ```
    @Deprecated
    record BlueprintKey(Artifact artifact) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.BlueprintPattern blueprintPattern -> blueprintPattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "blueprint/" + artifact.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<BlueprintKey> blueprintKey(String key) {
            if (!key.startsWith("blueprint/")) {
                return BLUEPRINT_KEY_FORMAT_ERROR.apply(key)
                                                 .result();
            }
            var artifactPart = key.substring(10);
            // Remove "blueprint/"
            return Artifact.artifact(artifactPart)
                           .map(BlueprintKey::new);
        }
    }

    /// Application blueprint key format:
    /// ```
    /// app-blueprint/{name}:{version}
    /// ```
    record AppBlueprintKey(BlueprintId blueprintId) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.AppBlueprintPattern appBlueprintPattern -> appBlueprintPattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "app-blueprint/" + blueprintId.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<AppBlueprintKey> appBlueprintKey(String key) {
            if (!key.startsWith("app-blueprint/")) {
                return APP_BLUEPRINT_KEY_FORMAT_ERROR.apply(key)
                                                     .result();
            }
            var blueprintIdPart = key.substring(14);
            // Remove "app-blueprint/"
            return BlueprintId.blueprintId(blueprintIdPart)
                              .map(AppBlueprintKey::new);
        }

        public static AppBlueprintKey appBlueprintKey(BlueprintId blueprintId) {
            return new AppBlueprintKey(blueprintId);
        }
    }

    /// Slice-node-key format:
    /// ```
    /// slices/{nodeId}/{groupId}:{artifactId}:{version}
    /// ```
    record SliceNodeKey(Artifact artifact, NodeId nodeId) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.SliceNodePattern sliceNodePattern -> sliceNodePattern.matches(this);
                default -> false;
            };
        }

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
            return Artifact.artifact(parts[2])
                           .map(artifact -> new SliceNodeKey(artifact,
                                                             NodeId.nodeId(parts[1])));
        }
    }

    /// Endpoint-key format (for slice instance endpoints):
    /// ```
    /// endpoints/{groupId}:{artifactId}:{version}/{methodName}:{instanceNumber}
    /// ```
    record EndpointKey(Artifact artifact, MethodName methodName, int instanceNumber) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.EndpointPattern endpointPattern -> endpointPattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "endpoints/" + artifact.asString() + "/" + methodName.name() + ":" + instanceNumber;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<EndpointKey> endpointKey(String key) {
            if (!key.startsWith("endpoints/")) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key)
                                                .result();
            }
            var content = key.substring(10);
            // Remove "endpoints/"
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

    /// Route key format:
    /// ```
    /// routes/{method}:{pathHash}
    /// ```
    /// Routes use path hash for key uniqueness while storing original pattern in value.
    record RouteKey(String method, int pathHash) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.RoutePattern routePattern -> routePattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "routes/" + method + ":" + pathHash;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static RouteKey routeKey(String method, String pathPattern) {
            return new RouteKey(method.toUpperCase(java.util.Locale.ROOT), pathPattern.hashCode());
        }

        public static Result<RouteKey> routeKey(String key) {
            if (!key.startsWith("routes/")) {
                return ROUTE_KEY_FORMAT_ERROR.apply(key)
                                             .result();
            }
            var content = key.substring(7);
            // Remove "routes/"
            var colonIndex = content.indexOf(':');
            if (colonIndex == - 1) {
                return ROUTE_KEY_FORMAT_ERROR.apply(key)
                                             .result();
            }
            var method = content.substring(0, colonIndex);
            var hashPart = content.substring(colonIndex + 1);
            return Number.parseInt(hashPart)
                         .map(hash -> new RouteKey(method, hash));
        }
    }

    /// Version routing key format:
    /// ```
    /// version-routing/{groupId}:{artifactId}
    /// ```
    /// Stores routing configuration between old and new versions during rolling updates.
    record VersionRoutingKey(ArtifactBase artifactBase) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.VersionRoutingPattern versionRoutingPattern -> versionRoutingPattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "version-routing/" + artifactBase.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static VersionRoutingKey versionRoutingKey(ArtifactBase artifactBase) {
            return new VersionRoutingKey(artifactBase);
        }

        public static Result<VersionRoutingKey> versionRoutingKey(String key) {
            if (!key.startsWith("version-routing/")) {
                return VERSION_ROUTING_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            var artifactBasePart = key.substring(16);
            // Remove "version-routing/"
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
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.RollingUpdatePattern rollingUpdatePattern -> rollingUpdatePattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "rolling-update/" + updateId;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static RollingUpdateKey rollingUpdateKey(String updateId) {
            return new RollingUpdateKey(updateId);
        }

        public static Result<RollingUpdateKey> parse(String key) {
            if (!key.startsWith("rolling-update/")) {
                return ROLLING_UPDATE_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            var updateId = key.substring(15);
            // Remove "rolling-update/"
            if (updateId.isEmpty()) {
                return ROLLING_UPDATE_KEY_FORMAT_ERROR.apply(key)
                                                      .result();
            }
            return Result.success(new RollingUpdateKey(updateId));
        }
    }

    /// Alert threshold key format:
    /// ```
    /// alert-threshold/{metricName}
    /// ```
    /// Stores alert threshold configuration for metrics.
    record AlertThresholdKey(String metricName) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.AlertThresholdPattern alertThresholdPattern -> alertThresholdPattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "alert-threshold/" + metricName;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static AlertThresholdKey alertThresholdKey(String metricName) {
            return new AlertThresholdKey(metricName);
        }

        public static Result<AlertThresholdKey> parse(String key) {
            if (!key.startsWith("alert-threshold/")) {
                return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            var metricName = key.substring(16);
            // Remove "alert-threshold/"
            if (metricName.isEmpty()) {
                return ALERT_THRESHOLD_KEY_FORMAT_ERROR.apply(key)
                                                       .result();
            }
            return Result.success(new AlertThresholdKey(metricName));
        }
    }

    Fn1<Cause, String>BLUEPRINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid blueprint key format: %s");
    Fn1<Cause, String>APP_BLUEPRINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid app-blueprint key format: %s");
    Fn1<Cause, String>SLICE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice key format: %s");
    Fn1<Cause, String>ENDPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid endpoint key format: %s");
    Fn1<Cause, String>ROUTE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid route key format: %s");
    Fn1<Cause, String>VERSION_ROUTING_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid version-routing key format: %s");
    Fn1<Cause, String>ROLLING_UPDATE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid rolling-update key format: %s");
    Fn1<Cause, String>ALERT_THRESHOLD_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid alert-threshold key format: %s");

    /// Aether KV-Store structured patterns for key matching
    sealed interface AetherKeyPattern extends StructuredPattern {
        /// Pattern for blueprint keys: blueprint/*
        record BlueprintPattern() implements AetherKeyPattern {
            public boolean matches(BlueprintKey key) {
                return true;
            }
        }

        /// Pattern for app-blueprint keys: app-blueprint/*
        record AppBlueprintPattern() implements AetherKeyPattern {
            public boolean matches(AppBlueprintKey key) {
                return true;
            }
        }

        /// Pattern for slice-node keys: slices/*/*
        record SliceNodePattern() implements AetherKeyPattern {
            public boolean matches(SliceNodeKey key) {
                return true;
            }
        }

        /// Pattern for endpoint keys: endpoints/*/*
        record EndpointPattern() implements AetherKeyPattern {
            public boolean matches(EndpointKey key) {
                return true;
            }
        }

        /// Pattern for route keys: routes/*
        record RoutePattern() implements AetherKeyPattern {
            public boolean matches(RouteKey key) {
                return true;
            }
        }

        /// Pattern for version-routing keys: version-routing/*
        record VersionRoutingPattern() implements AetherKeyPattern {
            public boolean matches(VersionRoutingKey key) {
                return true;
            }
        }

        /// Pattern for rolling-update keys: rolling-update/*
        record RollingUpdatePattern() implements AetherKeyPattern {
            public boolean matches(RollingUpdateKey key) {
                return true;
            }
        }

        /// Pattern for alert-threshold keys: alert-threshold/*
        record AlertThresholdPattern() implements AetherKeyPattern {
            public boolean matches(AlertThresholdKey key) {
                return true;
            }
        }
    }
}
