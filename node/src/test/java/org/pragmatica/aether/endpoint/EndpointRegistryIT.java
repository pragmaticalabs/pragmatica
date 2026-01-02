package org.pragmatica.aether.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.endpoint.EndpointRegistry.Endpoint;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/**
 * Integration tests for EndpointRegistry weighted routing.
 * Tests the weighted endpoint selection logic used during rolling updates.
 */
class EndpointRegistryIT {
    private EndpointRegistry registry;

    @BeforeEach
    void setUp() {
        registry = EndpointRegistry.endpointRegistry();
    }

    @Test
    void selectEndpointWithRouting_routesAllToOld_whenRoutingIs100Old() {
        // Given: 2 old endpoints, 2 new endpoints
        var oldVersion = Version.version("1.0.0").unwrap();
        var newVersion = Version.version("2.0.0").unwrap();
        var artifactBase = ArtifactBase.artifactBase("org.test:service").unwrap();
        var method = MethodName.methodName("process").unwrap();

        registerEndpoint(artifactBase, oldVersion, method, 0, "node-1");
        registerEndpoint(artifactBase, oldVersion, method, 1, "node-2");
        registerEndpoint(artifactBase, newVersion, method, 0, "node-3");
        registerEndpoint(artifactBase, newVersion, method, 1, "node-4");

        // When: routing is 100% old
        var routing = VersionRouting.parse("0:1");
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 100; i++) {
            var endpoint = registry.selectEndpointWithRouting(artifactBase, method, routing, oldVersion, newVersion);
            endpoint.onPresent(e -> counts.merge(e.artifact().version().withQualifier(), 1, Integer::sum));
        }

        // Then: all requests go to old version
        assertThat(counts.get("1.0.0")).isEqualTo(100);
        assertThat(counts.get("2.0.0")).isNull();
    }

    @Test
    void selectEndpointWithRouting_routesAllToNew_whenRoutingIs100New() {
        // Given: 2 old endpoints, 2 new endpoints
        var oldVersion = Version.version("1.0.0").unwrap();
        var newVersion = Version.version("2.0.0").unwrap();
        var artifactBase = ArtifactBase.artifactBase("org.test:service").unwrap();
        var method = MethodName.methodName("process").unwrap();

        registerEndpoint(artifactBase, oldVersion, method, 0, "node-1");
        registerEndpoint(artifactBase, oldVersion, method, 1, "node-2");
        registerEndpoint(artifactBase, newVersion, method, 0, "node-3");
        registerEndpoint(artifactBase, newVersion, method, 1, "node-4");

        // When: routing is 100% new
        var routing = VersionRouting.parse("1:0");
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 100; i++) {
            var endpoint = registry.selectEndpointWithRouting(artifactBase, method, routing, oldVersion, newVersion);
            endpoint.onPresent(e -> counts.merge(e.artifact().version().withQualifier(), 1, Integer::sum));
        }

        // Then: all requests go to new version
        assertThat(counts.get("2.0.0")).isEqualTo(100);
        assertThat(counts.get("1.0.0")).isNull();
    }

    @Test
    void selectEndpointWithRouting_distributesEvenly_whenRoutingIs50_50() {
        // Given: 2 old endpoints, 2 new endpoints
        var oldVersion = Version.version("1.0.0").unwrap();
        var newVersion = Version.version("2.0.0").unwrap();
        var artifactBase = ArtifactBase.artifactBase("org.test:even-split").unwrap();
        var method = MethodName.methodName("process").unwrap();

        registerEndpoint(artifactBase, oldVersion, method, 0, "node-1");
        registerEndpoint(artifactBase, oldVersion, method, 1, "node-2");
        registerEndpoint(artifactBase, newVersion, method, 0, "node-3");
        registerEndpoint(artifactBase, newVersion, method, 1, "node-4");

        // When: routing is 50/50
        var routing = VersionRouting.parse("1:1");
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 100; i++) {
            var endpoint = registry.selectEndpointWithRouting(artifactBase, method, routing, oldVersion, newVersion);
            endpoint.onPresent(e -> counts.merge(e.artifact().version().withQualifier(), 1, Integer::sum));
        }

        // Then: roughly equal distribution (50 each)
        assertThat(counts.get("1.0.0")).isEqualTo(50);
        assertThat(counts.get("2.0.0")).isEqualTo(50);
    }

    @Test
    void selectEndpointWithRouting_scalesRoutingToInstanceCounts() {
        // Given: 1 old endpoint, 3 new endpoints
        var oldVersion = Version.version("1.0.0").unwrap();
        var newVersion = Version.version("2.0.0").unwrap();
        var artifactBase = ArtifactBase.artifactBase("org.test:scaled").unwrap();
        var method = MethodName.methodName("process").unwrap();

        registerEndpoint(artifactBase, oldVersion, method, 0, "node-1");
        registerEndpoint(artifactBase, newVersion, method, 0, "node-2");
        registerEndpoint(artifactBase, newVersion, method, 1, "node-3");
        registerEndpoint(artifactBase, newVersion, method, 2, "node-4");

        // When: routing is 75/25 (3:1 ratio)
        var routing = VersionRouting.parse("3:1");
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 100; i++) {
            var endpoint = registry.selectEndpointWithRouting(artifactBase, method, routing, oldVersion, newVersion);
            endpoint.onPresent(e -> counts.merge(e.artifact().version().withQualifier(), 1, Integer::sum));
        }

        // Then: new gets ~75, old gets ~25
        assertThat(counts.get("2.0.0")).isEqualTo(75);
        assertThat(counts.get("1.0.0")).isEqualTo(25);
    }

    @Test
    void selectEndpointWithRouting_fallsBackToOld_whenNoNewEndpoints() {
        // Given: only old endpoints
        var oldVersion = Version.version("1.0.0").unwrap();
        var newVersion = Version.version("2.0.0").unwrap();
        var artifactBase = ArtifactBase.artifactBase("org.test:fallback").unwrap();
        var method = MethodName.methodName("process").unwrap();

        registerEndpoint(artifactBase, oldVersion, method, 0, "node-1");
        registerEndpoint(artifactBase, oldVersion, method, 1, "node-2");
        // No new version endpoints

        // When: routing is 50/50 but no new endpoints exist
        var routing = VersionRouting.parse("1:1");
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 100; i++) {
            var endpoint = registry.selectEndpointWithRouting(artifactBase, method, routing, oldVersion, newVersion);
            endpoint.onPresent(e -> counts.merge(e.artifact().version().withQualifier(), 1, Integer::sum));
        }

        // Then: all requests go to old version
        assertThat(counts.get("1.0.0")).isEqualTo(100);
        assertThat(counts.get("2.0.0")).isNull();
    }

    @Test
    void selectEndpointWithRouting_distributesAcrossInstances() {
        // Given: 2 old endpoints
        var oldVersion = Version.version("1.0.0").unwrap();
        var newVersion = Version.version("2.0.0").unwrap();
        var artifactBase = ArtifactBase.artifactBase("org.test:distribute").unwrap();
        var method = MethodName.methodName("process").unwrap();

        registerEndpoint(artifactBase, oldVersion, method, 0, "node-1");
        registerEndpoint(artifactBase, oldVersion, method, 1, "node-2");

        // When: routing is 100% old
        var routing = VersionRouting.parse("0:1");
        Map<String, Integer> nodeCounts = new HashMap<>();

        for (int i = 0; i < 100; i++) {
            var endpoint = registry.selectEndpointWithRouting(artifactBase, method, routing, oldVersion, newVersion);
            endpoint.onPresent(e -> nodeCounts.merge(e.nodeId().id(), 1, Integer::sum));
        }

        // Then: distributed across nodes (round-robin)
        assertThat(nodeCounts.get("node-1")).isEqualTo(50);
        assertThat(nodeCounts.get("node-2")).isEqualTo(50);
    }

    @Test
    void selectEndpoint_usesRoundRobin() {
        // Given: 3 endpoints for same artifact
        var artifact = Artifact.artifact("org.test:slice:1.0.0").unwrap();
        var method = MethodName.methodName("process").unwrap();

        registerDirectEndpoint(artifact, method, 0, "node-1");
        registerDirectEndpoint(artifact, method, 1, "node-2");
        registerDirectEndpoint(artifact, method, 2, "node-3");

        // When: selecting multiple times
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 99; i++) {
            var endpoint = registry.selectEndpoint(artifact, method);
            endpoint.onPresent(e -> counts.merge(e.nodeId().id(), 1, Integer::sum));
        }

        // Then: evenly distributed
        assertThat(counts.get("node-1")).isEqualTo(33);
        assertThat(counts.get("node-2")).isEqualTo(33);
        assertThat(counts.get("node-3")).isEqualTo(33);
    }

    // Helper methods
    private void registerEndpoint(ArtifactBase base, Version version, MethodName method, int instance, String nodeId) {
        var artifact = Artifact.artifact(base.groupId(), base.artifactId(), version);
        registerDirectEndpoint(artifact, method, instance, nodeId);
    }

    @SuppressWarnings("unchecked")
    private void registerDirectEndpoint(Artifact artifact, MethodName method, int instance, String nodeId) {
        var key = new EndpointKey(artifact, method, instance);
        var value = new EndpointValue(nodeId(nodeId));
        var put = (KVCommand.Put<AetherKey, AetherValue>) (KVCommand.Put<?, ?>) new KVCommand.Put<>(key, value);
        registry.onValuePut(new ValuePut<>(put, Option.empty()));
    }
}
