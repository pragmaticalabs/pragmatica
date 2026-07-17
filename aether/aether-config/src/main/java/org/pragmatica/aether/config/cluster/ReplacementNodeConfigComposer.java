// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

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
    /// same way the bootstrap path does. Defaults the node role to [NodeRole#CORE] (the historical
    /// core-only behavior); callers auto-healing a specific role use the role-aware overload so the
    /// overlay's tier-2 `[cloud.compute] image` reflects THAT role.
    static Result<TomlDocument> compose(ClusterBootstrapConfig config,
                                        SourceProfile source,
                                        Option<String> clusterSecret) {
        return compose(config, source, NodeRole.CORE, clusterSecret, List.of());
    }

    /// #442 — `sshKeyIds` are the operator SSH key ids the LEADER itself was provisioned with (read
    /// from its own resolved `[cloud.compute] ssh_key_ids`). Threaded into the replacement's overlay
    /// so a replacement that later becomes leader inherits the keys and provisions ITS replacements
    /// from config — extending the bootstrap-seeded inheritance across replacement generations
    /// without persisting the ids in the KV cluster config. Empty degrades to the prior behavior
    /// (no `ssh_key_ids`; the provider's name-prefix fallback then applies).
    ///
    /// RFC-0016 W2 — `role` is the replacement's intended role (from the CTM's `intendedRole`).
    /// Threaded into the overlay so tier-2 `[cloud.compute] image` carries the node's OWN role image:
    /// a WORKER/SPOT replacement re-provisions from its role's snapshot, never the core's.
    static Result<TomlDocument> compose(ClusterBootstrapConfig config,
                                        SourceProfile source,
                                        NodeRole role,
                                        Option<String> clusterSecret,
                                        List<Long> sshKeyIds) {
        var overlay = BootstrapOverlayGenerator.overlay(config,
                                                        source,
                                                        0,
                                                        Option.empty(),
                                                        Option.empty(),
                                                        clusterSecret,
                                                        role,
                                                        sshKeyIds);

        return Result.all(DefaultNodeConfig.globalDefault(),
                          DefaultNodeConfig.sourceTypeDefault(source.type()))
                     .map((global, typeDefault) -> NodeConfigComposer.compose(global,
                                                                              typeDefault,
                                                                              source.nodeConfig(),
                                                                              overlay));
    }
}
