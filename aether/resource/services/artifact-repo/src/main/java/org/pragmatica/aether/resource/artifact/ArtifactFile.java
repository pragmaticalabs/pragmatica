// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Option;

/// One FILE of a Maven coordinate: the coordinate plus the classifier and extension that tell
/// `lib-1.0.0.jar`, `lib-1.0.0.pom` and `lib-1.0.0-sources.jar` apart. The store keys every
/// file separately (#281); a GAV-only key made the pom a duplicate of the jar.
///
/// The PRIMARY file — extension `jar`, no classifier — is what the GAV-typed store operations
/// mean, so internal consumers that resolve a slice by its coordinate keep their signature.
public record ArtifactFile(Artifact artifact, Option<String> classifier, String extension) {
    public static final String PRIMARY_EXTENSION = "jar";

    public static ArtifactFile primary(Artifact artifact) {
        return new ArtifactFile(artifact, Option.none(), PRIMARY_EXTENSION);
    }

    /// A blank classifier means "none" — the Maven path parser yields `""` for an unclassified file.
    public static ArtifactFile artifactFile(Artifact artifact, String classifier, String extension) {
        return new ArtifactFile(artifact,
                                classifier.isEmpty()
                                ? Option.none()
                                : Option.some(classifier),
                                extension);
    }

    public boolean isPrimary() {
        return classifier.isEmpty() && PRIMARY_EXTENSION.equals(extension);
    }

    /// The key segment that names this file under its version: `[classifier.]extension`.
    public String fileName() {
        return classifier.map(c -> c + "." + extension).or(extension);
    }

    public String asString() {
        return artifact.asString() + ":" + fileName();
    }
}
