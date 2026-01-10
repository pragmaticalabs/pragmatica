package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;

/**
 * Configuration for slice loading and repository order.
 *
 * <p>Example aether.toml:
 * <pre>
 * [slice]
 * repositories = ["local"]           # dev/forge default
 * # repositories = ["builtin"]       # prod
 * # repositories = ["local", "builtin"]  # hybrid - try local first
 * </pre>
 *
 * @param repositories Ordered list of repository types to search for slices
 */
public record SliceConfig(List<RepositoryType> repositories) {
    private static final SliceConfig DEFAULT = new SliceConfig(List.of(RepositoryType.LOCAL));

    /**
     * Default configuration with local repository only.
     */
    public static SliceConfig defaults() {
        return DEFAULT;
    }

    /**
     * Factory method following JBCT naming convention.
     *
     * @param repositoryNames List of repository type names (e.g., ["local", "builtin"])
     * @return Result containing valid SliceConfig or error
     */
    public static Result<SliceConfig> sliceConfig(List<String> repositoryNames) {
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return SliceConfigError.InvalidSliceConfig.invalidConfig("repositories list cannot be empty")
                                   .result();
        }
        return Result.allOf(repositoryNames.stream()
                                           .map(RepositoryType::repositoryType)
                                           .toList())
                     .map(SliceConfig::new);
    }

    /**
     * Create configuration with specified repository types.
     */
    public static SliceConfig withTypes(RepositoryType... types) {
        return new SliceConfig(List.of(types));
    }

    /**
     * Create new config with different repositories.
     */
    public SliceConfig withRepositories(List<RepositoryType> repositories) {
        return new SliceConfig(repositories);
    }

    /**
     * Error hierarchy for slice configuration failures.
     */
    public sealed interface SliceConfigError extends Cause {
        /**
         * Configuration error for SliceConfig.
         */
        record InvalidSliceConfig(String detail) implements SliceConfigError {
            public static InvalidSliceConfig invalidConfig(String detail) {
                return new InvalidSliceConfig(detail);
            }

            @Override
            public String message() {
                return "Invalid slice configuration: " + detail;
            }
        }
    }
}
