// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;


public interface Repository {
    Promise<Location> locate(Artifact artifact);

    default Promise<Location> locate(Artifact artifact, String classifier) {
        return locate(artifact);
    }
}
