// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Composes the `aether.toml` a CTM auto-heal replacement node runs with, from the persisted
/// cluster config (`ClusterConfigValue.tomlContent()`) re-parsed into a [ClusterBootstrapConfig].
/// Reuses the SAME composition pipeline as the CLI bootstrap path
/// (`DefaultNodeConfig` global + source-type defaults, the source's `nodeConfig` operator
/// override, and the [BootstrapOverlayGenerator] overlay) so a replacement's config is
/// byte-identical to a seed's for the same source and cannot drift.
///
/// The replacement composes WITHOUT a docker `api_key` / `docker_gid` overlay: those are
/// node-identity values delivered to the replacement via the cluster-identity env allow-list
/// ([NodeUserDataRenderer#emitIdentityEnv] → `AETHER_API_KEY` / `DOCKER_GID`), not the static
/// composed TOML.
public sealed interface ReplacementNodeConfigComposer {
    record unused() implements ReplacementNodeConfigComposer {}

    /// Compose the replacement node config for the cloud source backing the given role. The
    /// `clusterSecret` is emitted into the cloud `tls.cluster_secret` section (when present) the
    /// same way the bootstrap path does.
    static Result<TomlDocument> compose(ClusterBootstrapConfig config,
                                        SourceProfile source,
                                        Option<String> clusterSecret) {
        var overlay = BootstrapOverlayGenerator.overlay(config,
                                                        source,
                                                        0,
                                                        Option.empty(),
                                                        Option.empty(),
                                                        clusterSecret);

        return Result.all(DefaultNodeConfig.globalDefault(),
                          DefaultNodeConfig.sourceTypeDefault(source.type()))
                     .map((global, typeDefault) -> NodeConfigComposer.compose(global,
                                                                              typeDefault,
                                                                              source.nodeConfig(),
                                                                              overlay));
    }
}
