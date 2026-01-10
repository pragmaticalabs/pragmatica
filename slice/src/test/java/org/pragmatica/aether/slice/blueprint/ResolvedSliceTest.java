package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvedSliceTest {

    @Test
    void resolvedSlice_succeeds_asDirectDependency() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> ResolvedSlice.resolvedSlice(artifact, 3, false))
                .onFailureRun(Assertions::fail)
                .onSuccess(resolved -> {
                    assertThat(resolved.artifact().asString()).isEqualTo("org.example:slice:1.0.0");
                    assertThat(resolved.instances()).isEqualTo(3);
                    assertThat(resolved.isDependency()).isFalse();
                });
    }

    @Test
    void resolvedSlice_succeeds_asTransitiveDependency() {
        Artifact.artifact("org.example:dependency:2.0.0")
                .flatMap(artifact -> ResolvedSlice.resolvedSlice(artifact, 1, true))
                .onFailureRun(Assertions::fail)
                .onSuccess(resolved -> {
                    assertThat(resolved.artifact().asString()).isEqualTo("org.example:dependency:2.0.0");
                    assertThat(resolved.instances()).isEqualTo(1);
                    assertThat(resolved.isDependency()).isTrue();
                });
    }

    @Test
    void resolvedSlice_fails_withZeroInstances() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> ResolvedSlice.resolvedSlice(artifact, 0, false))
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("positive"));
    }

    @Test
    void resolvedSlice_fails_withNegativeInstances() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> ResolvedSlice.resolvedSlice(artifact, -1, false))
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("positive"));
    }
}
