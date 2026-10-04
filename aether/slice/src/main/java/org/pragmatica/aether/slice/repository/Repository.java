// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository;

import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.lang.Promise;


public interface Repository {
    /// The failure a repository returns when it ANSWERED and the artifact is not there. Every other
    /// failure (timeout, network, write, checksum) means the repository could not answer, which is a
    /// different fact: a composite of repositories reports "not found" only when all of them said
    /// this, and "unavailable" otherwise.
    interface Absent extends Cause {}

    /// True when `cause` says the artifact is genuinely NOT THERE: a single repository's [Absent], or the composite's
    /// `ArtifactNotFound` once EVERY repository answered "absent" (#1769). Anything else (timeout, network, a corrupt
    /// download, a location whose bytes cannot be read) means the source could not answer, which is a different fact and
    /// must be reported as itself, never folded into "try the next source" (#1436, #1927).
    static boolean isAbsent(Cause cause) {
        return cause instanceof Absent || cause instanceof SliceLoadingFailure.Intermittent.ArtifactNotFound;
    }

    /// `first`, or `next` only when `first` failed with a genuine not-found ([#isAbsent]); any other failure is returned
    /// as itself.
    static <T> Promise<T> orElseWhenAbsent(Promise<T> first, Supplier<Promise<T>> next) {
        return first.fold(result -> result.fold(cause -> isAbsent(cause)
                                                         ? next.get()
                                                         : cause.<T> promise(),
                                                Promise::success));
    }

    Promise<Location> locate(Artifact artifact);

    default Promise<Location> locate(Artifact artifact, String classifier) {
        return locate(artifact);
    }
}
