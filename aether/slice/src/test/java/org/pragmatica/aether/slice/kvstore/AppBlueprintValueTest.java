// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppBlueprintValueTest {
    private static ExpandedBlueprint sampleBlueprint() {
        var bpId = BlueprintId.blueprintId("org.example:test-app:1.0.0").unwrap();
        var artifact = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(artifact, 1, false).unwrap();
        return ExpandedBlueprint.expandedBlueprint(bpId, List.of(slice));
    }

    @Test
    void appBlueprintValue_factoryDefaultsRegisterOnlyToFalse() {
        var bp = sampleBlueprint();
        var value = AppBlueprintValue.appBlueprintValue(bp);

        assertThat(value.blueprint()).isEqualTo(bp);
        assertThat(value.registerOnly()).isFalse();
    }

    @Test
    void appBlueprintValue_factoryWithExplicitRegisterOnlyTrue() {
        var bp = sampleBlueprint();
        var value = AppBlueprintValue.appBlueprintValue(bp, true);

        assertThat(value.blueprint()).isEqualTo(bp);
        assertThat(value.registerOnly()).isTrue();
    }

    @Test
    void appBlueprintValue_factoryWithExplicitRegisterOnlyFalse() {
        var bp = sampleBlueprint();
        var value = AppBlueprintValue.appBlueprintValue(bp, false);

        assertThat(value.blueprint()).isEqualTo(bp);
        assertThat(value.registerOnly()).isFalse();
    }

    @Test
    void appBlueprintValue_backwardCompatConstructorDefaultsRegisterOnlyToFalse() {
        var bp = sampleBlueprint();
        var value = new AppBlueprintValue(bp);

        assertThat(value.blueprint()).isEqualTo(bp);
        assertThat(value.registerOnly()).isFalse();
    }

    @Test
    void appBlueprintValue_canonicalConstructorPreservesRegisterOnly() {
        var bp = sampleBlueprint();
        var registerOnlyValue = new AppBlueprintValue(bp, true);
        var activeValue = new AppBlueprintValue(bp, false);

        assertThat(registerOnlyValue.registerOnly()).isTrue();
        assertThat(activeValue.registerOnly()).isFalse();
        assertThat(registerOnlyValue.blueprint()).isEqualTo(activeValue.blueprint());
    }
}
