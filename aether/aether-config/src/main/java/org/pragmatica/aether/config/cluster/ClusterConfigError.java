// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


public sealed interface ClusterConfigError extends Cause, HttpStatusAware {
    /// Default HTTP status for config errors is 400 (Bad Request) — most variants are
    /// input validation failures. Variants with state-conflict, not-found, server-side,
    /// or timeout semantics override.
    @Override
    default HttpStatus httpStatus() {
        return HttpStatus.BAD_REQUEST;
    }

    record InvalidDeploymentType(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid deployment type: '" + value
                 + "'. Must be one of: hetzner, aws, gcp, azure, kubernetes, on-premises, embedded";
        }
    }

    record InvalidClusterName(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid cluster name: '" + value
                 + "'. Must be non-empty, match [a-z]([a-z0-9-]{0,61}[a-z0-9])?, 1-63 chars";
        }
    }

    record InvalidCoreCount(int value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid core count: " + value + ". Must be an odd number >= 3";
        }
    }

    record InvalidCoreMin(int min, int count) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid core min: " + min + ". Must be an odd number >= 3 and <= core.count (" + count + ")";
        }
    }

    record InvalidCoreMax(int max, int count) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid core max: " + max + ". Must be an odd number >= core.count (" + count + ")";
        }
    }

    record InvalidVersion(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid version: '" + value + "'. Must be valid semver X.Y.Z";
        }
    }

    record MissingInstanceType(String role) implements ClusterConfigError {
        @Override
        public String message() {
            return "Missing instance type for role: '" + role + "'. deployment.instances must have a 'core' entry";
        }
    }

    record InvalidRuntimeType(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid runtime type: '" + value + "'. Must be 'container' or 'jvm'";
        }
    }

    enum MissingContainerImage implements ClusterConfigError {
        INSTANCE;
        @Override
        public String message() {
            return "Missing container image. deployment.runtime.image is required when runtime.type = 'container'";
        }
    }

    record InvalidPort(String name, int value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid port '" + name + "': " + value + ". Must be in range 1-65535";
        }
    }

    record UnmappedZone(String zone) implements ClusterConfigError {
        @Override
        public String message() {
            return "Unmapped zone: '" + zone
                 + "'. All zones in cluster.distribution.zones must have a mapping in deployment.zones";
        }
    }

    record InvalidDistributionStrategy(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid distribution strategy: '" + value + "'. Must be 'balanced' or 'manual'";
        }
    }

    record InvalidRetryInterval(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid retry interval: '" + value + "'. Must be a parseable duration >= 5s";
        }
    }

    record InvalidSecretReference(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid secret reference: '" + value + "'. Must start with '${secrets:' or be a literal";
        }
    }

    record ParseFailed(String detail) implements ClusterConfigError {
        @Override
        public String message() {
            return "Failed to parse cluster config: " + detail;
        }
    }

    record ValidationFailed(List<ClusterConfigError> errors) implements ClusterConfigError {
        @Override
        public String message() {
            var sb = new StringBuilder("Cluster config validation failed:\n");

            errors.forEach(e -> sb.append("- ")
                                  .append(e.message())
                                  .append('\n'));

            return sb.toString();
        }
    }

    record VersionConflict(long expected, long actual) implements ClusterConfigError {
        @Override
        public String message() {
            return "Config version conflict: expected " + expected + ", actual " + actual;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// #289: a config push carrying `expectedVersion=0` (the "I expect a fresh cluster" sentinel) was
    /// rejected because a populated config already exists. Without this fence an `expectedVersion=0`
    /// push skips the CAS check and blind-overwrites mutable cluster config — e.g. a re-run of
    /// `aether cluster bootstrap` against a live cluster, or two concurrent bootstraps. The caller must
    /// read the current `configVersion` and resubmit with it (proper optimistic concurrency).
    record UnfencedOverwrite(long actual) implements ClusterConfigError {
        @Override
        public String message() {
            return "Refusing to overwrite existing cluster config (version " + actual
                 + ") with an unfenced push (expectedVersion=0). Read the current configVersion and "
                 + "resubmit with it, or destroy the cluster first.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record ClusterAlreadyExists(String name) implements ClusterConfigError {
        @Override
        public String message() {
            return "Cluster '" + name + "' already exists. Use 'apply' to modify or 'destroy' first.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record ClusterNotFound(String name) implements ClusterConfigError {
        @Override
        public String message() {
            return "Cluster '" + name + "' not found in registry.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_FOUND;
        }
    }

    record BootstrapFailed(String phase, int nodesProvisioned, int nodesTotal, String detail) implements ClusterConfigError {
        @Override
        public String message() {
            return "Bootstrap failed at " + phase
                 + " (" + nodesProvisioned
                 + "/" + nodesTotal
                 + " nodes provisioned): " + detail;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    record QuorumSafetyViolation(int requested, int minimum) implements ClusterConfigError {
        @Override
        public String message() {
            return "Quorum safety violation: requested " + requested + " nodes, minimum is " + minimum;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// A scale request named a role but not a source, and several sources declare that role.
    ///
    /// Refusing is the point: "scale cores to 5" across two core-bearing sources does not say which
    /// one absorbs the change, and the former cluster-wide core count answered it by silently
    /// rewriting one number.
    record AmbiguousScaleSource(String role, List<String> sources) implements ClusterConfigError {
        @Override
        public String message() {
            return "Role '" + role
                 + "' is declared by " + sources.size()
                 + " sources (" + String.join(", ", sources)
                 + "). Re-run naming one with --source.";
        }
    }

    /// A scale request named a (source, role) the topology does not declare.
    ///
    /// Adding the pair instead would turn a mistyped source name into a real provisioning target.
    ///
    /// The component is `sourceName`, not `source`: [Cause] already declares `source()` returning
    /// `Option<Cause>`, so a record component named `source` fails to compile.
    record UnknownScaleTarget(String sourceName, String role, List<String> known) implements ClusterConfigError {
        @Override
        public String message() {
            return "Cluster topology declares no '" + role
                 + "' nodes in source '" + sourceName
                 + "'. Declared targets: " + (known.isEmpty()
                                              ? "(none)"
                                              : String.join(", ", known))
                 + ". Change the topology with 'aether cluster apply', not with a scale.";
        }
    }

    /// #335: `POST /api/cluster/scale` against a cluster with no stored config (e.g. after a
    /// `docker compose down -v` volume wipe leaves a fresh, unconfigured cluster) cannot bootstrap
    /// one from the scale request alone. Same class as the #290 formation-bootstrap work, but that
    /// path derives the cluster name, semver version, distribution strategy, zones, and deployment
    /// settings a `ClusterConfigValue` requires from a full TOML document — a `ScaleRequest` carries
    /// only source/role/count/expectedVersion, none of that. Guessing the rest would fabricate a
    /// cluster identity nobody declared, so this names the actual recovery instead of 500ing or
    /// inventing defaults.
    record NoConfigToScale(String scaleSource, String role, int count) implements ClusterConfigError {
        @Override
        public String message() {
            var sourceFlag = scaleSource.isBlank()
                             ? ""
                             : "--source " + scaleSource + " ";

            return "No cluster configuration stored. A scale request cannot create one — it carries "
                 + "only source/role/count, not the cluster name, version, or deployment settings a "
                 + "config requires. Run 'aether cluster bootstrap <aether-cluster.toml>' first, then "
                 + "retry 'aether cluster scale " + sourceFlag
                 + "--role " + role
                 + " --count " + count
                 + "'.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// #837: `POST /api/cluster/upgrade` against a cluster with no stored config (same
    /// post-volume-wipe state #335/#835 fixed for scale) used to route through
    /// `ClusterConfigRoutes.lookupClusterConfig()`, which folded absence into the bare,
    /// statusless `ConfigNotFoundError` — a 500 that read like server failure. An
    /// `UpgradeRequest` carries only a target version: none of the cluster name, distribution
    /// strategy, zones, or deployment settings a `ClusterConfigValue` requires, so there is no
    /// more an honest bootstrap-on-upgrade here than there was a bootstrap-on-scale. Same fix
    /// shape as `NoConfigToScale`: name the actual recovery instead of guessing or 500ing.
    record NoConfigToUpgrade(String targetVersion) implements ClusterConfigError {
        @Override
        public String message() {
            return "No cluster configuration stored. An upgrade request cannot create one — it "
                 + "carries only a target version, not the cluster name, topology, or deployment "
                 + "settings a config requires. Run 'aether cluster bootstrap <aether-cluster.toml>' "
                 + "first, then retry 'aether cluster upgrade --version " + targetVersion + "'.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record ImmutableFieldChange(String field) implements ClusterConfigError {
        @Override
        public String message() {
            return "Field '" + field + "' is immutable after bootstrap. Destroy and re-bootstrap to change.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record UpgradeInProgress(String upgradeId) implements ClusterConfigError {
        @Override
        public String message() {
            return "Upgrade already in progress: " + upgradeId + ". Wait for completion or rollback.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record SecretResolutionFailed(String placeholder) implements ClusterConfigError {
        @Override
        public String message() {
            return "Failed to resolve secret: " + placeholder;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    record CloudCredentialsMissing(String provider, String envVar) implements ClusterConfigError {
        @Override
        public String message() {
            return provider + " credentials missing. Set environment variable: " + envVar;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    record ProvisionTimeout(String instanceId, long timeoutSeconds) implements ClusterConfigError {
        @Override
        public String message() {
            return "Instance " + instanceId + " did not become reachable within " + timeoutSeconds + " seconds.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
    }

    record QuorumTimeout(int healthyNodes, int requiredNodes, long timeoutSeconds) implements ClusterConfigError {
        @Override
        public String message() {
            return "Quorum not established: " + healthyNodes
                 + "/" + requiredNodes
                 + " healthy nodes after " + timeoutSeconds
                 + " seconds.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
    }

    record MissingNodeInventory(String deploymentType) implements ClusterConfigError {
        @Override
        public String message() {
            return deploymentType + " deployment requires deployment.nodes.core list";
        }
    }

    record NodeCountMismatch(int nodeCount, int coreCount) implements ClusterConfigError {
        @Override
        public String message() {
            return "deployment.nodes.core has " + nodeCount + " entries but cluster.core.count is " + coreCount;
        }
    }

    record MissingSshConfig(String deploymentType) implements ClusterConfigError {
        @Override
        public String message() {
            return deploymentType + " deployment requires deployment.ssh section";
        }
    }

    enum MissingSshKeyPath implements ClusterConfigError {
        INSTANCE;
        @Override
        public String message() {
            return "deployment.ssh.key_path must be specified";
        }
    }

    record InvalidImageName(String value) implements ClusterConfigError {
        @Override
        public String message() {
            return "Invalid container image name: '" + value
                 + "'. Must match [a-zA-Z0-9][a-zA-Z0-9._/-]*:[a-zA-Z0-9._-]+";
        }
    }

    /// #578: `ClusterConfigApplier` only actuates [DiffAction.ScaleUp]/[DiffAction.ScaleDown] — the
    /// other 8 `DiffAction` variants (source/role add-remove, runtime change, field changes) fell
    /// through a catch-all `default` that logged and returned success, so a config push naming one of
    /// them silently no-op'd while the response claimed the apply worked. 501 (not 400) because the
    /// request itself is well-formed and the diff plan is valid — the server just doesn't implement
    /// this action kind on the `POST /api/cluster/config` path.
    ///
    /// The message deliberately does NOT tell the operator to destroy and re-bootstrap: unlike
    /// `ImmutableFieldChange`, these actions are not actually unsupportable — `aether cluster apply
    /// --resume`/`--rollback` route through `WaveExecutor`, which DOES provision/destroy/roll these
    /// kinds live. The plain `aether cluster apply <file>` invocation just never reaches that engine;
    /// it POSTs straight to this endpoint instead. That dispatch gap is a separate, structural finding
    /// (flagged for its own fix, not asserted here as a working workaround) — this cause only
    /// guarantees the one thing this layer can promise: the operation did not silently happen. It
    /// carries the [DiffAction] itself, not a pre-rendered symbol/description pair, so a caller can
    /// inspect which action kind failed rather than string-match the message.
    record UnsupportedApplyAction(DiffAction action) implements ClusterConfigError {
        @Override
        public String message() {
            return "Config action not supported for live apply on POST /api/cluster/config in this release: " + action.symbol()
                 + " " + action.description()
                 + ". No verified recovery is available through this endpoint yet — escalate rather than retry.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_IMPLEMENTED;
        }
    }

    /// #578 review: `ClusterConfigApplier.NoTopologyManager` is `ManagementServer`'s fallback wiring for a
    /// node whose `clusterTopologyManager()` returns `Option.none()` — today that never happens
    /// (`AetherNode` always returns `Option.some(...)`), so this path is currently dead, not live.
    /// It still shipped as a silent-success stub — the identical shape of defect #578 fixes on the
    /// live path — so a future conditional `clusterTopologyManager()` would reintroduce #578 through
    /// this fallback with no test catching it. 503 (not 501): this is about THIS node's readiness,
    /// not about the action kind being unimplemented — retrying the identical request against a node
    /// that has a topology manager wired is the honest recovery.
    enum ClusterTopologyManagerUnavailable implements ClusterConfigError {
        INSTANCE;
        @Override
        public String message() {
            return "This node has no cluster topology manager wired — it cannot apply cluster config "
                 + "changes. Retry against a node where cluster topology management is active.";
        }
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    record unused() implements ClusterConfigError {
        @Override
        public String message() {
            return "";
        }
    }
}
