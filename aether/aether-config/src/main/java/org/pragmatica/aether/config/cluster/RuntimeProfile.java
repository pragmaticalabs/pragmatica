// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


public record RuntimeProfile(RuntimeType type, Option<String> image, Option<String> jvmArgs, Option<String> jarUrl) {
    public static RuntimeProfile runtimeProfile(RuntimeType type, Option<String> image, Option<String> jvmArgs) {
        return new RuntimeProfile(type, image, jvmArgs, Option.empty());
    }

    public static RuntimeProfile runtimeProfile(RuntimeType type,
                                                Option<String> image,
                                                Option<String> jvmArgs,
                                                Option<String> jarUrl) {
        return new RuntimeProfile(type, image, jvmArgs, jarUrl);
    }

    /// Mirrors the renderer's launch choice: container runtimes (and an absent profile, handled by the caller)
    /// launch `image`, everything else launches `jarUrl`.
    public boolean isContainer() {
        return type == RuntimeType.CONTAINER || type == RuntimeType.DOCKER || type == RuntimeType.MANAGED_CONTAINER;
    }
}
