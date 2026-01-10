package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

/**
 * Types of slice repositories supported by Aether.
 *
 * <p>Used in configuration to specify repository order:
 * <pre>
 * [slice]
 * repositories = ["local"]           # dev/forge default
 * repositories = ["builtin"]         # prod
 * repositories = ["local", "builtin"] # hybrid - try local first
 * </pre>
 */
public enum RepositoryType {
    /**
     * Maven local repository (~/.m2/repository).
     * Default for development and Forge simulator.
     */
    LOCAL("local"),
    /**
     * Built-in artifact store (in-memory or cluster-shared).
     * Default for production deployments.
     */
    BUILTIN("builtin");
    private final String configName;
    RepositoryType(String configName) {
        this.configName = configName;
    }
    /**
     * Name used in configuration files.
     */
    public String configName() {
        return configName;
    }
    /**
     * Parse repository type from configuration string.
     *
     * @param name Configuration name (e.g., "local", "builtin")
     * @return Result containing the repository type or error
     */
    public static Result<RepositoryType> repositoryType(String name) {
        if (name == null || name.isBlank()) {
            return RepositoryTypeError.InvalidRepositoryType.invalidType("repository name cannot be blank")
                                      .result();
        }
        var normalized = name.trim()
                             .toLowerCase();
        return switch (normalized) {
            case "local" -> Result.success(LOCAL);
            case "builtin" -> Result.success(BUILTIN);
            default -> RepositoryTypeError.InvalidRepositoryType.invalidType("unknown repository type: " + name
                                                                             + ". Valid types: local, builtin")
                                          .result();
        };
    }
    /**
     * Error hierarchy for repository type configuration failures.
     */
    public sealed interface RepositoryTypeError extends Cause {
        /**
         * Configuration error for invalid repository type.
         */
        record InvalidRepositoryType(String detail) implements RepositoryTypeError {
            public static InvalidRepositoryType invalidType(String detail) {
                return new InvalidRepositoryType(detail);
            }

            @Override
            public String message() {
                return "Invalid repository type: " + detail;
            }
        }
    }
}
