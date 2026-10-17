// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;


public interface DependencyLoader {
    Promise<Set<Artifact>> loadDependencies(Artifact artifact);
}
