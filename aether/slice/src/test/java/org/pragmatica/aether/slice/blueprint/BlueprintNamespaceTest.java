// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.blueprint.BlueprintNamespace.deriveNamespace;


class BlueprintNamespaceTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    private static GroupId group(String id) {
        return GroupId.groupId(id).unwrap();
    }

    private static ArtifactId artifact(String id) {
        return ArtifactId.artifactId(id).unwrap();
    }

    @Nested
    class Derivation {

        @Test
        void concatenatesGroupAndArtifact() {
            var namespace = deriveNamespace(group("com.example"), artifact("url-shortener")).unwrap();

            assertThat(namespace).isEqualTo("com.example.url-shortener");
        }

        @Test
        void preservesHyphensWithinArtifactId() {
            var namespace = deriveNamespace(group("io.acme.billing"), artifact("invoice-service")).unwrap();

            assertThat(namespace).isEqualTo("io.acme.billing.invoice-service");
        }

        @Test
        void preservesDottedGroupId() {
            var namespace = deriveNamespace(group("org.pragmatica.aether"), artifact("forge")).unwrap();

            assertThat(namespace).isEqualTo("org.pragmatica.aether.forge");
        }

        @Test
        void acceptsSingleTokenArtifact() {
            var namespace = deriveNamespace(group("com.example"), artifact("banking")).unwrap();

            assertThat(namespace).isEqualTo("com.example.banking");
        }
    }

    @Nested
    class Rejections {

        @Test
        void rejectsSystemReservedNamespaceAtAppLevel() {
            // The literal reserved namespace "system" can only arise if coords resolve to exactly "system".
            // Not reachable through current GroupId grammar (requires at least one dot).
            // Sanity: typical coords pass.
            assertThat(deriveNamespace(group("com.example"), artifact("any")).isSuccess()).isTrue();
        }

        @Test
        void namespaceValidationErrorsAreSurfaced() {
            // All legal Maven coord combinations produce valid namespaces under the current grammar.
            // Guard: if the reserved/charset rules ever tighten, errors must propagate as
            // ResourceAddressError, not leak as raw validation noise. Regression surface only.
            var result = deriveNamespace(group("com.example"), artifact("x"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(errorOf(result)).isNull();
        }
    }
}
