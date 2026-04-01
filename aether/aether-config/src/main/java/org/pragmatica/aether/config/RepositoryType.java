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
    /// Maven local repository (~/.m2/repository).
    record Local() implements RepositoryType{}

    /// Built-in artifact store (in-memory or cluster-shared).
    record Builtin() implements RepositoryType{}

    /// Remote Maven repository (Maven Central, private Nexus, etc.)
    /// @param id Repository identifier (for settings.xml credential matching)
    /// @param url Base URL of the repository
    record Remote(String id, String url) implements RepositoryType{}

    /// Well-known repository: Maven Central.
    String CENTRAL_URL = "https://repo1.maven.org/maven2/";

    /// Parse repository type from configuration string.
    ///
    /// Supported formats:
    /// - "local" → Local repository
    /// - "builtin" → Built-in artifact store
    /// - "remote:central" → Maven Central
    /// - "remote:https://..." → Custom remote URL (id derived from hostname)
    /// - "remote:myid:https://..." → Custom remote with explicit id
    ///
    /// @param name Configuration name
    /// @return Result containing the repository type or error
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
        return switch (name.toLowerCase()) {case "local" -> success(new Local());case "builtin" -> success(new Builtin());default -> {
            if ( name.toLowerCase().startsWith("remote:")) {
            yield parseRemote(name.substring(7));}
            yield RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("unknown repository type: " + name + ". Valid types: local, builtin, remote:<id-or-url>")
            .result();
        }};
    }

    private static Result<RepositoryType> parseRemote(String value) {
        if ( value.isEmpty()) {
        return RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("remote repository requires an identifier or URL after 'remote:'")
        .result();}
        // Well-known: "central"
        if ( value.equalsIgnoreCase("central")) {
        return success(new Remote("central", CENTRAL_URL));}
        // URL with explicit id: "myid:https://..."
        var colonIdx = value.indexOf(':');
        if ( colonIdx > 0 && value.substring(colonIdx + 1).startsWith("http")) {
        return success(new Remote(value.substring(0, colonIdx), value.substring(colonIdx + 1)));}
        // Plain URL: "https://..."
        if ( value.startsWith("http://") || value.startsWith("https://")) {
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
        }























        catch (Exception e) {
            return "remote";
        }
    }

    /// Error hierarchy for repository type configuration failures.
    sealed interface RepositoryTypeError extends Cause {
        record unused() implements RepositoryTypeError {
            @Override public String message() {
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

            @Override public String message() {
                return "Invalid repository type: " + detail;
            }
        }
    }
}
