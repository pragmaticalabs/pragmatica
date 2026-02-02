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
    void environment_parsesLocalVariants() {
        assertThat(Environment.environment("local").unwrap()).isEqualTo(Environment.LOCAL);
        assertThat(Environment.environment("LOCAL").unwrap()).isEqualTo(Environment.LOCAL);
        assertThat(Environment.environment("Local").unwrap()).isEqualTo(Environment.LOCAL);
    }

    @Test
    void environment_parsesDockerVariants() {
        assertThat(Environment.environment("docker").unwrap()).isEqualTo(Environment.DOCKER);
        assertThat(Environment.environment("DOCKER").unwrap()).isEqualTo(Environment.DOCKER);
        assertThat(Environment.environment("Docker").unwrap()).isEqualTo(Environment.DOCKER);
    }

    @Test
    void environment_parsesKubernetesVariants() {
        assertThat(Environment.environment("kubernetes").unwrap()).isEqualTo(Environment.KUBERNETES);
        assertThat(Environment.environment("KUBERNETES").unwrap()).isEqualTo(Environment.KUBERNETES);
        assertThat(Environment.environment("k8s").unwrap()).isEqualTo(Environment.KUBERNETES);
        assertThat(Environment.environment("K8S").unwrap()).isEqualTo(Environment.KUBERNETES);
    }

    @Test
    void environment_defaultsToDockerForBlank() {
        assertThat(Environment.environment("").unwrap()).isEqualTo(Environment.DOCKER);
        assertThat(Environment.environment(null).unwrap()).isEqualTo(Environment.DOCKER);
        assertThat(Environment.environment("  ").unwrap()).isEqualTo(Environment.DOCKER);
    }

    @Test
    void environment_failsForUnknown() {
        Environment.environment("unknown")
            .onSuccessRun(org.junit.jupiter.api.Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Unknown environment"));
    }

    @Test
    void displayName_returnsLowercaseName() {
        assertThat(Environment.LOCAL.displayName()).isEqualTo("local");
        assertThat(Environment.DOCKER.displayName()).isEqualTo("docker");
        assertThat(Environment.KUBERNETES.displayName()).isEqualTo("kubernetes");
    }
}
