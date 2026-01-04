package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AetherConfigTest {

    @Test
    void forEnvironment_createsValidLocalConfig() {
        var config = AetherConfig.forEnvironment(Environment.LOCAL);

        assertThat(config.environment()).isEqualTo(Environment.LOCAL);
        assertThat(config.cluster().nodes()).isEqualTo(3);
        assertThat(config.node().heap()).isEqualTo("256m");
        assertThat(config.tlsEnabled()).isFalse();
        assertThat(config.docker()).isNull();
        assertThat(config.kubernetes()).isNull();
    }

    @Test
    void forEnvironment_createsValidDockerConfig() {
        var config = AetherConfig.forEnvironment(Environment.DOCKER);

        assertThat(config.environment()).isEqualTo(Environment.DOCKER);
        assertThat(config.cluster().nodes()).isEqualTo(5);
        assertThat(config.node().heap()).isEqualTo("512m");
        assertThat(config.tlsEnabled()).isFalse();
        assertThat(config.docker()).isNotNull();
        assertThat(config.docker().network()).isEqualTo(DockerConfig.DEFAULT_NETWORK);
        assertThat(config.kubernetes()).isNull();
    }

    @Test
    void forEnvironment_createsValidKubernetesConfig() {
        var config = AetherConfig.forEnvironment(Environment.KUBERNETES);

        assertThat(config.environment()).isEqualTo(Environment.KUBERNETES);
        assertThat(config.cluster().nodes()).isEqualTo(5);
        assertThat(config.node().heap()).isEqualTo("1g");
        assertThat(config.tlsEnabled()).isTrue();
        assertThat(config.tls()).isNotNull();
        assertThat(config.tls().autoGenerate()).isTrue();
        assertThat(config.docker()).isNull();
        assertThat(config.kubernetes()).isNotNull();
        assertThat(config.kubernetes().namespace()).isEqualTo(KubernetesConfig.DEFAULT_NAMESPACE);
    }

    @Test
    void defaults_createsDockerConfig() {
        var config = AetherConfig.defaults();

        assertThat(config.environment()).isEqualTo(Environment.DOCKER);
    }

    @Test
    void builder_overridesNodes() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .nodes(7)
            .build();

        assertThat(config.cluster().nodes()).isEqualTo(7);
        // Other defaults remain
        assertThat(config.node().heap()).isEqualTo("512m");
    }

    @Test
    void builder_overridesHeap() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .heap("2g")
            .build();

        assertThat(config.node().heap()).isEqualTo("2g");
        // Other defaults remain
        assertThat(config.cluster().nodes()).isEqualTo(5);
    }

    @Test
    void builder_overridesTls() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .tls(true)
            .build();

        assertThat(config.tlsEnabled()).isTrue();
        assertThat(config.tls()).isNotNull();
        assertThat(config.tls().autoGenerate()).isTrue();
    }

    @Test
    void builder_overridesPorts() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .ports(new PortsConfig(9000, 9100))
            .build();

        assertThat(config.cluster().ports().management()).isEqualTo(9000);
        assertThat(config.cluster().ports().cluster()).isEqualTo(9100);
    }

    @Test
    void builder_overridesGc() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .gc("g1")
            .build();

        assertThat(config.node().gc()).isEqualTo("g1");
    }

    @Test
    void builder_overridesDockerConfig() {
        var customDocker = new DockerConfig("my-network", "my-image:v1");
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .dockerConfig(customDocker)
            .build();

        assertThat(config.docker().network()).isEqualTo("my-network");
        assertThat(config.docker().image()).isEqualTo("my-image:v1");
    }

    @Test
    void builder_overridesKubernetesConfig() {
        var customK8s = new KubernetesConfig("prod", "LoadBalancer", "fast-ssd");
        var config = AetherConfig.builder()
            .environment(Environment.KUBERNETES)
            .kubernetesConfig(customK8s)
            .build();

        assertThat(config.kubernetes().namespace()).isEqualTo("prod");
        assertThat(config.kubernetes().serviceType()).isEqualTo("LoadBalancer");
        assertThat(config.kubernetes().storageClass()).isEqualTo("fast-ssd");
    }

    @Test
    void builder_combinesMultipleOverrides() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .nodes(7)
            .heap("2g")
            .gc("g1")
            .tls(true)
            .ports(new PortsConfig(9000, 9100))
            .build();

        assertThat(config.cluster().nodes()).isEqualTo(7);
        assertThat(config.node().heap()).isEqualTo("2g");
        assertThat(config.node().gc()).isEqualTo("g1");
        assertThat(config.tlsEnabled()).isTrue();
        assertThat(config.cluster().ports().management()).isEqualTo(9000);
    }
}
