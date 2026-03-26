package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.consensus.rabia.Phase;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterConfigKVTest {
    private static final Phase TEST_PHASE = Phase.phase(42L);
    private static final Instant TEST_TIMESTAMP = Instant.parse("2026-03-26T12:00:00Z");

    @Nested
    class KeyTests {
        @Test
        void asString_currentKey_formatsCorrectly() {
            assertThat(ClusterConfigKey.CURRENT.asString()).isEqualTo("cluster-config/0");
        }

        @Test
        void asString_versionedKey_formatsCorrectly() {
            var key = ClusterConfigKey.clusterConfigKey(7);
            assertThat(key.asString()).isEqualTo("cluster-config/7");
        }

        @Test
        void clusterConfigKey_validString_parsesCorrectly() {
            ClusterConfigKey.clusterConfigKey("cluster-config/5")
                            .onFailureRun(Assertions::fail)
                            .onSuccess(key -> assertThat(key.configVersion()).isEqualTo(5));
        }

        @Test
        void clusterConfigKey_invalidPrefix_failure() {
            ClusterConfigKey.clusterConfigKey("wrong-prefix/5")
                            .onSuccess(_ -> Assertions.fail("Expected failure"));
        }

        @Test
        void clusterConfigKey_emptyVersion_failure() {
            ClusterConfigKey.clusterConfigKey("cluster-config/")
                            .onSuccess(_ -> Assertions.fail("Expected failure"));
        }
    }

    @Nested
    class ValueTests {
        @Test
        void clusterConfigValue_factoryWithTimestamp_allFieldsSet() {
            var value = ClusterConfigValue.clusterConfigValue(
                "[cluster]\nname = \"test\"",
                "test",
                "0.21.1",
                5,
                3,
                9,
                "hetzner",
                1,
                1711461000000L
            );
            assertThat(value.tomlContent()).isEqualTo("[cluster]\nname = \"test\"");
            assertThat(value.clusterName()).isEqualTo("test");
            assertThat(value.version()).isEqualTo("0.21.1");
            assertThat(value.coreCount()).isEqualTo(5);
            assertThat(value.coreMin()).isEqualTo(3);
            assertThat(value.coreMax()).isEqualTo(9);
            assertThat(value.deploymentType()).isEqualTo("hetzner");
            assertThat(value.configVersion()).isEqualTo(1);
            assertThat(value.updatedAt()).isEqualTo(1711461000000L);
        }

        @Test
        void withIncrementedVersion_incrementsConfigVersion() {
            var value = ClusterConfigValue.clusterConfigValue(
                "content", "test", "0.21.1", 5, 3, 9, "hetzner", 3, 1711461000000L
            );
            var incremented = value.withIncrementedVersion();
            assertThat(incremented.configVersion()).isEqualTo(4);
            assertThat(incremented.clusterName()).isEqualTo("test");
        }
    }

    @Nested
    class SerializationRoundTrip {
        @Test
        void toToml_fromToml_roundTrips() {
            var key = ClusterConfigKey.clusterConfigKey(3);
            var value = ClusterConfigValue.clusterConfigValue(
                "name = \"test\"",
                "prod",
                "0.21.1",
                5,
                3,
                9,
                "hetzner",
                3,
                1711461000000L
            );

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entries -> {
                                 assertThat(entries).hasSize(1);
                                 var entry = entries.entrySet().iterator().next();
                                 assertThat(entry.getKey()).isInstanceOf(ClusterConfigKey.class);
                                 var parsedKey = (ClusterConfigKey) entry.getKey();
                                 assertThat(parsedKey.configVersion()).isEqualTo(3);
                                 var parsedValue = (ClusterConfigValue) entry.getValue();
                                 assertThat(parsedValue.clusterName()).isEqualTo("prod");
                                 assertThat(parsedValue.version()).isEqualTo("0.21.1");
                                 assertThat(parsedValue.coreCount()).isEqualTo(5);
                                 assertThat(parsedValue.coreMin()).isEqualTo(3);
                                 assertThat(parsedValue.coreMax()).isEqualTo(9);
                                 assertThat(parsedValue.deploymentType()).isEqualTo("hetzner");
                                 assertThat(parsedValue.configVersion()).isEqualTo(3);
                                 assertThat(parsedValue.updatedAt()).isEqualTo(1711461000000L);
                                 assertThat(parsedValue.tomlContent()).isEqualTo("name = \"test\"");
                             });
        }

        @Test
        void toToml_fromToml_preservesTomlContentWithSpecialChars() {
            var key = ClusterConfigKey.CURRENT;
            var tomlContent = "[deployment]\ntype = \"hetzner\"\n\n[cluster]\nname = \"prod\"";
            var value = ClusterConfigValue.clusterConfigValue(
                tomlContent, "prod", "0.21.1", 5, 3, 9, "hetzner", 1, 1711461000000L
            );

            KVStoreSerializer.toToml(Map.of(key, value), TEST_PHASE, TEST_TIMESTAMP)
                             .flatMap(KVStoreSerializer::fromToml)
                             .onFailureRun(Assertions::fail)
                             .onSuccess(entries -> {
                                 var parsedValue = (ClusterConfigValue) entries.values().iterator().next();
                                 assertThat(parsedValue.tomlContent()).isEqualTo(tomlContent);
                             });
        }
    }
}
