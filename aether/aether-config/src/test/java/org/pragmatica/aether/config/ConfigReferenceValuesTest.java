// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Map;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ConfigReferenceValuesTest {
    @Test
    void resolvesNamedSourceEnvironmentAndSecretConventionWithoutMixingAccounts() {
        var bindings = Map.of("WEST_TOKEN", "west-secret", "AETHER_EAST_TOKEN", "east-secret");

        assertThat(ConfigReferenceValues.resolve("${env:WEST_TOKEN}",
                                                 key -> Option.option(bindings.get(key))).unwrap()).isEqualTo("west-secret");
        assertThat(ConfigReferenceValues.resolve("${secrets:east-token}",
                                                 key -> Option.option(bindings.get(key)))
                                        .unwrap()).isEqualTo("east-secret");
    }

    @Test
    void missingAndUnrecognizedReferencesFailBeforeProviderUse() {
        assertThat(ConfigReferenceValues.resolve("${env:ABSENT}", _ -> Option.none()).isFailure()).isTrue();
        assertThat(ConfigReferenceValues.resolve("${unknown:ABSENT}", _ -> Option.none()).isFailure()).isTrue();
        assertThat(ConfigReferenceValues.resolve("${secrets:ABSENT}", _ -> Option.some(" ")).isFailure()).isTrue();
    }

    @Test
    void resolutionTreatsBindingAsOpaqueLiteralWithoutRecursiveInterpolation() {
        assertThat(ConfigReferenceValues.resolve("prefix-${env:TOKEN}-suffix", _ -> Option.some("a$1\\b")).unwrap()).isEqualTo("prefix-a$1\\b-suffix");
        var lookups = new java.util.ArrayList<String>();
        assertThat(ConfigReferenceValues.resolve("prefix-${secrets:token}-suffix", name -> {
            lookups.add(name);
            return Option.some("${env:OTHER}-${not-an-expression}");
        }).unwrap()).isEqualTo("prefix-${env:OTHER}-${not-an-expression}-suffix");
        assertThat(lookups).containsExactly("AETHER_TOKEN");
    }
    @Test
    void malformedOriginalExpressionsFailBeforeReadingAnyBinding() {
        var lookups = new java.util.ArrayList<String>();
        for (var expression : java.util.List.of("${env:TOKEN}-${unknown:OTHER}", "${env:${env:OTHER}}", "${env:TOKEN")) {
            assertThat(ConfigReferenceValues.resolve(expression, name -> {
                lookups.add(name);
                return Option.some("secret");
            }).isFailure()).isTrue();
        }
        assertThat(lookups).isEmpty();
    }
}
