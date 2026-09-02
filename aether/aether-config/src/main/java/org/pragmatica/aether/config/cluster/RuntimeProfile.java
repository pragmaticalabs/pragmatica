// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.config.ConfigKeyLive;


/// `name` is #693: parsed and stored in every `RuntimeProfile`, but nothing reads this accessor — the
/// map key it's grouped under (`ClusterBootstrapConfig.runtimes(): Map<String, RuntimeProfile>`) carries
/// the name identity for every real consumer instead. `@ConfigKeyLive`-suppressed rather than deleted:
/// #693 owns the fix, not #519's dead-surface guard.
public record RuntimeProfile(@ConfigKeyLive("#693: parsed but never read — ClusterBootstrapConfig.runtimes() map key carries name instead") String name,
                             RuntimeType type,
                             Option<String> image,
                             Option<String> jvmArgs,
                             Option<String> jarUrl) {
    public static RuntimeProfile runtimeProfile(String name,
                                                RuntimeType type,
                                                Option<String> image,
                                                Option<String> jvmArgs) {
        return new RuntimeProfile(name, type, image, jvmArgs, Option.empty());
    }

    public static RuntimeProfile runtimeProfile(String name,
                                                RuntimeType type,
                                                Option<String> image,
                                                Option<String> jvmArgs,
                                                Option<String> jarUrl) {
        return new RuntimeProfile(name, type, image, jvmArgs, jarUrl);
    }
}
