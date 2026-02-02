package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;

import java.util.Set;

/**
 * Loads dependencies for a given artifact.
 * <p>
 * Abstraction to enable testing without actual JAR files.
 */
public interface DependencyLoader {
    /**
     * Load dependencies for the given artifact.
     *
     * @param artifact The artifact to load dependencies for
     *
     * @return Set of dependency artifacts
     */
    Promise<Set<Artifact>> loadDependencies(Artifact artifact);
}
