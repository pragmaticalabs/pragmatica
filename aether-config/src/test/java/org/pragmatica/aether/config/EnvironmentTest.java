package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentTest {

    @Test
    void local_hasCorrectDefaults() {
        var env = Environment.LOCAL;

        assertThat(env.defaultNodes()).isEqualTo(3);
        assertThat(env.defaultHeap()).isEqualTo("256m");
        assertThat(env.defaultTls()).isFalse();
    }

    @Test
    void docker_hasCorrectDefaults() {
        var env = Environment.DOCKER;

        assertThat(env.defaultNodes()).isEqualTo(5);
        assertThat(env.defaultHeap()).isEqualTo("512m");
        assertThat(env.defaultTls()).isFalse();
    }

    @Test
    void kubernetes_hasCorrectDefaults() {
        var env = Environment.KUBERNETES;

        assertThat(env.defaultNodes()).isEqualTo(5);
        assertThat(env.defaultHeap()).isEqualTo("1g");
        assertThat(env.defaultTls()).isTrue();
    }

    @Test
    void fromString_parsesLocalVariants() {
        assertThat(Environment.fromString("local")).isEqualTo(Environment.LOCAL);
        assertThat(Environment.fromString("LOCAL")).isEqualTo(Environment.LOCAL);
        assertThat(Environment.fromString("Local")).isEqualTo(Environment.LOCAL);
    }

    @Test
    void fromString_parsesDockerVariants() {
        assertThat(Environment.fromString("docker")).isEqualTo(Environment.DOCKER);
        assertThat(Environment.fromString("DOCKER")).isEqualTo(Environment.DOCKER);
        assertThat(Environment.fromString("Docker")).isEqualTo(Environment.DOCKER);
    }

    @Test
    void fromString_parsesKubernetesVariants() {
        assertThat(Environment.fromString("kubernetes")).isEqualTo(Environment.KUBERNETES);
        assertThat(Environment.fromString("KUBERNETES")).isEqualTo(Environment.KUBERNETES);
        assertThat(Environment.fromString("k8s")).isEqualTo(Environment.KUBERNETES);
        assertThat(Environment.fromString("K8S")).isEqualTo(Environment.KUBERNETES);
    }

    @Test
    void fromString_defaultsToDockerForBlank() {
        assertThat(Environment.fromString("")).isEqualTo(Environment.DOCKER);
        assertThat(Environment.fromString(null)).isEqualTo(Environment.DOCKER);
        assertThat(Environment.fromString("  ")).isEqualTo(Environment.DOCKER);
    }

    @Test
    void fromString_throwsForUnknown() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> Environment.fromString("unknown")
        );
    }

    @Test
    void displayName_returnsLowercaseName() {
        assertThat(Environment.LOCAL.displayName()).isEqualTo("local");
        assertThat(Environment.DOCKER.displayName()).isEqualTo("docker");
        assertThat(Environment.KUBERNETES.displayName()).isEqualTo("kubernetes");
    }
}
