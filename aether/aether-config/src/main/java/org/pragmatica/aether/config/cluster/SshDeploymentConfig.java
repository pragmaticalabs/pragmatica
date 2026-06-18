// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;


/// Operator SSH deployment material from `[infrastructure.ssh]`.
///
///  - [#publicKeyFiles] — operator-local PATHS to public-key files, resolved by the CLI bootstrap
///    host (`SshKeyResolver`). The leader VM cannot read these paths, so they are NOT consumed by
///    the CTM auto-heal replacement path.
///  - [#authorizedKeys] — resolved public-key CONTENTS (one `<algo> <blob> [comment]` line each).
///    Persisted into the cluster config TOML at bootstrap formation so the CTM auto-heal path (which
///    re-parses that persisted TOML on the leader VM) can inject them into the replacement node's
///    cloud-init `authorized_keys` — giving a replacement VM the SAME operator SSH access a
///    bootstrap-minted node receives, by the SAME user-data mechanism.
public record SshDeploymentConfig(List<String> publicKeyFiles, List<String> authorizedKeys) {
    public SshDeploymentConfig {
        publicKeyFiles = List.copyOf(publicKeyFiles);
        authorizedKeys = List.copyOf(authorizedKeys);
    }

    public static SshDeploymentConfig sshDeploymentConfig(List<String> publicKeyFiles) {
        return new SshDeploymentConfig(publicKeyFiles, List.of());
    }

    public static SshDeploymentConfig sshDeploymentConfig(List<String> publicKeyFiles, List<String> authorizedKeys) {
        return new SshDeploymentConfig(publicKeyFiles, authorizedKeys);
    }

    public static SshDeploymentConfig empty() {
        return new SshDeploymentConfig(List.of(), List.of());
    }
}
