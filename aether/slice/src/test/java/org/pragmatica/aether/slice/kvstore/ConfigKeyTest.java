package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigKeyTest {

    @Nested
    class ClusterWideFactory {
        @Test
        void forKey_creates_cluster_wide_key() {
            var key = ConfigKey.forKey("max-retries");
            assertThat(key.key()).isEqualTo("max-retries");
            assertThat(key.isClusterWide()).isTrue();
        }

        @Test
        void forKey_asString_produces_correct_format() {
            var key = ConfigKey.forKey("max-retries");
            assertThat(key.asString()).isEqualTo("config/max-retries");
        }

        @Test
        void forKey_toString_returns_asString() {
            var key = ConfigKey.forKey("max-retries");
            assertThat(key.toString()).isEqualTo(key.asString());
        }
    }

    @Nested
    class NodeScopedFactory {
        @Test
        void forKey_with_nodeId_creates_node_scoped_key() {
            var nodeId = NodeId.randomNodeId();
            var key = ConfigKey.forKey("max-retries", nodeId);
            assertThat(key.key()).isEqualTo("max-retries");
            assertThat(key.isClusterWide()).isFalse();
            assertThat(key.nodeScope().isPresent()).isTrue();
        }

        @Test
        void forKey_with_nodeId_asString_produces_correct_format() {
            var nodeId = NodeId.nodeId("node-42").unwrap();
            var key = ConfigKey.forKey("max-retries", nodeId);
            assertThat(key.asString()).isEqualTo("config/node/node-42/max-retries");
        }
    }

    @Nested
    class ConfigKeyParsing {
        @Test
        void configKey_parses_cluster_wide_key() {
            ConfigKey.configKey("config/max-retries")
                     .onSuccess(this::assertClusterWideMaxRetries)
                     .onFailureRun(Assertions::fail);
        }

        @Test
        void configKey_parses_node_scoped_key() {
            ConfigKey.configKey("config/node/node-42/max-retries")
                     .onSuccess(this::assertNodeScopedMaxRetries)
                     .onFailureRun(Assertions::fail);
        }

        private void assertClusterWideMaxRetries(ConfigKey key) {
            assertThat(key.key()).isEqualTo("max-retries");
            assertThat(key.isClusterWide()).isTrue();
        }

        private void assertNodeScopedMaxRetries(ConfigKey key) {
            assertThat(key.key()).isEqualTo("max-retries");
            assertThat(key.isClusterWide()).isFalse();
        }

        @Test
        void configKey_from_string_with_invalid_prefix_fails() {
            ConfigKey.configKey("invalid/max-retries")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause.message()).contains("Invalid config key format"));
        }

        @Test
        void configKey_from_string_with_empty_key_fails() {
            ConfigKey.configKey("config/")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause.message()).contains("Invalid config key format"));
        }

        @Test
        void configKey_from_string_with_empty_node_scoped_key_fails() {
            ConfigKey.configKey("config/node/node-42/")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause.message()).contains("Invalid config key format"));
        }

        @Test
        void configKey_from_string_with_empty_nodeId_fails() {
            ConfigKey.configKey("config/node//max-retries")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void configKey_cluster_wide_roundtrip_consistency() {
            var originalKey = ConfigKey.forKey("max-retries");
            var keyString = originalKey.asString();
            ConfigKey.configKey(keyString)
                     .onSuccess(parsedKey -> assertThat(parsedKey).isEqualTo(originalKey))
                     .onFailureRun(Assertions::fail);
        }

        @Test
        void configKey_node_scoped_roundtrip_consistency() {
            var nodeId = NodeId.nodeId("node-42").unwrap();
            var originalKey = ConfigKey.forKey("max-retries", nodeId);
            var keyString = originalKey.asString();
            ConfigKey.configKey(keyString)
                     .onSuccess(parsedKey -> assertThat(parsedKey).isEqualTo(originalKey))
                     .onFailureRun(Assertions::fail);
        }
    }

    @Nested
    class Equality {
        @Test
        void configKey_equality_works_for_cluster_wide() {
            var key1 = ConfigKey.forKey("max-retries");
            var key2 = ConfigKey.forKey("max-retries");
            assertThat(key1).isEqualTo(key2);
            assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        }

        @Test
        void configKey_equality_works_for_node_scoped() {
            var nodeId = NodeId.nodeId("node-42").unwrap();
            var key1 = ConfigKey.forKey("max-retries", nodeId);
            var key2 = ConfigKey.forKey("max-retries", nodeId);
            assertThat(key1).isEqualTo(key2);
            assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        }

        @Test
        void configKey_inequality_cluster_vs_node_scoped() {
            var nodeId = NodeId.nodeId("node-42").unwrap();
            var clusterKey = ConfigKey.forKey("max-retries");
            var nodeKey = ConfigKey.forKey("max-retries", nodeId);
            assertThat(clusterKey).isNotEqualTo(nodeKey);
        }

        @Test
        void configKey_inequality_different_keys() {
            var key1 = ConfigKey.forKey("max-retries");
            var key2 = ConfigKey.forKey("timeout");
            assertThat(key1).isNotEqualTo(key2);
        }
    }
}
