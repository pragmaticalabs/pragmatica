// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import org.pragmatica.aether.artifact.Artifact;


/// One FILE of a Maven coordinate, identified by its EXACT file name (the path segment): `lib-1.0.0.jar`,
/// `lib-1.0.0.pom`, `lib-1.0.0-sources.jar`, `lib-1.0.0.jar.asc`. The store keys every file separately (#281) and
/// keys it by that whole name (#1778): identity is never a parsed reading (classifier, extension) of the name, so no
/// two distinct names can alias one key, whatever they look like.
///
/// The PRIMARY file — `<artifactId>-<version>.jar` — is what the GAV-typed store operations mean, so internal
/// consumers that resolve a slice by its coordinate keep their signature.
public record ArtifactFile(Artifact artifact, String fileName) {
    public static ArtifactFile primary(Artifact artifact) {
        return artifactFile(artifact, "", "jar");
    }

    /// The file named exactly `fileName` under the coordinate's version.
    public static ArtifactFile named(Artifact artifact, String fileName) {
        return new ArtifactFile(artifact, fileName);
    }

    /// The conventionally named file `<artifactId>-<version>[-<classifier>].<extension>`; a blank classifier
    /// means "none".
    public static ArtifactFile artifactFile(Artifact artifact, String classifier, String extension) {
        var stem = artifact.artifactId().id() + "-" + artifact.version().withQualifier();

        return named(artifact,
                     classifier.isEmpty()
                     ? stem + "." + extension
                     : stem + "-" + classifier + "." + extension);
    }

    public String asString() {
        return artifact.asString() + ":" + fileName;
    }
}
