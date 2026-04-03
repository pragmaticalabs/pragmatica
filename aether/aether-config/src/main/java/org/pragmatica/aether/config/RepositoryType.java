package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Types of slice repositories supported by Aether.
///
/// Used in configuration to specify repository order:
/// ```
/// [slice]
/// repositories = ["local"]                    # dev/forge default
/// repositories = ["builtin"]                  # prod
/// repositories = ["local", "remote:central"]  # resolve from Maven Central
/// repositories = ["remote:https://nexus.example.com/repository/releases/"]
/// ```
public sealed interface RepositoryType {
    record Local() implements RepositoryType{}

    record Builtin() implements RepositoryType{}

    record Remote(String id, String url) implements RepositoryType{}

    String CENTRAL_URL = "https://repo1.maven.org/maven2/";

    static Result<RepositoryType> repositoryType(String name) {
        return option(name).map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toResult(blankNameError())
                     .flatMap(RepositoryType::fromNormalized);
    }

    private static RepositoryTypeError.InvalidRepositoryType blankNameError() {
        return RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("repository name cannot be blank");
    }

    private static Result<RepositoryType> fromNormalized(String name) {
        return switch (name.toLowerCase()){
            case "local" -> success(new Local());
            case "builtin" -> success(new Builtin());
            default -> {
                if (name.toLowerCase().startsWith("remote:")) {yield parseRemote(name.substring(7));}
                yield RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("unknown repository type: " + name + ". Valid types: local, builtin, remote:<id-or-url>")
                                                                                     .result();
            }
        };
    }

    private static Result<RepositoryType> parseRemote(String value) {
        if (value.isEmpty()) {return RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("remote repository requires an identifier or URL after 'remote:'")
                                                                                                    .result();}
        if (value.equalsIgnoreCase("central")) {return success(new Remote("central", CENTRAL_URL));}
        var colonIdx = value.indexOf(':');
        if (colonIdx > 0 && value.substring(colonIdx + 1).startsWith("http")) {return success(new Remote(value.substring(0,
                                                                                                                         colonIdx),
                                                                                                         value.substring(colonIdx + 1)));}
        if (value.startsWith("http://") || value.startsWith("https://")) {
            var id = deriveIdFromUrl(value);
            return success(new Remote(id, value));
        }
        return RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("invalid remote repository format: " + value + ". Use 'remote:central', 'remote:https://...', or 'remote:id:https://...'")
                                                                              .result();
    }

    private static String deriveIdFromUrl(String url) {
        try {
            var uri = java.net.URI.create(url);
            return uri.getHost().replace('.', '-');
        } catch (Exception e) {
            return "remote";
        }
    }

    sealed interface RepositoryTypeError extends Cause {
        record unused() implements RepositoryTypeError {
            @Override public String message() {
                return "unused";
            }
        }

        record InvalidRepositoryType(String detail) implements RepositoryTypeError {
            public static Result<InvalidRepositoryType> invalidRepositoryType(String detail, boolean validated) {
                return success(new InvalidRepositoryType(detail));
            }

            public static InvalidRepositoryType invalidRepositoryType(String detail) {
                return invalidRepositoryType(detail, true).unwrap();
            }

            @Override public String message() {
                return "Invalid repository type: " + detail;
            }
        }
    }
}
