package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;

public interface Repository {
    Promise<Location> locate(Artifact artifact);

    /// Locate an artifact with a Maven classifier (e.g., "blueprint" → {artifactId}-{version}-blueprint.jar).
    /// Default delegates to locate() ignoring classifier — repositories that support classifiers should override.
    default Promise<Location> locate(Artifact artifact, String classifier) {
        return locate(artifact);
    }
}
