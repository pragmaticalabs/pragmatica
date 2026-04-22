// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// Derives the stream namespace for a blueprint from its Maven coordinates.
///
/// Rule: `namespace = groupId + "." + strip_suffix(artifactId, "-blueprint")`.
///
/// The `-blueprint` suffix is mandatory on blueprint artifactIds. A blueprint
/// whose artifactId lacks the suffix cannot participate in stream addressing
/// and is rejected at build time and at runtime.
public final class BlueprintNamespace {
    public static final String BLUEPRINT_SUFFIX = "-blueprint";

    public sealed interface BlueprintNamespaceError extends Cause {
        enum General implements BlueprintNamespaceError {
            MISSING_BLUEPRINT_SUFFIX("Blueprint artifactId must end with '-blueprint'");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements BlueprintNamespaceError {
            @Override public String message() {
                return "";
            }
        }
    }

    private BlueprintNamespace() {}

    public static Result<String> deriveNamespace(BlueprintId blueprintId) {
        return deriveNamespace(blueprintId.artifact());
    }

    public static Result<String> deriveNamespace(Artifact artifact) {
        return deriveNamespace(artifact.groupId(), artifact.artifactId());
    }

    public static Result<String> deriveNamespace(GroupId groupId, ArtifactId artifactId) {
        var artifactIdString = artifactId.id();
        if (!artifactIdString.endsWith(BLUEPRINT_SUFFIX)) {
            return BlueprintNamespaceError.General.MISSING_BLUEPRINT_SUFFIX.result();
        }
        var strippedArtifactId = artifactIdString.substring(0, artifactIdString.length() - BLUEPRINT_SUFFIX.length());
        var candidate = groupId.id() + "." + strippedArtifactId;
        return StreamAddress.validateAppNamespace(candidate);
    }
}
