// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.StreamOps;


@SuppressWarnings({"JBCT-RET-05", "JBCT-PAT-01"})
public interface SliceDependencies {
    static Result<List<DependencyDescriptor>> load(String sliceClassName, ClassLoader classLoader) {
        return StreamOps.readResource(classLoader, "META-INF/dependencies/" + sliceClassName)
                        .map(SliceDependencies::parseDependencies)
                        .orElse(Result.success(List.of()));
    }

    private static List<DependencyDescriptor> parseDependencies(String content) {
        return Arrays.stream(content.split("\n"))
                     .flatMap(line -> DependencyDescriptor.dependencyDescriptor(line).stream())
                     .toList();
    }
}
