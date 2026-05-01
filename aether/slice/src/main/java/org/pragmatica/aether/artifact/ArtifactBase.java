// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;


@Codec public record ArtifactBase(GroupId groupId, ArtifactId artifactId) {
    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid artifact base format %s");

    public static Result<ArtifactBase> artifactBase(String artifactBaseString) {
        var parts = artifactBaseString.split(":", 2);
        if (parts.length != 2) {return INVALID_FORMAT.apply(artifactBaseString).result();}
        return Result.all(GroupId.groupId(parts[0]), ArtifactId.artifactId(parts[1])).map(ArtifactBase::new);
    }

    public static ArtifactBase artifactBase(GroupId groupId, ArtifactId artifactId) {
        return new ArtifactBase(groupId, artifactId);
    }

    public static ArtifactBase artifactBase(Artifact artifact) {
        return new ArtifactBase(artifact.groupId(), artifact.artifactId());
    }

    public Artifact withVersion(Version version) {
        return Artifact.artifact(groupId, artifactId, version);
    }

    public boolean matches(Artifact artifact) {
        return groupId.equals(artifact.groupId()) && artifactId.equals(artifact.artifactId());
    }

    @Override public String toString() {
        return asString();
    }

    public String asString() {
        return groupId + ":" + artifactId;
    }
}
