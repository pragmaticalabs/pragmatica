// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.environment.CloudProvider;


/// Strategy for provisioning nodes per source type. Section 8 Phase 2.
/// Each variant holds the provider reference needed for its source type.
/// Actual provisioning logic will be added in the bootstrap orchestrator (Phase B).
public sealed interface SourceProvisioner {
    record CloudSourceProvisioner(CloudProvider cloudProvider) implements SourceProvisioner{}

    record SshSourceProvisioner() implements SourceProvisioner{}

    record ForgeSourceProvisioner() implements SourceProvisioner{}

    record DockerSourceProvisioner(CloudProvider dockerCloudProvider) implements SourceProvisioner{}
}
