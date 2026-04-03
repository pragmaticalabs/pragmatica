package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;


public interface Repository {
    Promise<Location> locate(Artifact artifact);

    default Promise<Location> locate(Artifact artifact, String classifier) {
        return locate(artifact);
    }
}
