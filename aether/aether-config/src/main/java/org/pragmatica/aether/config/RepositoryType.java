package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Types of slice repositories supported by Aether.
///
///
/// Used in configuration to specify repository order:
/// ```
/// [slice]
/// repositories = ["local"]           # dev/forge default
/// repositories = ["builtin"]         # prod
/// repositories = ["local", "builtin"] # hybrid - try local first
/// ```
public enum RepositoryType {
    /// Maven local repository (~/.m2/repository).
    /// Default for development and Forge simulator.
    LOCAL("local"),
    /// Built-in artifact store (in-memory or cluster-shared).
    /// Default for production deployments.
    BUILTIN("builtin");
    private final String configName;
    RepositoryType(String configName) {
        this.configName = configName;
    }
    /// Name used in configuration files.
    public String configName() {
        return configName;
    }
    /// Parse repository type from configuration string.
    ///
    /// @param name Configuration name (e.g., "local", "builtin")
    /// @return Result containing the repository type or error
    public static Result<RepositoryType> repositoryType(String name) {
        return option(name).map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toResult(blankNameError())
                     .flatMap(RepositoryType::fromNormalized);
    }
    private static RepositoryTypeError.InvalidRepositoryType blankNameError() {
        return RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("repository name cannot be blank");
    }
    private static Result<RepositoryType> fromNormalized(String name) {
        return switch (name.toLowerCase()) {
            case "local" -> success(LOCAL);
            case "builtin" -> success(BUILTIN);
            default -> RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("unknown repository type: " + name
                                                                                       + ". Valid types: local, builtin")
                                          .result();
        };
    }
    /// Error hierarchy for repository type configuration failures.
    public sealed interface RepositoryTypeError extends Cause {
        record unused() implements RepositoryTypeError {
            @Override
            public String message() {
                return "unused";
            }
        }

        /// Configuration error for invalid repository type.
        record InvalidRepositoryType(String detail) implements RepositoryTypeError {
            /// Factory method following JBCT naming convention.
            public static Result<InvalidRepositoryType> invalidRepositoryType(String detail, boolean validated) {
                return success(new InvalidRepositoryType(detail));
            }

            public static InvalidRepositoryType invalidRepositoryType(String detail) {
                return invalidRepositoryType(detail, true).unwrap();
            }

            @Override
            public String message() {
                return "Invalid repository type: " + detail;
            }
        }
    }
}
