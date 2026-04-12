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
