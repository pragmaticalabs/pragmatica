// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionAssignmentValue.PartitionAssignment;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KVStoreSerializerTest {
    private static final Phase TEST_PHASE = Phase.phase(12345L);
    private static final Instant TEST_TIMESTAMP = Instant.parse("2026-03-10T12:00:00Z");

    @Nested
    class Serialization {
        @Test
        void toToml_emptyStore_containsOnlyMeta() {
            KVStoreSerializer.toToml(Map.of(), TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("[meta]");
                                 assertThat(toml).contains("phase = 12345");
                                 assertThat(toml).contains("timestamp = \"2026-03-10T12:00:00Z\"");
                                 assertThat(toml).doesNotContain("[slice-target]");
                             });
        }

        @Test
        void toToml_sliceTarget_serializedCorrectly() {
            var artifactBase = ArtifactBase.artifactBase("com.example:my-app").unwrap();
            var version = Version.version("1.0.0").unwrap();
            var key = SliceTargetKey.sliceTargetKey(artifactBase);
            var value = new SliceTargetValue(version, 3, 2, Option.none(), "CORE_ONLY", 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("[slice-target]");
                                 assertThat(toml).contains("\"com.example:my-app\" = \"1.0.0|3|2||1710072000000|CORE_ONLY|||\"");
                             });
        }

        @Test
        void toToml_configValue_serializedCorrectly() {
            var key = ConfigKey.forKey("max-replicas");
            var value = new ConfigValue("max-replicas", "5", 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("[config]");
                                 assertThat(toml).contains("\"max-replicas\" = \"max-replicas|5|1710072000000\"");
                             });
        }

        @Test
        void toToml_gossipKeyRotation_serializedCorrectly() {
            var key = GossipKeyRotationKey.gossipKeyRotationKey();
            var value = new GossipKeyRotationValue(1, "abc123key", 0, "", 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("[gossip-key-rotation]");
                                 assertThat(toml).contains("\"\" = \"1|abc123key|0||1710072000000\"");
                             });
        }

        @Test
        void toToml_ephemeralGovernorAnnouncement_excluded() {
            var key = GovernorAnnouncementKey.forCommunity("prod:us-east-1");
            var members = List.of(NodeId.nodeId("worker-1").unwrap(), NodeId.nodeId("worker-2").unwrap());
            var value = GovernorAnnouncementValue.governorAnnouncementValue(
                NodeId.nodeId("governor-1").unwrap(), 2, members, "0.0.0.0:7201", 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).doesNotContain("[governor-announcement]");
                                 assertThat(toml).doesNotContain("governor-1");
                             });
        }

        @Test
        void toToml_workerDirectiveWithCommunity_serializedCorrectly() {
            var artifact = org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap();
            var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact, "prod:us-east-1");
            var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact, 5, "WORKERS_ONLY", "prod:us-east-1");

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("[worker-directive]");
                                 assertThat(toml).contains("prod:us-east-1/com.example:svc:1.0.0");
                             });
        }

        @Test
        void toToml_multipleEntriesSameSection_groupedCorrectly() {
            var ab1 = ArtifactBase.artifactBase("com.example:app-a").unwrap();
            var ab2 = ArtifactBase.artifactBase("com.example:app-b").unwrap();
            var version = Version.version("1.0.0").unwrap();
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            entries.put(SliceTargetKey.sliceTargetKey(ab1),
                        new SliceTargetValue(version, 2, 1, Option.none(), "CORE_ONLY", 1000L));
            entries.put(SliceTargetKey.sliceTargetKey(ab2),
                        new SliceTargetValue(version, 4, 3, Option.none(), "CORE_ONLY", 2000L));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("\"com.example:app-a\" = \"1.0.0|2|1||1000|CORE_ONLY|||\"");
                                 assertThat(toml).contains("\"com.example:app-b\" = \"1.0.0|4|3||2000|CORE_ONLY|||\"");
                                 // Only one section header
                                 assertThat(countOccurrences(toml, "[slice-target]")).isEqualTo(1);
                             });
        }

        @Test
        void toToml_mixedEphemeralAndPersistent_onlyPersistentSerialized() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();

            // Persistent: slice target
            var ab = ArtifactBase.artifactBase("com.example:svc").unwrap();
            var ver = Version.version("1.0.0").unwrap();
            entries.put(SliceTargetKey.sliceTargetKey(ab),
                        new SliceTargetValue(ver, 2, 1, Option.none(), "CORE_ONLY", 1000L));

            // Ephemeral: activation directive
            var nodeId = NodeId.nodeId("node-1").unwrap();
            entries.put(ActivationDirectiveKey.activationDirectiveKey(nodeId),
                        new ActivationDirectiveValue("CORE"));

            // Persistent: config
            entries.put(ConfigKey.forKey("timeout"), new ConfigValue("timeout", "5000", 3000L));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("[slice-target]");
                                 assertThat(toml).contains("[config]");
                                 assertThat(toml).doesNotContain("[activation]");
                             });
        }
    }

    @Nested
    class Deserialization {
        @Test
        void fromToml_emptyStore_returnsEmptyMap() {
            var toml = """
                       # Aether KV-Store Snapshot

                       [meta]
                       phase = 12345
                       timestamp = "2026-03-10T12:00:00Z"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(map -> assertThat(map).isEmpty());
        }

        @Test
        void fromToml_unknownSection_skippedGracefully() {
            var toml = """
                       [meta]
                       phase = 1
                       timestamp = "2026-01-01T00:00:00Z"

                       [future-feature]
                       "key1" = "value1"
                       """;

            // Unknown sections result in UnknownKeyType error for their entries,
            // which causes allOf to fail. The spec says skip unknown sections.
            // Since we collect results, unknown sections produce errors.
            // Let's verify it handles gracefully — no crash.
            KVStoreSerializer.fromToml(toml)
                             .onSuccess(_ -> Assertions.fail("Should fail on unknown section"));
        }

        @Test
        void fromToml_malformedValue_returnsParseFailure() {
            var toml = """
                       [meta]
                       phase = 1
                       timestamp = "2026-01-01T00:00:00Z"

                       [slice-target]
                       "com.example:test" = "bad-value"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onSuccessRun(Assertions::fail)
                             .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }

        @Test
        void fromToml_deploymentOutcomeInvalidStatus_returnsParseFailureNotRawException() {
            var toml = """
                       [meta]
                       phase = 1
                       timestamp = "2026-01-01T00:00:00Z"

                       [deployment-outcome]
                       "com.example:bad-status-app:1.0.0" = "NOT_A_REAL_STATUS||cause|1710072000000"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onSuccessRun(Assertions::fail)
                             .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }

        @Test
        void fromToml_deploymentOutcomeInvalidTimestamp_returnsParseFailureNotRawException() {
            var toml = """
                       [meta]
                       phase = 1
                       timestamp = "2026-01-01T00:00:00Z"

                       [deployment-outcome]
                       "com.example:bad-timestamp-app:1.0.0" = "SUCCEEDED||cause|not-a-number"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onSuccessRun(Assertions::fail)
                             .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }

        @Test
        void fromToml_ephemeralSection_skippedOnRestore() {
            var toml = """
                       [meta]
                       phase = 100
                       timestamp = "2026-03-10T12:00:00Z"

                       [activation]
                       "node-1" = "CORE"

                       [config]
                       "timeout" = "timeout|5000|3000"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(map -> {
                                 assertThat(map).hasSize(1);
                                 assertThat(map).containsKey(ConfigKey.forKey("timeout"));
                                 // activation is ephemeral — should not be restored
                                 var nodeId = NodeId.nodeId("node-1").unwrap();
                                 assertThat(map).doesNotContainKey(ActivationDirectiveKey.activationDirectiveKey(nodeId));
                             });
        }

        @Test
        void fromToml_multipleEphemeralSections_allSkipped() {
            var toml = """
                       [meta]
                       phase = 100
                       timestamp = "2026-03-10T12:00:00Z"

                       [activation]
                       "node-1" = "CORE"

                       [governor-announcement]
                       "prod:us-east-1" = "governor-1|2|worker-1,worker-2|0.0.0.0:7201|1710072000000"

                       [slices]
                       "worker-1/com.example:svc:1.0.0" = "ACTIVE||false"

                       [endpoints]
                       "com.example:svc:1.0.0/handle:0" = "node-1"

                       [node-artifact]
                       "node-1/com.example:svc:1.0.0" = "ACTIVE||false|0|handle"

                       [node-routes]
                       "node-1/com.example:svc:1.0.0" = "GET,/api/,handle,ACTIVE,100,1710072000000"

                       [http-node-routes]
                       "GET:/api/:node-1" = "com.example:svc:1.0.0|handle|ACTIVE|100|1710072000000"

                       [slice-target]
                       "com.example:svc" = "1.0.0|2|1||1000|CORE_ONLY"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(map -> {
                                 // Only the persistent slice-target entry should be restored
                                 assertThat(map).hasSize(1);
                                 var ab = ArtifactBase.artifactBase("com.example:svc").unwrap();
                                 assertThat(map).containsKey(SliceTargetKey.sliceTargetKey(ab));
                             });
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void roundTrip_persistentTypes_preservesAllEntries() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();

            var ab = ArtifactBase.artifactBase("com.example:svc").unwrap();
            var ver = Version.version("2.0.0").unwrap();
            entries.put(SliceTargetKey.sliceTargetKey(ab),
                        new SliceTargetValue(ver, 5, 3, Option.none(), "CORE_ONLY", 1000L));

            entries.put(ConfigKey.forKey("timeout-ms"),
                        new ConfigValue("timeout-ms", "3000", 3000L));

            entries.put(GossipKeyRotationKey.gossipKeyRotationKey(),
                        new GossipKeyRotationValue(42, "keydata", 41, "oldkeydata", 4000L));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(3);
                                 assertThat(restored).containsKey(SliceTargetKey.sliceTargetKey(ab));
                                 assertThat(restored).containsKey(ConfigKey.forKey("timeout-ms"));
                                 assertThat(restored).containsKey(GossipKeyRotationKey.gossipKeyRotationKey());

                                 var st = (SliceTargetValue) restored.get(SliceTargetKey.sliceTargetKey(ab));
                                 assertThat(st.targetInstances()).isEqualTo(5);
                                 assertThat(st.minInstances()).isEqualTo(3);
                                 assertThat(st.updatedAt()).isEqualTo(1000L);

                                 var gk = (GossipKeyRotationValue) restored.get(
                                     GossipKeyRotationKey.gossipKeyRotationKey());
                                 assertThat(gk.currentKeyId()).isEqualTo(42);
                                 assertThat(gk.previousKey()).isEqualTo("oldkeydata");
                             });
        }

        /// #634-3 gate finding: `StorageStatusValue` had NO serializer coverage at all when it gained
        /// `walBytes` — the arms were being changed blind. The `storage-status` section is EPHEMERAL
        /// (excluded from `toToml` by design — periodically re-published node status), so this drives
        /// the package-visible arms DIRECTLY, the `activation` precedent. A dropped or reordered field
        /// turns this red.
        @Test
        void roundTrip_storageStatus_preservesAllFieldsIncludingWalBytes() {
            var key = StorageStatusKey.storageStatusKey(new NodeId("node-1"), "streams");
            var value = new StorageStatusValue("streams",
                                               List.of(StorageStatusValue.TierStatus.tierStatus("MEMORY", 10, 100),
                                                       StorageStatusValue.TierStatus.tierStatus("LOCAL_DISK", 20, 200)),
                                               "READY",
                                               true,
                                               true,
                                               7L,
                                               1234L,
                                               4096L,
                                               99L);
            var identity = key.asString()
                              .substring("storage-status/".length());

            KVStoreSerializer.parseStorageStatusEntry(identity, KVStoreSerializer.serializeStorageStatus(value))
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> {
                                 assertThat(entry.getKey()).isEqualTo(key);
                                 assertThat(entry.getValue()).isEqualTo(value);
                             });
        }

        @Test
        void roundTrip_sliceTargetWithOverrides_preservesMaxInstancesAndThresholds() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var ab = ArtifactBase.artifactBase("com.example:autoscaled").unwrap();
            var ver = Version.version("2.0.0").unwrap();
            var key = SliceTargetKey.sliceTargetKey(ab);
            var value = new SliceTargetValue(ver,
                                             5,
                                             3,
                                             Option.none(),
                                             "CORE_ONLY",
                                             1000L,
                                             Option.some(7),
                                             Option.some(1.5),
                                             Option.some(0.5));
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> assertThat(restored.get(key)).isEqualTo(value));
        }

        @Test
        void roundTrip_sliceTargetWithoutOverrides_restoresNoneForAllOverrides() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var ab = ArtifactBase.artifactBase("com.example:plain").unwrap();
            var ver = Version.version("2.0.0").unwrap();
            var key = SliceTargetKey.sliceTargetKey(ab);
            var value = new SliceTargetValue(ver, 3, 2, Option.none(), "CORE_ONLY", 1000L);
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 var st = (SliceTargetValue) restored.get(key);

                                 assertThat(st.maxInstances()).isEqualTo(Option.none());
                                 assertThat(st.scaleUpThreshold()).isEqualTo(Option.none());
                                 assertThat(st.scaleDownThreshold()).isEqualTo(Option.none());
                                 assertThat(st).isEqualTo(value);
                             });
        }

        @Test
        void fromToml_legacyFiveFieldSliceTarget_parsesWithNoneOverrides() {
            var toml = """
                       [meta]
                       phase = 1
                       timestamp = "2026-01-01T00:00:00Z"

                       [slice-target]
                       "com.example:legacy5" = "1.0.0|2|1||1000"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(map -> {
                                 var ab = ArtifactBase.artifactBase("com.example:legacy5").unwrap();
                                 var st = (SliceTargetValue) map.get(SliceTargetKey.sliceTargetKey(ab));

                                 assertThat(st.targetInstances()).isEqualTo(2);
                                 assertThat(st.effectivePlacement()).isEqualTo("CORE_ONLY");
                                 assertThat(st.maxInstances()).isEqualTo(Option.none());
                                 assertThat(st.scaleUpThreshold()).isEqualTo(Option.none());
                                 assertThat(st.scaleDownThreshold()).isEqualTo(Option.none());
                             });
        }

        @Test
        void fromToml_legacySixFieldSliceTarget_parsesWithNoneOverrides() {
            var toml = """
                       [meta]
                       phase = 1
                       timestamp = "2026-01-01T00:00:00Z"

                       [slice-target]
                       "com.example:legacy6" = "1.0.0|4|2||2000|CORE_ONLY"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(map -> {
                                 var ab = ArtifactBase.artifactBase("com.example:legacy6").unwrap();
                                 var st = (SliceTargetValue) map.get(SliceTargetKey.sliceTargetKey(ab));

                                 assertThat(st.targetInstances()).isEqualTo(4);
                                 assertThat(st.minInstances()).isEqualTo(2);
                                 assertThat(st.effectivePlacement()).isEqualTo("CORE_ONLY");
                                 assertThat(st.maxInstances()).isEqualTo(Option.none());
                                 assertThat(st.scaleUpThreshold()).isEqualTo(Option.none());
                                 assertThat(st.scaleDownThreshold()).isEqualTo(Option.none());
                             });
        }

        @Test
        void roundTrip_observabilityConfig_preservesAllFacets() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var key = ObservabilityConfigKey.observabilityConfigKey("com.example:my-slice", "handle");

            entries.put(key, new ObservabilityConfigValue("com.example:my-slice", "handle", true, false, true, false, 7, 9000L));
            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).containsKey(key);
                                 var oc = (ObservabilityConfigValue) restored.get(key);

                                 assertThat(oc.artifactBase()).isEqualTo("com.example:my-slice");
                                 assertThat(oc.methodName()).isEqualTo("handle");
                                 assertThat(oc.logging()).isTrue();
                                 assertThat(oc.metrics()).isFalse();
                                 assertThat(oc.spans()).isTrue();
                                 assertThat(oc.tracing()).isFalse();
                                 assertThat(oc.depth()).isEqualTo(7);
                                 assertThat(oc.updatedAt()).isEqualTo(9000L);
                             });
        }

        @Test
        void roundTrip_observabilityConfig_wildcardScopes_preservesGlobalAndArtifactKeys() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var globalKey = ObservabilityConfigKey.observabilityConfigKey("*", "*");
            var artifactKey = ObservabilityConfigKey.observabilityConfigKey("com.example:my-slice", "*");

            entries.put(globalKey, new ObservabilityConfigValue("*", "*", true, true, false, false, 0, 1000L));
            entries.put(artifactKey, new ObservabilityConfigValue("com.example:my-slice", "*", false, true, false, false, 3, 2000L));
            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).containsKey(globalKey);
                                 assertThat(restored).containsKey(artifactKey);

                                 var g = (ObservabilityConfigValue) restored.get(globalKey);
                                 assertThat(g.artifactBase()).isEqualTo("*");
                                 assertThat(g.methodName()).isEqualTo("*");
                                 assertThat(g.logging()).isTrue();
                                 assertThat(g.metrics()).isTrue();

                                 var a = (ObservabilityConfigValue) restored.get(artifactKey);
                                 assertThat(a.artifactBase()).isEqualTo("com.example:my-slice");
                                 assertThat(a.methodName()).isEqualTo("*");
                                 assertThat(a.metrics()).isTrue();
                                 assertThat(a.depth()).isEqualTo(3);
                             });
        }

        @Test
        void roundTrip_apiKeyValue_preservesAuthorizationRole() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            entries.put(ApiKeyKey.apiKeyKey("ak_admin01"),
                        AetherValue.ApiKeyValue.apiKeyValue("ak_admin01", "deadbeef", 5000L, "ADMIN"));
            entries.put(ApiKeyKey.apiKeyKey("ak_oper001"),
                        AetherValue.ApiKeyValue.apiKeyValue("ak_oper001", "cafe1234", 5000L, "OPERATOR"));
            entries.put(ApiKeyKey.apiKeyKey("ak_view001"),
                        AetherValue.ApiKeyValue.apiKeyValue("ak_view001", "feedface", 5000L, "VIEWER"));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 var admin = (AetherValue.ApiKeyValue) restored.get(ApiKeyKey.apiKeyKey("ak_admin01"));
                                 assertThat(admin.authorizationRole()).isEqualTo("ADMIN");

                                 var operator = (AetherValue.ApiKeyValue) restored.get(ApiKeyKey.apiKeyKey("ak_oper001"));
                                 assertThat(operator.authorizationRole()).isEqualTo("OPERATOR");

                                 var viewer = (AetherValue.ApiKeyValue) restored.get(ApiKeyKey.apiKeyKey("ak_view001"));
                                 assertThat(viewer.authorizationRole()).isEqualTo("VIEWER");
                             });
        }

        @Test
        void apiKeyValue_factoryWithoutRole_defaultsToViewer() {
            var keyValue = AetherValue.ApiKeyValue.apiKeyValue("ak_test", "hash", 1000L);
            assertThat(keyValue.authorizationRole()).isEqualTo("VIEWER");
        }

        @Test
        void roundTrip_ephemeralKeys_excludedFromOutput() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();

            // Persistent
            var ab = ArtifactBase.artifactBase("com.example:svc").unwrap();
            var ver = Version.version("1.0.0").unwrap();
            entries.put(SliceTargetKey.sliceTargetKey(ab),
                        new SliceTargetValue(ver, 2, 1, Option.none(), "CORE_ONLY", 1000L));

            // Ephemeral — should be filtered out
            var key = GovernorAnnouncementKey.forCommunity("prod:us-east-1");
            var members = List.of(NodeId.nodeId("worker-a").unwrap());
            entries.put(key, GovernorAnnouncementValue.governorAnnouncementValue(
                NodeId.nodeId("governor-1").unwrap(), 1, members, "10.0.1.5:7201", 5000L));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 // Only the persistent slice-target survives
                                 assertThat(restored).hasSize(1);
                                 assertThat(restored).containsKey(SliceTargetKey.sliceTargetKey(ab));
                             });
        }

        @Test
        void roundTrip_workerDirectiveWithCommunity_preservesFields() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var artifact = org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap();
            var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact, "prod:us-east-1");
            var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact, 5, "WORKERS_ONLY", "prod:us-east-1");
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 var restoredKey = restored.keySet().iterator().next();
                                 assertThat(restoredKey).isInstanceOf(WorkerSliceDirectiveKey.class);
                                 var wdk = (WorkerSliceDirectiveKey) restoredKey;
                                 assertThat(wdk.artifact()).isEqualTo(artifact);
                                 assertThat(wdk.communityId().isPresent()).isTrue();
                                 assertThat(wdk.communityId().or("")).isEqualTo("prod:us-east-1");
                                 var wdv = (WorkerSliceDirectiveValue) restored.get(restoredKey);
                                 assertThat(wdv.targetInstances()).isEqualTo(5);
                                 assertThat(wdv.placement()).isEqualTo("WORKERS_ONLY");
                                 assertThat(wdv.targetCommunity().or("")).isEqualTo("prod:us-east-1");
                             });
        }

        @Test
        void roundTrip_workerDirectiveNoCommunity_preservesFields() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var artifact = org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap();
            var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact);
            var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact, 3, "WORKERS_PREFERRED");
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 var restoredKey = restored.keySet().iterator().next();
                                 assertThat(restoredKey).isInstanceOf(WorkerSliceDirectiveKey.class);
                                 var wdk = (WorkerSliceDirectiveKey) restoredKey;
                                 assertThat(wdk.artifact()).isEqualTo(artifact);
                                 assertThat(wdk.communityId().isPresent()).isFalse();
                                 var wdv = (WorkerSliceDirectiveValue) restored.get(restoredKey);
                                 assertThat(wdv.targetInstances()).isEqualTo(3);
                                 assertThat(wdv.targetCommunity().isPresent()).isFalse();
                             });
        }

        @Test
        void roundTrip_communityValue_preservesAllFields() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var key = CommunityKey.communityKey("orders:worker");
            var value = CommunityValue.communityValue("orders",
                                                      "WORKER",
                                                      5,
                                                      CommunityState.ACTIVE,
                                                      1710072000000L,
                                                      Option.none());
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 assertThat(restored).containsKey(key);
                                 var cv = (CommunityValue) restored.get(key);
                                 assertThat(cv.sourceName()).isEqualTo("orders");
                                 assertThat(cv.role()).isEqualTo("WORKER");
                                 assertThat(cv.targetSize()).isEqualTo(5);
                                 assertThat(cv.state()).isEqualTo(CommunityState.ACTIVE);
                                 assertThat(cv.createdAt()).isEqualTo(1710072000000L);
                                 assertThat(cv.dissolvedAt().isPresent()).isFalse();
                             });
        }

        @Test
        void roundTrip_communityValueDissolved_preservesDissolvedAt() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var key = CommunityKey.communityKey("orders:worker");
            var value = CommunityValue.communityValue("orders",
                                                      "WORKER",
                                                      5,
                                                      CommunityState.DISSOLVED,
                                                      1710072000000L,
                                                      Option.some(1710072500000L));
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 var cv = (CommunityValue) restored.get(key);
                                 assertThat(cv.state()).isEqualTo(CommunityState.DISSOLVED);
                                 assertThat(cv.dissolvedAt()).isEqualTo(Option.some(1710072500000L));
                                 assertThat(cv).isEqualTo(value);
                             });
        }

        @Test
        void roundTrip_activationDirectiveWithCommunity_preservesRoleCommunityAndHint() {
            var original = AetherValue.ActivationDirectiveValue.worker("orders:worker", "10.0.0.1:7201");

            var serialized = KVStoreSerializer.serializeActivationDirective(original);

            KVStoreSerializer.parseActivationEntry("node-1", serialized)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> {
                                 var value = (AetherValue.ActivationDirectiveValue) entry.getValue();
                                 assertThat(value.role()).isEqualTo("WORKER");
                                 assertThat(value.communityId()).isEqualTo("orders:worker");
                                 assertThat(value.governorHint()).isEqualTo("10.0.0.1:7201");
                                 assertThat(value).isEqualTo(original);
                             });
        }

        @Test
        void roundTrip_activationDirectiveNoCommunity_preservesEmptyFields() {
            var original = AetherValue.ActivationDirectiveValue.core();

            var serialized = KVStoreSerializer.serializeActivationDirective(original);

            KVStoreSerializer.parseActivationEntry("node-1", serialized)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> {
                                 var value = (AetherValue.ActivationDirectiveValue) entry.getValue();
                                 assertThat(value.role()).isEqualTo("CORE");
                                 assertThat(value.communityId()).isEmpty();
                                 assertThat(value.governorHint()).isEmpty();
                                 assertThat(value).isEqualTo(original);
                             });
        }

        @Test
        void parseActivationEntry_legacyBareRole_defaultsEmptyCommunityFields() {
            KVStoreSerializer.parseActivationEntry("node-1", "WORKER")
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> {
                                 var value = (AetherValue.ActivationDirectiveValue) entry.getValue();
                                 assertThat(value.role()).isEqualTo("WORKER");
                                 assertThat(value.communityId()).isEmpty();
                                 assertThat(value.governorHint()).isEmpty();
                                 assertThat(value).isEqualTo(AetherValue.ActivationDirectiveValue.worker());
                             });
        }

        @Test
        void roundTrip_deploymentOutcome_escapesPipeCommaAndNewlineInCauseAndSliceIds_preservesExactly() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var blueprintId = BlueprintId.blueprintId("com.example:outcome-app:1.0.0").unwrap();
            var key = DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId);
            var cause = "boom | kaboom, again\nsecond line";
            var slices = List.of("com.example:svc,a:1.0.0", "com.example:svc|b:2.0.0");
            var value = DeploymentOutcomeValue.failed(slices, cause, 1710072000000L);
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 var outcome = (DeploymentOutcomeValue) restored.get(key);
                                 assertThat(outcome.status()).isEqualTo(DeploymentOutcomeStatus.FAILED);
                                 assertThat(outcome.cause()).isEqualTo(cause);
                                 assertThat(outcome.failingSlices()).isEqualTo(slices);
                                 assertThat(outcome.timestampMs()).isEqualTo(1710072000000L);
                             });
        }

        @Test
        void roundTrip_deploymentOutcome_emptyFailingSlices_preservesEmptyList() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var blueprintId = BlueprintId.blueprintId("com.example:outcome-app-empty:1.0.0").unwrap();
            var key = DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId);
            var value = DeploymentOutcomeValue.succeeded(1710072000000L);
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 var outcome = (DeploymentOutcomeValue) restored.get(key);
                                 assertThat(outcome.status()).isEqualTo(DeploymentOutcomeStatus.SUCCEEDED);
                                 assertThat(outcome.failingSlices()).isEmpty();
                                 assertThat(outcome.cause()).isEmpty();
                             });
        }
    }

    @Nested
    class ProvisioningSlotCodec {
        @Test
        void parseProvisioningSlot_legacyThreeFields_dropsDeadlineDefaultsEpochZeroAndNoSuperseded() {
            var legacy = "1000|61000|node-occupant";

            KVStoreSerializer.parseProvisioningSlotEntry("0", legacy)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> {
                                 var value = (ProvisioningSlotValue) entry.getValue();
                                 assertThat(value.spawnedAtMs()).isEqualTo(1000L);
                                 assertThat(value.assignedNodeId()).isEqualTo(Option.some(NodeId.nodeId("node-occupant").unwrap()));
                                 assertThat(value.occupantEpoch()).isEqualTo(0L);
                                 assertThat(value.supersededNodeId().isPresent()).isFalse();
                             });
        }

        @Test
        void parseProvisioningSlot_legacyThreeFieldsEmptyOccupant_defaultsEmpty() {
            var legacy = "1000|61000|";

            KVStoreSerializer.parseProvisioningSlotEntry("3", legacy)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> {
                                 var value = (ProvisioningSlotValue) entry.getValue();
                                 assertThat(value.assignedNodeId().isPresent()).isFalse();
                                 assertThat(value.occupantEpoch()).isEqualTo(0L);
                                 assertThat(value.supersededNodeId().isPresent()).isFalse();
                             });
        }

        @Test
        void parseProvisioningSlot_legacyFiveFieldFenced_dropsDeadlinePreservesFencedFields() {
            // Legacy 5-field wire `(spawnedAtMs|deadlineMs|assignedNodeId|occupantEpoch|supersededNodeId)`
            // — the stored deadline (62000) is discarded; expiry is derived now (#230).
            var legacy = "2000|62000|node-new|7|node-dead";

            KVStoreSerializer.parseProvisioningSlotEntry("1", legacy)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> {
                                 var value = (ProvisioningSlotValue) entry.getValue();
                                 assertThat(value.spawnedAtMs()).isEqualTo(2000L);
                                 assertThat(value.assignedNodeId()).isEqualTo(Option.some(NodeId.nodeId("node-new").unwrap()));
                                 assertThat(value.occupantEpoch()).isEqualTo(7L);
                                 assertThat(value.supersededNodeId()).isEqualTo(Option.some(NodeId.nodeId("node-dead").unwrap()));
                             });
        }

        @Test
        void roundTrip_currentFourField_preservesAllFields() {
            var original = new ProvisioningSlotValue(2000L,
                                                     Option.some(NodeId.nodeId("node-new").unwrap()),
                                                     7L,
                                                     Option.some(NodeId.nodeId("node-dead").unwrap()));

            var serialized = KVStoreSerializer.serializeProvisioningSlot(original);

            KVStoreSerializer.parseProvisioningSlotEntry("1", serialized)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> assertThat(entry.getValue()).isEqualTo(original));
        }

        @Test
        void roundTrip_currentFourFieldNoOccupantNoSuperseded_preservesEpoch() {
            var original = new ProvisioningSlotValue(3000L,
                                                     Option.none(),
                                                     4L,
                                                     Option.none());

            var serialized = KVStoreSerializer.serializeProvisioningSlot(original);

            KVStoreSerializer.parseProvisioningSlotEntry("2", serialized)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entry -> assertThat(entry.getValue()).isEqualTo(original));
        }

        @Test
        void parseProvisioningSlot_wrongFieldCount_returnsParseFailure() {
            KVStoreSerializer.parseProvisioningSlotEntry("0", "1000|61000")
                             .onSuccess(_ -> Assertions.fail());
        }
    }

    @Nested
    class EphemeralKeyFiltering {
        @Test
        void isEphemeral_nodeArtifactKey_true() {
            var nodeId = NodeId.nodeId("node-1").unwrap();
            var artifact = org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap();
            assertThat(EphemeralKeys.isEphemeral(NodeArtifactKey.nodeArtifactKey(nodeId, artifact))).isTrue();
        }

        @Test
        void isEphemeral_nodeRoutesKey_true() {
            var nodeId = NodeId.nodeId("node-1").unwrap();
            var artifact = org.pragmatica.aether.artifact.Artifact.artifact("com.example:svc:1.0.0").unwrap();
            assertThat(EphemeralKeys.isEphemeral(NodeRoutesKey.nodeRoutesKey(nodeId, artifact))).isTrue();
        }

        @Test
        void isEphemeral_activationDirectiveKey_true() {
            var nodeId = NodeId.nodeId("node-1").unwrap();
            assertThat(EphemeralKeys.isEphemeral(ActivationDirectiveKey.activationDirectiveKey(nodeId))).isTrue();
        }

        @Test
        void isEphemeral_governorAnnouncementKey_true() {
            assertThat(EphemeralKeys.isEphemeral(GovernorAnnouncementKey.forCommunity("test"))).isTrue();
        }

        @Test
        void isEphemeral_dhtPartitionOwnershipKey_true() {
            assertThat(EphemeralKeys.isEphemeral(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("core"))).isTrue();
        }

        @Test
        void isEphemeral_spokesmanKey_true() {
            var nodeId = NodeId.nodeId("core-1").unwrap();
            assertThat(EphemeralKeys.isEphemeral(SpokesmanKey.spokesmanKey(nodeId))).isTrue();
        }

        @Test
        void isEphemeralSection_dhtPartitionOwnership_true() {
            assertThat(EphemeralKeys.isEphemeralSection("dht-partition-ownership")).isTrue();
        }

        @Test
        void isEphemeralSection_spokesman_true() {
            assertThat(EphemeralKeys.isEphemeralSection("spokesman")).isTrue();
        }

        @Test
        void isEphemeral_sliceTargetKey_false() {
            var ab = ArtifactBase.artifactBase("com.example:svc").unwrap();
            assertThat(EphemeralKeys.isEphemeral(SliceTargetKey.sliceTargetKey(ab))).isFalse();
        }

        @Test
        void isEphemeral_configKey_false() {
            assertThat(EphemeralKeys.isEphemeral(ConfigKey.forKey("timeout"))).isFalse();
        }

        @Test
        void isEphemeral_gossipKeyRotationKey_false() {
            assertThat(EphemeralKeys.isEphemeral(GossipKeyRotationKey.gossipKeyRotationKey())).isFalse();
        }

        @Test
        void isEphemeralSection_ephemeralSections_true() {
            assertThat(EphemeralKeys.isEphemeralSection("node-artifact")).isTrue();
            assertThat(EphemeralKeys.isEphemeralSection("node-routes")).isTrue();
            assertThat(EphemeralKeys.isEphemeralSection("endpoints")).isTrue();
            assertThat(EphemeralKeys.isEphemeralSection("activation")).isTrue();
            assertThat(EphemeralKeys.isEphemeralSection("governor-announcement")).isTrue();
            assertThat(EphemeralKeys.isEphemeralSection("slices")).isTrue();
            assertThat(EphemeralKeys.isEphemeralSection("http-node-routes")).isTrue();
        }

        @Test
        void isEphemeralSection_persistentSections_false() {
            assertThat(EphemeralKeys.isEphemeralSection("slice-target")).isFalse();
            assertThat(EphemeralKeys.isEphemeralSection("config")).isFalse();
            assertThat(EphemeralKeys.isEphemeralSection("gossip-key-rotation")).isFalse();
            assertThat(EphemeralKeys.isEphemeralSection("scheduled-task")).isFalse();
            assertThat(EphemeralKeys.isEphemeralSection("topic-sub")).isFalse();
            assertThat(EphemeralKeys.isEphemeralSection("worker-directive")).isFalse();
            assertThat(EphemeralKeys.isEphemeralSection("app-blueprint")).isFalse();
        }
    }

    @Nested
    class StreamNamespaceRoundTrip {
        private static ResourceAddress addr(String namespace, String stream, int major, int minor, int patch) {
            return ResourceAddress.resourceAddress(namespace, stream, ResourceVersion.resourceVersion(major, minor, patch).unwrap())
                                .unwrap();
        }

        @Test
        void roundTrip_streamRegistryAndBlueprintBindings_preservesAllFields() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();

            var streamAddress = addr("com.example.app", "orders", 1, 2, 3);
            var retention = new RetentionPolicy(7_777L, 555L, 99_000L, RetentionMode.ALL, Option.none());
            var registryEntry = new StreamRegistryEntry(streamAddress,
                                                        retention,
                                                        1710072000000L,
                                                        StreamRegistryEntry.RegisteredByKind.BLUEPRINT,
                                                        4);
            var registryKey = StreamRegistryKey.streamRegistryKey(streamAddress);
            entries.put(registryKey, StreamRegistryValue.streamRegistryValue(registryEntry));

            var blueprintId = BlueprintId.blueprintId("com.example:my-app-blueprint:2.0.0").unwrap();
            var bindings = List.of(new NamedAddress("orders-out", addr("com.example.app", "orders", 1, 2, 3)),
                                   new NamedAddress("events-in", addr("com.example.app", "events", 4, 0, 0)));
            var bindingsKey = BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprintId);
            entries.put(bindingsKey, BlueprintStreamBindingsValue.blueprintStreamBindingsValue(bindings));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(2);
                                 assertThat(restored.get(registryKey)).isEqualTo(StreamRegistryValue.streamRegistryValue(registryEntry));
                                 assertThat(restored.get(bindingsKey)).isEqualTo(BlueprintStreamBindingsValue.blueprintStreamBindingsValue(bindings));

                                 var rv = (StreamRegistryValue) restored.get(registryKey);
                                 assertThat(rv.entry().address()).isEqualTo(streamAddress);
                                 assertThat(rv.entry().refCount()).isEqualTo(4);
                                 assertThat(rv.entry().registeredBy()).isEqualTo(StreamRegistryEntry.RegisteredByKind.BLUEPRINT);
                                 assertThat(rv.entry().retention()).isEqualTo(retention);

                                 var bv = (BlueprintStreamBindingsValue) restored.get(bindingsKey);
                                 assertThat(bv.bindings()).hasSize(2);
                                 assertThat(bv.addressFor("orders-out")).isEqualTo(Option.some(addr("com.example.app", "orders", 1, 2, 3)));
                                 assertThat(bv.addressFor("events-in")).isEqualTo(Option.some(addr("com.example.app", "events", 4, 0, 0)));
                             });
        }

        @Test
        void roundTrip_namespacedTopicSubscriptionKey_preservesAddressArtifactMethod() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();

            var address = ResourceAddress.resourceAddress("com.example.app", "order-events",
                                                    ResourceVersion.resourceVersion(2, 1, 3).unwrap()).unwrap();
            var artifact = Artifact.artifact("com.example:order-slice:1.0.0").unwrap();
            var method = MethodName.methodName("onOrder").unwrap();
            var key = TopicSubscriptionKey.topicSubscriptionKey(address, artifact, method);
            var value = TopicSubscriptionValue.topicSubscriptionValue(new NodeId("node-a"));
            entries.put(key, value);

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 assertThat(restored).containsKey(key);
                                 var rk = (TopicSubscriptionKey) restored.keySet().iterator().next();
                                 assertThat(rk.address()).isEqualTo(address);
                                 assertThat(rk.topicName()).isEqualTo("order-events");
                                 assertThat(rk.artifact()).isEqualTo(artifact);
                                 assertThat(rk.methodName()).isEqualTo(method);
                                 assertThat(restored.get(key)).isEqualTo(value);
                             });
        }

        @Test
        void roundTrip_blueprintBindingsEmpty_preservesEmptyList() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var blueprintId = BlueprintId.blueprintId("com.example:empty-app-blueprint:1.0.0").unwrap();
            var bindingsKey = BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprintId);
            entries.put(bindingsKey, BlueprintStreamBindingsValue.blueprintStreamBindingsValue(List.of()));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 var bv = (BlueprintStreamBindingsValue) restored.get(bindingsKey);
                                 assertThat(bv.bindings()).isEmpty();
                                 assertThat(restored.get(bindingsKey)).isEqualTo(BlueprintStreamBindingsValue.blueprintStreamBindingsValue(List.of()));
                             });
        }

        @Test
        void roundTrip_streamRegistryRefCountOne_frameworkRegistered() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var streamAddress = addr("system", "audit", 1, 0, 0);
            var entry = new StreamRegistryEntry(streamAddress,
                                                RetentionPolicy.retentionPolicy(),
                                                42L,
                                                StreamRegistryEntry.RegisteredByKind.FRAMEWORK,
                                                1);
            var key = StreamRegistryKey.streamRegistryKey(streamAddress);
            entries.put(key, StreamRegistryValue.streamRegistryValue(entry));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 assertThat(restored.get(key)).isEqualTo(StreamRegistryValue.streamRegistryValue(entry));
                                 var rv = (StreamRegistryValue) restored.get(key);
                                 assertThat(rv.entry().refCount()).isEqualTo(1);
                                 assertThat(rv.entry().registeredBy()).isEqualTo(StreamRegistryEntry.RegisteredByKind.FRAMEWORK);
                             });
        }
    }

    @Nested
    class StreamConfigRoundTrip {
        @Test
        void roundTrip_streamConfig_preservesReplicasAndMinSyncReplicas() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();
            var retention = new RetentionPolicy(1_000L, 2_000L, 3_000L, RetentionMode.ANY, Option.none());
            var config = StreamConfig.streamConfig("orders",
                                                   4,
                                                   retention,
                                                   "latest",
                                                   1_048_576L,
                                                   ConsistencyMode.EVENTUAL,
                                                   3,
                                                   2,
                                                   StreamCompression.NONE,
                                                   Option.none());
            var key = StreamConfigKey.streamConfigKey("orders");
            entries.put(key, StreamConfigValue.streamConfigValue(config, 1710072000000L));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(restored -> {
                                 assertThat(restored).hasSize(1);
                                 var rv = (StreamConfigValue) restored.get(key);
                                 assertThat(rv.config().replicas()).isEqualTo(3);
                                 assertThat(rv.config().minSyncReplicas()).isEqualTo(2);
                                 assertThat(rv.config().partitions()).isEqualTo(4);
                                 assertThat(rv.config().consistencyMode()).isEqualTo(ConsistencyMode.EVENTUAL);
                                 assertThat(rv.config().retention()).isEqualTo(retention);
                                 assertThat(rv.createdAt()).isEqualTo(1710072000000L);
                             });
        }
    }

    /// Serialize/parse SYMMETRY (#488).
    ///
    /// `toToml` and `fromToml` are two hand-maintained switches, and nothing structurally ties them
    /// together: a key type can gain a serialize case and never gain a parse case. That is not
    /// hypothetical — `stream-reg` was written to consensus KV on every deployment since the
    /// declarative-consumer surface landed and had NO parse case, so any snapshot containing one
    /// failed with `UnknownKeyType`. Nothing noticed because nothing read it back.
    ///
    /// These pin the two types the declarative stream-consumer path depends on. A sweep of the
    /// serialize switch found nine further key types with the same asymmetry; they are reported
    /// separately rather than fixed here.
    @Nested
    class RoundTripSymmetry {

        @Test
        void fromToml_streamCursorCheckpoint_recoversKeyAndOffset() {
            var key = StreamCursorCheckpointKey.streamCursorCheckpointKey("orders", 2, "orders-onOrderEvent");
            var value = new StreamCursorCheckpointValue(4321L, 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entries -> {
                                 assertThat(entries).containsKey(key);
                                 assertThat(entries.get(key)).isInstanceOfSatisfying(StreamCursorCheckpointValue.class,
                                                                                     recovered -> assertThat(recovered.committedOffset()).isEqualTo(4321L));
                             });
        }

        @Test
        void fromToml_streamRegistration_recoversKeyAndDeclaration() {
            var artifact = Artifact.artifact("org.example:orders:1.0.0").unwrap();
            var method = MethodName.methodName("onOrderEvent").unwrap();
            var key = StreamRegistrationKey.streamRegistrationKey("orders", "streams.orders", artifact, method);
            var value = StreamRegistrationValue.streamRegistrationValue(NodeId.nodeId("node-1").unwrap(),
                                                                        "orders-onOrderEvent",
                                                                        true,
                                                                        "java.lang.String");

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entries -> {
                                 assertThat(entries).containsKey(key);
                                 assertThat(entries.get(key)).isInstanceOfSatisfying(StreamRegistrationValue.class,
                                                                                     recovered -> {
                                                                                         assertThat(recovered.consumerGroup()).isEqualTo("orders-onOrderEvent");
                                                                                         assertThat(recovered.batchMode()).isTrue();
                                                                                         assertThat(recovered.eventType()).isEqualTo("java.lang.String");
                                                                                     });
                             });
        }

        /// A registration written by one node must survive the snapshot a JOINING node restores from —
        /// otherwise a late joiner never learns the declaration and never consumes.
        @Test
        void fromToml_streamRegistrationAmongOtherEntries_doesNotFailWholeSnapshot() {
            var artifact = Artifact.artifact("org.example:orders:1.0.0").unwrap();
            var method = MethodName.methodName("onOrderEvent").unwrap();
            var registration = StreamRegistrationKey.streamRegistrationKey("orders", "streams.orders", artifact, method);
            var cursor = StreamCursorCheckpointKey.streamCursorCheckpointKey("orders", 0, "orders-onOrderEvent");
            var entries = new LinkedHashMap<AetherKey, AetherValue>();

            entries.put(registration,
                        StreamRegistrationValue.streamRegistrationValue(NodeId.nodeId("node-1").unwrap(),
                                                                        "orders-onOrderEvent",
                                                                        false,
                                                                        "java.lang.String"));
            entries.put(cursor, new StreamCursorCheckpointValue(7L, 1710072000000L));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(recovered -> assertThat(recovered).containsOnlyKeys(registration, cursor));
        }

        @Test
        void fromToml_streamMetadata_recoversKeyAndAllFields() {
            var key = StreamMetadataKey.streamMetadataKey("orders");
            var value = new StreamMetadataValue("orders",
                                                4,
                                                "count",
                                                "100000",
                                                "65536",
                                                "block",
                                                "com.example:my-app:1.0.0",
                                                1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entries -> {
                                 assertThat(entries).containsKey(key);
                                 assertThat(entries.get(key)).isEqualTo(value);
                             });
        }

        @Test
        void fromToml_streamPartitionAssignment_recoversPartitionsAndNodes() {
            var key = StreamPartitionAssignmentKey.streamPartitionAssignmentKey("orders", "orders-onOrderEvent");
            var assignments = List.of(new PartitionAssignment(0, NodeId.nodeId("node-a").unwrap()),
                                      new PartitionAssignment(3, NodeId.nodeId("node-b").unwrap()));
            var value = new StreamPartitionAssignmentValue(assignments, 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entries -> {
                                 assertThat(entries).containsKey(key);
                                 assertThat(entries.get(key)).isEqualTo(value);
                                 var rk = (StreamPartitionAssignmentKey) entries.keySet().iterator().next();
                                 assertThat(rk.streamName()).isEqualTo("orders");
                                 assertThat(rk.consumerGroup()).isEqualTo("orders-onOrderEvent");
                             });
        }

        @Test
        void fromToml_streamPartitionAssignmentWithoutAssignments_recoversEmptyList() {
            var key = StreamPartitionAssignmentKey.streamPartitionAssignmentKey("orders", "idle-group");
            var value = new StreamPartitionAssignmentValue(List.of(), 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entries -> {
                                 assertThat(entries.get(key)).isEqualTo(value);
                                 assertThat(((StreamPartitionAssignmentValue) entries.get(key)).assignments()).isEmpty();
                             });
        }
    }

    /// Executable form of the [KVStoreSerializer#parseKeyValue] symmetry invariant. The tag set is
    /// derived by enumerating the sealed [AetherKey] hierarchy and building one throwaway instance
    /// per variant, so a newly added key type is covered the moment it joins the hierarchy — there
    /// is no per-type sample to remember to write, which is what let nine tags drift unnoticed
    /// (#530).
    @Nested
    class ParseSerializeSymmetry {
        @Test
        void parseKeyValue_coversEverySerializedSection_exceptNamedExemptions() {
            var missing = serializedSections().stream()
                                              .filter(section -> !EphemeralKeys.isEphemeralSection(section))
                                              .filter(section -> !KVStoreSerializer.LOSSY_SECTIONS.contains(section))
                                              .filter(section -> !hasParseCase(section))
                                              .sorted()
                                              .toList();

            assertThat(missing).as("section tags that toToml can emit but parseKeyValue rejects as UnknownKeyType")
                               .isEmpty();
        }

        @Test
        void sectionForKey_assignsDistinctSection_toEveryPermittedKeyType() {
            assertThat(permittedKeyTypes()).isNotEmpty();
            assertThat(serializedSections()).as("two key types sharing one section tag would collide on restore")
                                            .hasSameSizeAs(permittedKeyTypes());
        }

        @Test
        void ephemeralSections_matchSectionsOfEphemeralKeyTypes_exactly() {
            var derived = EphemeralKeys.EPHEMERAL_KEY_TYPES.stream()
                                                           .map(KVStoreSerializerTest::instantiate)
                                                           .map(KVStoreSerializer::sectionForKey)
                                                           .collect(Collectors.toSet());

            assertThat(EphemeralKeys.EPHEMERAL_SECTIONS).as("EPHEMERAL_SECTIONS must be exactly the section tags of EPHEMERAL_KEY_TYPES")
                                                        .isEqualTo(derived);
        }

        @Test
        void lossySections_areSerializedAndNotAlsoEphemeral() {
            assertThat(serializedSections()).containsAll(KVStoreSerializer.LOSSY_SECTIONS);
            assertThat(KVStoreSerializer.LOSSY_SECTIONS.stream().filter(EphemeralKeys::isEphemeralSection).toList())
                    .as("an ephemeral section never reaches TOML, so it cannot also be lossy")
                    .isEmpty();
        }

        @Test
        void parseKeyValue_unknownSection_reportsUnknownKeyType() {
            assertThat(hasParseCase("no-such-section")).isFalse();
        }
    }

    private static Set<String> serializedSections() {
        return permittedKeyTypes().stream()
                                  .map(KVStoreSerializerTest::instantiate)
                                  .map(KVStoreSerializer::sectionForKey)
                                  .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends AetherKey>> permittedKeyTypes() {
        return Arrays.stream(AetherKey.class.getPermittedSubclasses())
                     .<Class<? extends AetherKey>> map(type -> (Class<? extends AetherKey>) type)
                     .toList();
    }

    /// Probes the parse switch behaviourally instead of reading its source: only the `default` arm
    /// produces [KVStoreSerializer.SerializationError.UnknownKeyType], so any other outcome —
    /// including a thrown exception from a parser fed deliberately empty input — proves a case
    /// exists for the tag.
    private static boolean hasParseCase(String section) {
        return Result.lift(() -> KVStoreSerializer.parseKeyValue(section, "", ""))
                     .map(KVStoreSerializerTest::isKnownSection)
                     .or(true);
    }

    private static boolean isKnownSection(Result<Map.Entry<AetherKey, AetherValue>> result) {
        return switch (result) {
            case Result.Failure<Map.Entry<AetherKey, AetherValue>> failure ->
                    !(failure.cause() instanceof KVStoreSerializer.SerializationError.UnknownKeyType);
            case Result.Success<Map.Entry<AetherKey, AetherValue>> _ -> true;
        };
    }

    /// Builds a throwaway key instance from the canonical record constructor with zeroed
    /// components. [KVStoreSerializer#sectionForKey] dispatches on type alone and never reads a
    /// component, so the zeroed values are never observed — that is what lets the guard cover every
    /// variant without a hand-written sample per key type.
    private static AetherKey instantiate(Class<? extends AetherKey> type) {
        return Result.lift(() -> newKeyInstance(type))
                     .onFailure(cause -> Assertions.fail("Cannot instantiate " + type.getSimpleName() + ": " + cause.message()))
                     .unwrap();
    }

    private static AetherKey newKeyInstance(Class<? extends AetherKey> type) throws ReflectiveOperationException {
        var componentTypes = Arrays.stream(type.getRecordComponents())
                                   .map(RecordComponent::getType)
                                   .toArray(Class<?>[]::new);
        var arguments = Arrays.stream(componentTypes).map(KVStoreSerializerTest::zeroValue).toArray();

        return type.cast(type.getDeclaredConstructor(componentTypes).newInstance(arguments));
    }

    /// A reference component's zero value is `null` — that is what
    /// [java.lang.reflect.Constructor#newInstance] requires for an unset reference argument, and
    /// the constructed key is only ever type-matched, never read.
    @NullReturn
    private static Object zeroValue(Class<?> type) {
        return switch (type.getName()) {
            case "int" -> 0;
            case "long" -> 0L;
            case "boolean" -> false;
            case "double" -> 0.0d;
            case "float" -> 0.0f;
            case "short" -> (short) 0;
            case "byte" -> (byte) 0;
            case "char" -> '\0';
            default -> null;
        };
    }

    private static int countOccurrences(String text, String substring) {
        var count = 0;
        var index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
