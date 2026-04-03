package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;


/// Errors specific to declarative cluster management operations.
public sealed interface ClusterConfigError extends Cause {
    record InvalidDeploymentType(String value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid deployment type: '" + value + "'. Must be one of: hetzner, aws, gcp, azure, kubernetes, on-premises, embedded";
        }
    }

    record InvalidClusterName(String value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid cluster name: '" + value + "'. Must be non-empty, match [a-z0-9][a-z0-9-]*, max 63 chars";
        }
    }

    record InvalidCoreCount(int value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid core count: " + value + ". Must be an odd number >= 3";
        }
    }

    record InvalidCoreMin(int min, int count) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid core min: " + min + ". Must be an odd number >= 3 and <= core.count (" + count + ")";
        }
    }

    record InvalidCoreMax(int max, int count) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid core max: " + max + ". Must be an odd number >= core.count (" + count + ")";
        }
    }

    record InvalidVersion(String value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid version: '" + value + "'. Must be valid semver X.Y.Z";
        }
    }

    record MissingInstanceType(String role) implements ClusterConfigError {
        @Override public String message() {
            return "Missing instance type for role: '" + role + "'. deployment.instances must have a 'core' entry";
        }
    }

    record InvalidRuntimeType(String value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid runtime type: '" + value + "'. Must be 'container' or 'jvm'";
        }
    }

    record MissingContainerImage() implements ClusterConfigError {
        @Override public String message() {
            return "Missing container image. deployment.runtime.image is required when runtime.type = 'container'";
        }
    }

    record InvalidPort(String name, int value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid port '" + name + "': " + value + ". Must be in range 1-65535";
        }
    }

    record UnmappedZone(String zone) implements ClusterConfigError {
        @Override public String message() {
            return "Unmapped zone: '" + zone + "'. All zones in cluster.distribution.zones must have a mapping in deployment.zones";
        }
    }

    record InvalidDistributionStrategy(String value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid distribution strategy: '" + value + "'. Must be 'balanced' or 'manual'";
        }
    }

    record InvalidRetryInterval(String value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid retry interval: '" + value + "'. Must be a parseable duration >= 5s";
        }
    }

    record InvalidSecretReference(String value) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid secret reference: '" + value + "'. Must start with '${secrets:' or be a literal";
        }
    }

    record ParseFailed(String detail) implements ClusterConfigError {
        @Override public String message() {
            return "Failed to parse cluster config: " + detail;
        }
    }

    record ValidationFailed(java.util.List<ClusterConfigError> errors) implements ClusterConfigError {
        @Override public String message() {
            var sb = new StringBuilder("Cluster config validation failed:\n");
            errors.forEach(e -> sb.append("- ").append(e.message())
                                         .append('\n'));
            return sb.toString();
        }
    }

    record VersionConflict(long expected, long actual) implements ClusterConfigError {
        @Override public String message() {
            return "Config version conflict: expected " + expected + ", actual " + actual;
        }
    }

    record ClusterAlreadyExists(String name) implements ClusterConfigError {
        @Override public String message() {
            return "Cluster '" + name + "' already exists. Use 'apply' to modify or 'destroy' first.";
        }
    }

    record ClusterNotFound(String name) implements ClusterConfigError {
        @Override public String message() {
            return "Cluster '" + name + "' not found in registry.";
        }
    }

    record BootstrapFailed(String phase, int nodesProvisioned, int nodesTotal, String detail) implements ClusterConfigError {
        @Override public String message() {
            return "Bootstrap failed at " + phase + " (" + nodesProvisioned + "/" + nodesTotal + " nodes provisioned): " + detail;
        }
    }

    record QuorumSafetyViolation(int requested, int minimum) implements ClusterConfigError {
        @Override public String message() {
            return "Quorum safety violation: requested " + requested + " nodes, minimum is " + minimum;
        }
    }

    record ImmutableFieldChange(String field) implements ClusterConfigError {
        @Override public String message() {
            return "Field '" + field + "' is immutable after bootstrap. Destroy and re-bootstrap to change.";
        }
    }

    record UpgradeInProgress(String upgradeId) implements ClusterConfigError {
        @Override public String message() {
            return "Upgrade already in progress: " + upgradeId + ". Wait for completion or rollback.";
        }
    }

    record SecretResolutionFailed(String placeholder) implements ClusterConfigError {
        @Override public String message() {
            return "Failed to resolve secret: " + placeholder;
        }
    }

    record CloudCredentialsMissing(String provider, String envVar) implements ClusterConfigError {
        @Override public String message() {
            return provider + " credentials missing. Set environment variable: " + envVar;
        }
    }

    record ProvisionTimeout(String instanceId, long timeoutSeconds) implements ClusterConfigError {
        @Override public String message() {
            return "Instance " + instanceId + " did not become reachable within " + timeoutSeconds + " seconds.";
        }
    }

    record QuorumTimeout(int healthyNodes, int requiredNodes, long timeoutSeconds) implements ClusterConfigError {
        @Override public String message() {
            return "Quorum not established: " + healthyNodes + "/" + requiredNodes + " healthy nodes after " + timeoutSeconds + " seconds.";
        }
    }

    record MissingNodeInventory(String deploymentType) implements ClusterConfigError {
        @Override public String message() {
            return deploymentType + " deployment requires deployment.nodes.core list";
        }
    }

    record NodeCountMismatch(int nodeCount, int coreCount) implements ClusterConfigError {
        @Override public String message() {
            return "deployment.nodes.core has " + nodeCount + " entries but cluster.core.count is " + coreCount;
        }
    }

    record MissingSshConfig(String deploymentType) implements ClusterConfigError {
        @Override public String message() {
            return deploymentType + " deployment requires deployment.ssh section";
        }
    }

    record MissingSshKeyPath() implements ClusterConfigError {
        @Override public String message() {
            return "deployment.ssh.key_path must be specified";
        }
    }

    record unused() implements ClusterConfigError {
        @Override public String message() {
            return "";
        }
    }
}
