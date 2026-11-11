// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public sealed interface RepositoryType {
    record Local() implements RepositoryType {}

    record Builtin() implements RepositoryType {}

    record Remote(String id, String url) implements RepositoryType {}

    /// A module's own packaged slices (Ember JUnit extension, #3021): a coordinate in `moduleSet` is located under
    /// `root` ALONE, never in a Maven repository; every other coordinate goes to the Maven local repository. Constructed
    /// only in code, never parsed from TOML. As the node's ONLY repository entry it makes a staged miss a not-found.
    record Staged(Path root, Set<Artifact> moduleSet) implements RepositoryType {
        public static Staged staged(Path root, Collection<Artifact> moduleSet) {
            return new Staged(root, Set.copyOf(moduleSet));
        }

        /// This staging, or a refusal naming every module coordinate that is also declared `[shared]`: a failed locate of
        /// such a coordinate would be taken for a runtime-provided library instead of a missing artifact.
        public Result<Staged> refuseShared(Collection<Artifact> sharedCoordinates) {
            var clashes = sharedCoordinates.stream()
                                           .filter(moduleSet::contains)
                                           .map(Artifact::asString)
                                           .sorted()
                                           .toList();

            return clashes.isEmpty()
                   ? success(this)
                   : RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("module coordinate declared [shared]: "
                                                                                    + String.join(", ", clashes)).result();
        }
    }

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

    @SuppressWarnings("JBCT-RET-07")
    private static Result<RepositoryType> fromNormalized(String name) {
        return switch (name.toLowerCase()) {
            case "local" -> success(new Local());
            case "builtin" -> success(new Builtin());
            default -> {
                if (name.toLowerCase().startsWith("remote:")) {
                    yield parseRemote(name.substring(7));
                }

                yield RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("unknown repository type: " + name + ". Valid types: local, builtin, remote:<id-or-url>").result();
            }
        };
    }

    private static Result<RepositoryType> parseRemote(String value) {
        if (value.isEmpty()) {
            return RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("remote repository requires an identifier or URL after 'remote:'").result();
        }

        if (value.equalsIgnoreCase("central")) {
            return success(new Remote("central", CENTRAL_URL));
        }

        var colonIdx = value.indexOf(':');

        if (colonIdx > 0 && value.substring(colonIdx + 1).startsWith("http")) {
            return success(new Remote(value.substring(0, colonIdx), value.substring(colonIdx + 1)));
        }

        if (value.startsWith("http://") || value.startsWith("https://")) {
            var id = deriveIdFromUrl(value);

            return success(new Remote(id, value));
        }

        return RepositoryTypeError.InvalidRepositoryType.invalidRepositoryType("invalid remote repository format: " + value
                                                                              + ". Use 'remote:central', 'remote:https://...', or 'remote:id:https://...'").result();
    }

    private static String deriveIdFromUrl(String url) {
        try {
            var uri = java.net.URI.create(url);

            return uri.getHost()
                      .replace('.', '-');
        } catch (Exception e) {
            return "remote";
        }
    }

    sealed interface RepositoryTypeError extends Cause {
        record unused() implements RepositoryTypeError {
            @Override
            public String message() {
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

            @Override
            public String message() {
                return "Invalid repository type: " + detail;
            }
        }
    }
}
