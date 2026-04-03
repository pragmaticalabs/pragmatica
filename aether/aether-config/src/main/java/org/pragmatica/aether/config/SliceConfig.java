package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Configuration for slice loading and repository order.
///
///
/// Example aether.toml:
/// ```
/// [slice]
/// repositories = ["local"]           # dev/forge default
/// # repositories = ["builtin"]       # prod
/// # repositories = ["local", "builtin"]  # hybrid - try local first
/// ```
///
/// @param repositories Ordered list of repository types to search for slices
@SuppressWarnings({"JBCT-ZONE-02", "JBCT-ZONE-03"}) public record SliceConfig(List<RepositoryType> repositories) {
    private static final SliceConfig DEFAULT = sliceConfig(List.of(new RepositoryType.Local())).unwrap();

    public static Result<SliceConfig> sliceConfig(List<RepositoryType> repositories) {
        return success(new SliceConfig(repositories));
    }

    public static SliceConfig sliceConfig() {
        return DEFAULT;
    }

    @SuppressWarnings("JBCT-NAM-01") public static Result<SliceConfig> sliceConfigFromNames(List<String> repositoryNames) {
        return checkNotEmpty(repositoryNames).flatMap(SliceConfig::toRepositoryTypes);
    }

    public static SliceConfig sliceConfig(RepositoryType... types) {
        return sliceConfig(List.of(types)).unwrap();
    }

    public SliceConfig withRepositories(List<RepositoryType> repositories) {
        return sliceConfig(repositories).unwrap();
    }

    private static Result<List<String>> checkNotEmpty(List<String> repositoryNames) {
        return option(repositoryNames).filter(names -> !names.isEmpty()).toResult(emptyListError());
    }

    private static SliceConfigError.InvalidSliceConfig emptyListError() {
        return SliceConfigError.InvalidSliceConfig.invalidSliceConfig("repositories list cannot be empty");
    }

    @SuppressWarnings("JBCT-NAM-01") private static Result<SliceConfig> toRepositoryTypes(List<String> names) {
        return Result.allOf(names.stream().map(RepositoryType::repositoryType)
                                        .toList()).map(SliceConfig::new);
    }

    public sealed interface SliceConfigError extends Cause {
        record unused() implements SliceConfigError {
            @Override public String message() {
                return "unused";
            }
        }

        record InvalidSliceConfig(String detail) implements SliceConfigError {
            public static Result<InvalidSliceConfig> invalidSliceConfig(String detail, boolean validated) {
                return success(new InvalidSliceConfig(detail));
            }

            public static InvalidSliceConfig invalidSliceConfig(String detail) {
                return invalidSliceConfig(detail, true).unwrap();
            }

            @Override public String message() {
                return "Invalid slice configuration: " + detail;
            }
        }
    }
}
