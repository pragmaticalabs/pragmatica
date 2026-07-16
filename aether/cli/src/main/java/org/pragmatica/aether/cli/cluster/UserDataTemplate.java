// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;


/// CLI bootstrap wrapper over the shared [NodeUserDataRenderer] (in `aether-config`). Adapts the
/// CLI's [SshPublicKey] value object to the renderer's raw-`String` key contract and delegates
/// every rendering decision to the shared renderer so the bootstrap user-data and the CTM
/// auto-heal replacement user-data are produced by ONE renderer and cannot drift.
sealed interface UserDataTemplate {
    record unused() implements UserDataTemplate {}

    static String deriveJarTag(String version) {
        return NodeUserDataRenderer.deriveJarTag(version);
    }

    static String resolveJarUrl(Option<RuntimeProfile> profile, String version) {
        return NodeUserDataRenderer.resolveJarUrl(profile, version);
    }

    static String render(ClusterBootstrapConfig config,
                         SourceProfile source,
                         NodeRole role,
                         String nodeId,
                         int nodeIndex,
                         String clusterSecret,
                         String clusterName,
                         TomlDocument composedConfig) {
        return NodeUserDataRenderer.render(config,
                                           source,
                                           role,
                                           nodeId,
                                           nodeIndex,
                                           clusterSecret,
                                           clusterName,
                                           composedConfig);
    }

    static String render(ClusterBootstrapConfig config,
                         SourceProfile source,
                         NodeRole role,
                         String nodeId,
                         int nodeIndex,
                         String clusterSecret,
                         String clusterName,
                         TomlDocument composedConfig,
                         List<SshPublicKey> sshPublicKeys) {
        return render(config,
                      source,
                      role,
                      nodeId,
                      nodeIndex,
                      clusterSecret,
                      clusterName,
                      composedConfig,
                      sshPublicKeys,
                      List.of());
    }

    static String render(ClusterBootstrapConfig config,
                         SourceProfile source,
                         NodeRole role,
                         String nodeId,
                         int nodeIndex,
                         String clusterSecret,
                         String clusterName,
                         TomlDocument composedConfig,
                         List<SshPublicKey> sshPublicKeys,
                         List<String> peers) {
        return NodeUserDataRenderer.render(config,
                                           source,
                                           role,
                                           nodeId,
                                           nodeIndex,
                                           clusterSecret,
                                           clusterName,
                                           composedConfig,
                                           sshPublicKeys.stream().map(SshPublicKey::value).toList(),
                                           peers);
    }

    @Contract
    static void emitIdentityEnv(Fn2<Unit, String, String> emit,
                                String clusterName,
                                NodeRole role,
                                Option<String> clusterSecretRef,
                                Fn1<String, String> envLookup) {
        NodeUserDataRenderer.emitIdentityEnv(emit, clusterName, role, clusterSecretRef, envLookup);
    }
}
