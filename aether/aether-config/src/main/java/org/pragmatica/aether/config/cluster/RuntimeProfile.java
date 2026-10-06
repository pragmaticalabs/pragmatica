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

    /// Mirrors the renderer's launch choice: container runtimes (and an absent profile, handled by the caller)
    /// launch `image`, everything else launches `jarUrl`.
    public boolean isContainer() {
        return type == RuntimeType.CONTAINER || type == RuntimeType.DOCKER || type == RuntimeType.MANAGED_CONTAINER;
    }

    /// Placeholder a pin may carry to follow `[cluster] version` (#1543 part C): `image = "registry/node:{version}"`.
    public static final String VERSION_PLACEHOLDER = "{version}";

    /// `image` with [#VERSION_PLACEHOLDER] replaced by the cluster version.
    public Option<String> imageFor(String clusterVersion) {
        return image.map(value -> value.replace(VERSION_PLACEHOLDER, clusterVersion));
    }

    /// `jar_url` with [#VERSION_PLACEHOLDER] replaced by the cluster version.
    public Option<String> jarUrlFor(String clusterVersion) {
        return jarUrl.map(value -> value.replace(VERSION_PLACEHOLDER, clusterVersion));
    }

    /// True when the launch artifact this profile selects is a LITERAL pin, so `[cluster] version` cannot change
    /// what a replacement boots (#1543 part C). A pin carrying [#VERSION_PLACEHOLDER] follows the version.
    public boolean pinsArtifact() {
        return (isContainer()
                ? image
                : jarUrl).filter(value -> !value.contains(VERSION_PLACEHOLDER))
                         .isPresent();
    }
}
