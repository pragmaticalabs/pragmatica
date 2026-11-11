// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #3021: `Staged` is built in code only; the one validation it carries is that a module coordinate must not also be `[shared]`.
class RepositoryTypeStagedTest {
    private static final Artifact MODULE = Artifact.artifact("org.example:echo:1.0.0").unwrap();
    private static final Artifact OTHER = Artifact.artifact("org.example:lib:1.0.0").unwrap();

    @Test
    void refuseShared_succeeds_whenNoModuleCoordinateIsShared() {
        var staged = RepositoryType.Staged.staged(Path.of("root"), List.of(MODULE));

        assertThat(staged.refuseShared(List.of(OTHER)).isSuccess()).isTrue();
    }

    @Test
    void refuseShared_failsNamingTheCoordinate_whenAModuleCoordinateIsShared() {
        var staged = RepositoryType.Staged.staged(Path.of("root"), List.of(MODULE));

        staged.refuseShared(List.of(OTHER, MODULE))
              .onSuccess(_ -> Assertions.fail("a [shared] module coordinate must be refused"))
              .onFailure(cause -> assertThat(cause.message()).contains(MODULE.asString())
                                            .doesNotContain(OTHER.asString()));
    }

    @Test
    void staged_copiesTheCoordinateSet() {
        var coordinates = new HashSet<>(List.of(MODULE));
        var staged = RepositoryType.Staged.staged(Path.of("root"), coordinates);

        coordinates.add(OTHER);
        assertThat(staged.moduleSet()).containsExactly(MODULE);
    }
}
