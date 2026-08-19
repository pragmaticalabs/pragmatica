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

        @Override
        public String description() {
            return "VM " + resourceId + " (" + sourceName + "/" + role + ")";
        }
    }

    /// A standalone cloud firewall created by [ComputeProvider#openIngress] for one source
    /// (cluster-bootstrap-spec §6.2). ONE resource per source carrying ALL of that source's rules —
    /// a `"tcp+udp"` entry is two rules on this one firewall, not two firewalls — so destroy issues
    /// exactly one delete.
    /// `firewallId` is the PROVIDER'S OWN id, kept as an opaque String. It was a `long`, which is a
    /// Hetzner-shaped assumption: an AWS security group is `sg-0abc…`, an Azure NSG is an ARM resource
    /// path, and a GCP rule is addressed by name. Numeric ids are a special case of string ids, so
    /// widening loses nothing and lets every provider record what it actually created. Providers whose
    /// API needs a number parse it back at their own edge — [BootstrapCleanup] does exactly that for
    /// Hetzner, and REFUSES rather than guessing if the recorded id is not numeric.
    ///
    /// **Stored-format note.** `bootstrap-state.json` is written by hand in [BootstrapStateJson], not by
    /// Jackson databind. Reading uses `JsonNode.asText()`, which yields `"12345"` for a legacy unquoted
    /// number and the value itself for a new quoted string — so existing state files load unchanged.
    /// Writing now emits a QUOTED value, which a pre-widening binary would read as `0` via `asLong()`.
    /// The widening is therefore forward-compatible but not backward-compatible: downgrading the CLI
    /// after a bootstrap would strand the firewall rather than delete it.
    record CloudFirewall(String provider, String firewallId, String sourceName, String name) implements CreatedResource {
        static CloudFirewall cloudFirewall(String provider, String firewallId, String sourceName, String name) {
            return new CloudFirewall(provider, firewallId, sourceName, name);
        }

        @Override
        public String resourceId() {
            return firewallId;
        }

        @Override
        public String description() {
            return "Firewall " + name + " (id=" + firewallId + ", source=" + sourceName + ")";
        }
    }

    record FloatingIpAssignment(String provider, String floatingIp, String targetNodeId) implements CreatedResource {
        static FloatingIpAssignment floatingIpAssignment(String provider, String floatingIp, String targetNodeId) {
            return new FloatingIpAssignment(provider, floatingIp, targetNodeId);
        }

        @Override
        public String description() {
            return "Floating IP " + floatingIp + " -> " + targetNodeId;
        }

        @Override
        public String resourceId() {
            return floatingIp;
        }

        @Override
        public String provider() {
            return "cloud";
        }
    }

    record DockerContainer(String containerId, String sourceName) implements CreatedResource {
        static DockerContainer dockerContainer(String containerId, String sourceName) {
            return new DockerContainer(containerId, sourceName);
        }

        @Override
        public String provider() {
            return "docker";
        }

        @Override
        public String resourceId() {
            return containerId;
        }

        @Override
        public String description() {
            return "Container " + containerId + " (" + sourceName + ")";
        }
    }

    record SshKeyResource(String provider, long sshKeyId, String name) implements CreatedResource {
        public static SshKeyResource sshKeyResource(String provider, long sshKeyId, String name) {
            return new SshKeyResource(provider, sshKeyId, name);
        }

        @Override
        public String resourceId() {
            return Long.toString(sshKeyId);
        }

        @Override
        public String description() {
            return "SSH key " + sshKeyId + " (" + name + ", provider=" + provider + ")";
        }
    }

    record SshDeployedConfig(String host, String remotePath) implements CreatedResource {
        static SshDeployedConfig sshDeployedConfig(String host, String remotePath) {
            return new SshDeployedConfig(host, remotePath);
        }

        @Override
        public String provider() {
            return "ssh";
        }

        @Override
        public String resourceId() {
            return host + ":" + remotePath;
        }

        @Override
        public String description() {
            return "Config " + remotePath + " on " + host;
        }
    }
}
