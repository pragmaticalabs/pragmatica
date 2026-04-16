// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.migration;

public sealed interface MigrationStep {
    String description();

    static ProvisionTarget provisionTarget(String targetProvider, String targetZone, int index) {
        return new ProvisionTarget(targetProvider, targetZone, index);
    }

    static WaitForSync waitForSync(int index) {
        return new WaitForSync(index);
    }

    static DrainSource drainSource(String nodeId) {
        return new DrainSource(nodeId);
    }

    static UpdateDns updateDns(String hostname, java.util.List<String> targetAddresses) {
        return new UpdateDns(hostname, targetAddresses);
    }

    static TerminateSource terminateSource(String nodeId) {
        return new TerminateSource(nodeId);
    }

    record ProvisionTarget(String targetProvider, String targetZone, int index) implements MigrationStep {
        @Override public String description() {
            return "Provision node " + index + " in " + targetProvider + "/" + targetZone;
        }
    }

    record WaitForSync(int index) implements MigrationStep {
        @Override public String description() {
            return "Wait for node " + index + " to synchronize";
        }
    }

    record DrainSource(String nodeId) implements MigrationStep {
        @Override public String description() {
            return "Drain source node " + nodeId;
        }
    }

    record UpdateDns(String hostname, java.util.List<String> targetAddresses) implements MigrationStep {
        public UpdateDns {
            targetAddresses = java.util.List.copyOf(targetAddresses);
        }

        @Override public String description() {
            return "Update DNS " + hostname + " -> " + targetAddresses;
        }
    }

    record TerminateSource(String nodeId) implements MigrationStep {
        @Override public String description() {
            return "Terminate source node " + nodeId;
        }
    }
}
