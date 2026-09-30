// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;


public interface Repository {
    /// The failure a repository returns when it ANSWERED and the artifact is not there. Every other
    /// failure (timeout, network, write, checksum) means the repository could not answer, which is a
    /// different fact: a composite of repositories reports "not found" only when all of them said
    /// this, and "unavailable" otherwise.
    interface Absent extends Cause {}

    Promise<Location> locate(Artifact artifact);

    default Promise<Location> locate(Artifact artifact, String classifier) {
        return locate(artifact);
    }
}
