package org.pragmatica.aether.repository;

import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.infra.artifact.ArtifactStore;
import org.pragmatica.aether.slice.repository.Repository;

import java.util.List;

import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;

/**
 * Factory for creating Repository instances from configuration.
 * <p>
 * Translates {@link RepositoryType} configuration values into actual
 * {@link Repository} implementations.
 */
public interface RepositoryFactory {
    /**
     * Create a repository for the given type.
     *
     * @param type Repository type from configuration
     * @return Repository implementation
     */
    Repository create(RepositoryType type);

    /**
     * Create repositories for all types in the configuration.
     *
     * @param config Slice configuration containing repository types
     * @return List of Repository implementations in configuration order
     */
    default List<Repository> createAll(SliceConfig config) {
        return config.repositories()
                     .stream()
                     .map(this::create)
                     .toList();
    }

    /**
     * Create a RepositoryFactory with the given ArtifactStore for BUILTIN repositories.
     *
     * @param artifactStore Store for built-in artifact resolution
     * @return RepositoryFactory instance
     */
    static RepositoryFactory repositoryFactory(ArtifactStore artifactStore) {
        return type -> switch (type) {
            case LOCAL -> localRepository();
            case BUILTIN -> BuiltinRepository.builtinRepository(artifactStore);
        };
    }
}
