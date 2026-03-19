package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;

public interface Repository {
    Promise<Location> locate(Artifact artifact);

    /// Locate a blueprint-classifier artifact ({artifactId}-{version}-blueprint.jar).
    /// Default delegates to locate() — repositories that support classifiers should override.
    default Promise<Location> locateBlueprint(Artifact artifact) {
        return locate(artifact);
    }
}
