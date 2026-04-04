package org.pragmatica.aether.deployment.migration;


/// An individual step within a migration plan.
/// Steps are executed sequentially; each must succeed before the next begins.
public sealed interface MigrationStep {
    String description();

    /// Provision a new node in the target environment.
    record ProvisionTarget(String targetProvider, String targetZone, int index) implements MigrationStep {
        @Override public String description() {
            return "Provision node " + index + " in " + targetProvider + "/" + targetZone;
        }
    }

    /// Wait for a newly provisioned node to join the cluster and synchronize state.
    record WaitForSync(int index) implements MigrationStep {
        @Override public String description() {
            return "Wait for node " + index + " to synchronize";
        }
    }

    /// Drain a source node, evacuating slices to remaining nodes.
    record DrainSource(String nodeId) implements MigrationStep {
        @Override public String description() {
            return "Drain source node " + nodeId;
        }
    }

    /// Update DNS records to point to the new target nodes.
    record UpdateDns(String hostname, java.util.List<String> targetAddresses) implements MigrationStep {
        public UpdateDns {
            targetAddresses = java.util.List.copyOf(targetAddresses);
        }

        @Override public String description() {
            return "Update DNS " + hostname + " -> " + targetAddresses;
        }
    }

    /// Terminate a source node after successful migration.
    record TerminateSource(String nodeId) implements MigrationStep {
        @Override public String description() {
            return "Terminate source node " + nodeId;
        }
    }
}
