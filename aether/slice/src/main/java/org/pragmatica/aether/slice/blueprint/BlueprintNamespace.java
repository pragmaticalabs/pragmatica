// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.lang.Result;


/// Derives the stream namespace for a blueprint from its Maven coordinates.
///
/// Rule: `namespace = groupId + "." + artifactId`.
///
/// Blueprint identity is signalled by the Maven classifier `blueprint` on the produced JAR
/// (see `GenerateBlueprintMojo`), not by a suffix on the artifactId. The artifactId is taken
/// verbatim; no stripping, no required marker.
public final class BlueprintNamespace {
    private BlueprintNamespace() {}

    public static Result<String> deriveNamespace(BlueprintId blueprintId) {
        return deriveNamespace(blueprintId.artifact());
    }

    public static Result<String> deriveNamespace(Artifact artifact) {
        return deriveNamespace(artifact.groupId(), artifact.artifactId());
    }

    public static Result<String> deriveNamespace(GroupId groupId, ArtifactId artifactId) {
        return StreamAddress.validateAppNamespace(groupId.id() + "." + artifactId.id());
    }
}
