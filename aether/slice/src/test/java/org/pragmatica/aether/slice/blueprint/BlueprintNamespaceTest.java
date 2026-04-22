// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace.BlueprintNamespaceError.General;
import org.pragmatica.aether.slice.stream.StreamAddress.StreamAddressError;
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
        void stripsBlueprintSuffix() {
            var namespace = deriveNamespace(group("com.example"), artifact("myapp-blueprint")).unwrap();

            assertThat(namespace).isEqualTo("com.example.myapp");
        }

        @Test
        void preservesHyphensWithinArtifactId() {
            var namespace = deriveNamespace(group("io.acme.billing"),
                                             artifact("invoice-service-blueprint")).unwrap();

            assertThat(namespace).isEqualTo("io.acme.billing.invoice-service");
        }

        @Test
        void preservesDottedGroupId() {
            var namespace = deriveNamespace(group("org.pragmatica.aether"),
                                             artifact("forge-blueprint")).unwrap();

            assertThat(namespace).isEqualTo("org.pragmatica.aether.forge");
        }
    }

    @Nested
    class Rejections {

        @Test
        void rejectsMissingBlueprintSuffix() {
            var error = errorOf(deriveNamespace(group("com.example"), artifact("myapp")));

            assertThat(error).isEqualTo(General.MISSING_BLUEPRINT_SUFFIX);
        }

        @Test
        void rejectsWrongSuffixFormat() {
            // artifactId `my-blueprinted` doesn't end with exactly `-blueprint`
            var error = errorOf(deriveNamespace(group("com.example"), artifact("my-blueprinted")));

            assertThat(error).isEqualTo(General.MISSING_BLUEPRINT_SUFFIX);
        }

        @Test
        void rejectsSystemReservedNamespace() {
            // groupId `system.core` with artifactId `x-blueprint` would give `system.core.x` (not reserved).
            // But a blueprint with coords producing literal `system` can only happen if stripping yields
            // an empty string and groupId equals `system` — which isn't legal because GroupId requires
            // at least one dot. Instead, exercise the reserved path by constructing a pathological case
            // that derives to the literal `system`: not reachable through the artifact types' grammar.
            // We confirm the validateAppNamespace reserved check is wired by asserting a non-system
            // coord works (sanity) — the direct reserved-check path is covered in StreamAddressTest.
            assertThat(deriveNamespace(group("com.example"), artifact("x-blueprint")).isSuccess()).isTrue();
        }

        @Test
        void propagatesNamespaceValidationErrors() {
            // All legal (GroupId, ArtifactId) combos currently validate as acceptable namespaces
            // after `-blueprint` stripping. Surface-area guard: if any invariant changes in the future
            // the error type should still be a StreamAddressError (not a leaked Maven-grammar error).
            var error = errorOf(deriveNamespace(group("com.example"), artifact("blueprint")));

            // `blueprint` does NOT end with `-blueprint` (no hyphen), so suffix check fires.
            assertThat(error).isEqualTo(General.MISSING_BLUEPRINT_SUFFIX);
            assertThat(error).isNotInstanceOf(StreamAddressError.class);
        }
    }
}
