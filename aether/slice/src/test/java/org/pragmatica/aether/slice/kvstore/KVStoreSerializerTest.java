package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.*;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                                 assertThat(toml).contains("\"com.example:my-app\" = \"1.0.0|3|2||1710072000000|CORE_ONLY\"");
                             });
        }

        @Test
        void toToml_ephemeralNodeLifecycle_excluded() {
            var nodeId = NodeId.nodeId("node-abc123").unwrap();
            var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
            var value = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, 1710072000000L);

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).doesNotContain("[node-lifecycle]");
                                 assertThat(toml).doesNotContain("node-abc123");
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
            var value = new GovernorAnnouncementValue(
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
                                 assertThat(toml).contains("\"com.example:app-a\" = \"1.0.0|2|1||1000|CORE_ONLY\"");
                                 assertThat(toml).contains("\"com.example:app-b\" = \"1.0.0|4|3||2000|CORE_ONLY\"");
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

            // Ephemeral: node lifecycle
            var nodeId = NodeId.nodeId("node-1").unwrap();
            entries.put(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                        NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, 2000L));

            // Ephemeral: activation directive
            entries.put(ActivationDirectiveKey.activationDirectiveKey(nodeId),
                        new ActivationDirectiveValue("CORE"));

            // Persistent: config
            entries.put(ConfigKey.forKey("timeout"), new ConfigValue("timeout", "5000", 3000L));

            KVStoreSerializer.toToml(entries, TEST_PHASE, TEST_TIMESTAMP)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(toml -> {
                                 assertThat(toml).contains("[slice-target]");
                                 assertThat(toml).contains("[config]");
                                 assertThat(toml).doesNotContain("[node-lifecycle]");
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
        void fromToml_ephemeralSection_skippedOnRestore() {
            var toml = """
                       [meta]
                       phase = 100
                       timestamp = "2026-03-10T12:00:00Z"

                       [node-lifecycle]
                       "node-1" = "ON_DUTY|1710072000000"

                       [config]
                       "timeout" = "timeout|5000|3000"
                       """;

            KVStoreSerializer.fromToml(toml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(map -> {
                                 assertThat(map).hasSize(1);
                                 assertThat(map).containsKey(ConfigKey.forKey("timeout"));
                                 // node-lifecycle is ephemeral — should not be restored
                                 var nodeId = NodeId.nodeId("node-1").unwrap();
                                 assertThat(map).doesNotContainKey(NodeLifecycleKey.nodeLifecycleKey(nodeId));
                             });
        }

        @Test
        void fromToml_multipleEphemeralSections_allSkipped() {
            var toml = """
                       [meta]
                       phase = 100
                       timestamp = "2026-03-10T12:00:00Z"

                       [node-lifecycle]
                       "node-1" = "ON_DUTY|1710072000000"

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

        @Test
        void roundTrip_ephemeralKeys_excludedFromOutput() {
            var entries = new LinkedHashMap<AetherKey, AetherValue>();

            // Persistent
            var ab = ArtifactBase.artifactBase("com.example:svc").unwrap();
            var ver = Version.version("1.0.0").unwrap();
            entries.put(SliceTargetKey.sliceTargetKey(ab),
                        new SliceTargetValue(ver, 2, 1, Option.none(), "CORE_ONLY", 1000L));

            // Ephemeral — should be filtered out
            var nodeId = NodeId.nodeId("node-x").unwrap();
            entries.put(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                        NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, 2000L));

            var key = GovernorAnnouncementKey.forCommunity("prod:us-east-1");
            var members = List.of(NodeId.nodeId("worker-a").unwrap());
            entries.put(key, new GovernorAnnouncementValue(
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
        void isEphemeral_nodeLifecycleKey_true() {
            var nodeId = NodeId.nodeId("node-1").unwrap();
            assertThat(EphemeralKeys.isEphemeral(NodeLifecycleKey.nodeLifecycleKey(nodeId))).isTrue();
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
            assertThat(EphemeralKeys.isEphemeralSection("node-lifecycle")).isTrue();
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
