// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import static org.assertj.core.api.Assertions.assertThat;

class TerraGraphTest {
    record Factory(String artifact, Class<Object> sliceType, List<Class<?>> dependencies) implements TerraFactory<Object> {
        public Promise<Object> create(TerraContext context) { return Promise.success(new Object()); }
    }
    @Test void ordered_selfCycle_refuses() {
        var factory = new Factory("g:a:1", Object.class, List.of(Object.class));
        assertThat(TerraApplication.ordered(new TerraBlueprint(List.of(factory.artifact())), List.of(factory)).isFailure()).isTrue();
    }
    @Test void ordered_duplicateArtifact_refuses() {
        var factory = new Factory("g:a:1", Object.class, List.of());
        assertThat(TerraApplication.ordered(new TerraBlueprint(List.of(factory.artifact())), List.of(factory, factory)).isFailure()).isTrue();
    }
    @Test void ordered_unknownArtifact_refuses() {
        assertThat(TerraApplication.ordered(new TerraBlueprint(List.of("g:missing:1")), List.of()).isFailure()).isTrue();
    }
}
