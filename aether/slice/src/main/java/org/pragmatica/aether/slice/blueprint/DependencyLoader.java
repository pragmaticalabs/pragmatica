package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;

import java.util.Set;


/// Loads dependencies for a given artifact.
///
/// Abstraction to enable testing without actual JAR files.
public interface DependencyLoader {
    Promise<Set<Artifact>> loadDependencies(Artifact artifact);
}
