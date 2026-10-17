// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
