package org.pragmatica.aether.slice.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;

import static org.assertj.core.api.Assertions.assertThat;

class SliceSpecTest {

    @Test
    void sliceSpec_succeeds_withValidInput() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 3))
                .onFailureRun(Assertions::fail)
                .onSuccess(spec -> {
                    assertThat(spec.instances()).isEqualTo(3);
                    assertThat(spec.artifact().asString()).isEqualTo("org.example:slice:1.0.0");
                });
    }

    @Test
    void sliceSpec_succeeds_withDefaultInstances() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(SliceSpec::sliceSpec)
                .onFailureRun(Assertions::fail)
                .onSuccess(spec -> assertThat(spec.instances()).isEqualTo(1));
    }

    @Test
    void sliceSpec_fails_withZeroInstances() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 0))
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("must be positive"));
    }

    @Test
    void sliceSpec_fails_withNegativeInstances() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> SliceSpec.sliceSpec(artifact, -1))
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("must be positive"));
    }
}
