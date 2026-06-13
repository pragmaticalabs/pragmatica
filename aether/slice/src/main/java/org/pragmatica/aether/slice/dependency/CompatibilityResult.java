// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Version;


@SuppressWarnings("JBCT-UTIL-02")
public sealed interface CompatibilityResult {
    record Compatible(Version loadedVersion) implements CompatibilityResult {}

    record Conflict(Version loadedVersion, VersionPattern required) implements CompatibilityResult {}

    default boolean isCompatible() {
        return this instanceof Compatible;
    }

    default boolean isConflict() {
        return this instanceof Conflict;
    }

    static CompatibilityResult check(Version loadedVersion, VersionPattern required) {
        if (required.matches(loadedVersion)) {
            return new Compatible(loadedVersion);
        }

        return new Conflict(loadedVersion, required);
    }

    record unused() implements CompatibilityResult {}
}
