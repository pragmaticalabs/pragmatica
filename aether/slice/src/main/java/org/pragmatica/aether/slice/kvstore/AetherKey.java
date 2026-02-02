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

    /// Slice target key format:
    /// ```
    /// slice-target/{groupId}:{artifactId}
    /// ```
    /// Stores runtime scaling targets for slices (instance count, current version, owning blueprint).
    record SliceTargetKey(ArtifactBase artifactBase) implements AetherKey {
        private static final String PREFIX = "slice-target/";

        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.SliceTargetPattern sliceTargetPattern -> sliceTargetPattern.matches(this);
                default -> false;
            };
        }

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
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.AppBlueprintPattern appBlueprintPattern -> appBlueprintPattern.matches(this);
                default -> false;
            };
        }

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
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.EndpointPattern endpointPattern -> endpointPattern.matches(this);
                default -> false;
            };
        }

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
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.VersionRoutingPattern versionRoutingPattern -> versionRoutingPattern.matches(this);
                default -> false;
            };
        }

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
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.RollingUpdatePattern rollingUpdatePattern -> rollingUpdatePattern.matches(this);
                default -> false;
            };
        }

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
            return Result.success(new RollingUpdateKey(updateId));
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
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.PreviousVersionPattern previousVersionPattern -> previousVersionPattern.matches(this);
                default -> false;
            };
        }

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
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.HttpRoutePattern httpRoutePattern -> httpRoutePattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return PREFIX + httpMethod + ":" + pathPrefix;
        }

        @Override
        public String toString() {
            return asString();
        }

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
            return Result.success(new HttpRouteKey(httpMethod, pathPrefix));
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

    /// Alert threshold key format:
    /// ```
    /// alert-threshold/{metricName}
    /// ```
    /// Stores alert threshold configuration for metrics.
    record AlertThresholdKey(String metricName) implements AetherKey {
        private static final String PREFIX = "alert-threshold/";

        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.AlertThresholdPattern alertThresholdPattern -> alertThresholdPattern.matches(this);
                default -> false;
            };
        }

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
            return Result.success(new AlertThresholdKey(metricName));
        }
    }

    Fn1<Cause, String> SLICE_TARGET_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice-target key format: %s");
    Fn1<Cause, String> APP_BLUEPRINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid app-blueprint key format: %s");
    Fn1<Cause, String> SLICE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid slice key format: %s");
    Fn1<Cause, String> ENDPOINT_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid endpoint key format: %s");
    Fn1<Cause, String> VERSION_ROUTING_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid version-routing key format: %s");
    Fn1<Cause, String> ROLLING_UPDATE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid rolling-update key format: %s");
    Fn1<Cause, String> PREVIOUS_VERSION_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid previous-version key format: %s");
    Fn1<Cause, String> HTTP_ROUTE_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid http-routes key format: %s");
    Fn1<Cause, String> ALERT_THRESHOLD_KEY_FORMAT_ERROR = Causes.forOneValue("Invalid alert-threshold key format: %s");

    /// Aether KV-Store structured patterns for key matching
    sealed interface AetherKeyPattern extends StructuredPattern {
        /// Pattern for slice-target keys: slice-target/*
        record SliceTargetPattern() implements AetherKeyPattern {
            public boolean matches(SliceTargetKey key) {
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

        /// Pattern for previous-version keys: previous-version/*
        record PreviousVersionPattern() implements AetherKeyPattern {
            public boolean matches(PreviousVersionKey key) {
                return true;
            }
        }

        /// Pattern for http-routes keys: http-routes/*
        record HttpRoutePattern() implements AetherKeyPattern {
            public boolean matches(HttpRouteKey key) {
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
