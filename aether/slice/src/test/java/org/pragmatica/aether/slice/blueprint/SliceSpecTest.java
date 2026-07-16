// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Option;

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
    void sliceSpec_defaultsOverridesToNone_whenNotProvided() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 3))
                .onFailureRun(Assertions::fail)
                .onSuccess(spec -> {
                    assertThat(spec.maxInstances()).isEqualTo(Option.none());
                    assertThat(spec.scaleUpThreshold()).isEqualTo(Option.none());
                    assertThat(spec.scaleDownThreshold()).isEqualTo(Option.none());
                });
    }

    @Test
    void sliceSpec_succeeds_withOverrides() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> SliceSpec.sliceSpec(artifact,
                                                         3,
                                                         1,
                                                         Option.some(5),
                                                         Option.some(1.8),
                                                         Option.some(0.3)))
                .onFailureRun(Assertions::fail)
                .onSuccess(spec -> {
                    assertThat(spec.maxInstances()).isEqualTo(Option.some(5));
                    assertThat(spec.scaleUpThreshold()).isEqualTo(Option.some(1.8));
                    assertThat(spec.scaleDownThreshold()).isEqualTo(Option.some(0.3));
                });
    }

    @Test
    void sliceSpec_succeeds_whenMaxInstancesEqualsInstances() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 3, 1, Option.some(3), Option.none(), Option.none()))
                .onFailureRun(Assertions::fail)
                .onSuccess(spec -> assertThat(spec.maxInstances()).isEqualTo(Option.some(3)));
    }

    @Test
    void sliceSpec_fails_whenMaxInstancesBelowInstances() {
        Artifact.artifact("org.example:slice:1.0.0")
                .flatMap(artifact -> SliceSpec.sliceSpec(artifact, 3, 1, Option.some(2), Option.none(), Option.none()))
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("maxInstances"));
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
