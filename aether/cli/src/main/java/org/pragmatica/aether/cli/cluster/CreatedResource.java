// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

public sealed interface CreatedResource {
    String provider();
    String resourceId();
    String description();

    record ProvisionedVm(String provider, String resourceId, String sourceName, String role) implements CreatedResource {
        static ProvisionedVm provisionedVm(String provider, String resourceId, String sourceName, String role) {
            return new ProvisionedVm(provider, resourceId, sourceName, role);
        }

        @Override public String description() {
            return "VM " + resourceId + " (" + sourceName + "/" + role + ")";
        }
    }

    record FirewallRule(String provider, String resourceId, String sourceName, int port, String protocol) implements CreatedResource {
        static FirewallRule firewallRule(String provider,
                                         String resourceId,
                                         String sourceName,
                                         int port,
                                         String protocol) {
            return new FirewallRule(provider, resourceId, sourceName, port, protocol);
        }

        @Override public String description() {
            return "Firewall rule " + resourceId + " (" + sourceName + " port " + port + "/" + protocol + ")";
        }
    }

    record FloatingIpAssignment(String provider, String floatingIp, String targetNodeId) implements CreatedResource {
        static FloatingIpAssignment floatingIpAssignment(String provider, String floatingIp, String targetNodeId) {
            return new FloatingIpAssignment(provider, floatingIp, targetNodeId);
        }

        @Override public String description() {
            return "Floating IP " + floatingIp + " -> " + targetNodeId;
        }

        @Override public String resourceId() {
            return floatingIp;
        }

        @Override public String provider() {
            return "cloud";
        }
    }

    record DockerContainer(String containerId, String sourceName) implements CreatedResource {
        static DockerContainer dockerContainer(String containerId, String sourceName) {
            return new DockerContainer(containerId, sourceName);
        }

        @Override public String provider() {
            return "docker";
        }

        @Override public String resourceId() {
            return containerId;
        }

        @Override public String description() {
            return "Container " + containerId + " (" + sourceName + ")";
        }
    }

    record SshDeployedConfig(String host, String remotePath) implements CreatedResource {
        static SshDeployedConfig sshDeployedConfig(String host, String remotePath) {
            return new SshDeployedConfig(host, remotePath);
        }

        @Override public String provider() {
            return "ssh";
        }

        @Override public String resourceId() {
            return host + ":" + remotePath;
        }

        @Override public String description() {
            return "Config " + remotePath + " on " + host;
        }
    }
}
