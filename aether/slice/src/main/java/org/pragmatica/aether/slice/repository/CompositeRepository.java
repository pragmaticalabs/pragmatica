// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;


/// A [Repository] over several, consulted in order (#1769, #1927).
///
/// `failures` holds, in lookup order, what each repository tried so far answered. A repository that failed does not end
/// the search (the next one may have the artifact), but its cause is kept. The artifact is reported not found
/// ([SliceLoadingFailure.Intermittent.ArtifactNotFound]) only when EVERY repository answered "absent" ([Repository.Absent]);
/// if any could not answer (timeout, network), it is not known to be absent and the cause names each outcome
/// ([SliceLoadingFailure.Intermittent.ArtifactUnavailable]). With no repository configured nothing answered "unavailable", so the answer is not found, and a caller that falls
/// through to another source on a genuine not-found (the artifact store) still does.
public final class CompositeRepository implements Repository {
    private final List<Repository> repositories;

    private CompositeRepository(List<Repository> repositories) {
        this.repositories = List.copyOf(repositories);
    }

    public static Repository compositeRepository(List<Repository> repositories) {
        return new CompositeRepository(repositories);
    }

    @Override
    public Promise<Location> locate(Artifact artifact) {
        return search(artifact, repositories, List.of(), repository -> repository.locate(artifact));
    }

    @Override
    public Promise<Location> locate(Artifact artifact, String classifier) {
        return search(artifact, repositories, List.of(), repository -> repository.locate(artifact, classifier));
    }

    private static Promise<Location> search(Artifact artifact,
                                            List<Repository> remaining,
                                            List<Cause> failures,
                                            Function<Repository, Promise<Location>> locate) {
        if (remaining.isEmpty()) {
            return unlocated(artifact, failures).promise();
        }

        var rest = remaining.subList(1, remaining.size());

        return locate.apply(remaining.getFirst())
                     .fold(result -> result.fold(cause -> search(artifact,
                                                                 rest,
                                                                 plus(failures, cause),
                                                                 locate),
                                                 Promise::success));
    }

    private static List<Cause> plus(List<Cause> failures, Cause cause) {
        return Stream.concat(failures.stream(),
                             Stream.of(cause))
                     .toList();
    }

    private static Cause unlocated(Artifact artifact, List<Cause> failures) {
        return failures.stream()
                       .allMatch(Repository.Absent.class::isInstance)
               ? new SliceLoadingFailure.Intermittent.ArtifactNotFound(artifact.asString())
               : new SliceLoadingFailure.Intermittent.ArtifactUnavailable(artifact.asString(), outcomes(failures));
    }

    private static List<String> outcomes(List<Cause> failures) {
        return IntStream.range(0,
                               failures.size())
                        .mapToObj(index -> outcome(index,
                                                   failures.get(index)))
                        .toList();
    }

    private static String outcome(int index, Cause cause) {
        return "repository #" + index + (cause instanceof Repository.Absent
                                         ? " absent: "
                                         : " unavailable: ") + cause.message();
    }
}
