// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public sealed interface EnvironmentError extends Cause {
    record ProvisionFailed(Throwable cause) implements EnvironmentError {
        public static Result<ProvisionFailed> provisionFailed(Throwable cause) {
            return success(new ProvisionFailed(cause));
        }

        @Override
        public String message() {
            return "Node provisioning failed: " + cause.getMessage();
        }
    }

    record TerminateFailed(InstanceId instanceId, Throwable cause) implements EnvironmentError {
        public static Result<TerminateFailed> terminateFailed(InstanceId instanceId, Throwable cause) {
            return success(new TerminateFailed(instanceId, cause));
        }

        @Override
        public String message() {
            return "Instance termination failed for '" + instanceId.value() + "': " + cause.getMessage();
        }
    }

    record InstanceNotFound(InstanceId instanceId) implements EnvironmentError {
        public static Result<InstanceNotFound> instanceNotFound(InstanceId instanceId) {
            return success(new InstanceNotFound(instanceId));
        }

        @Override
        public String message() {
            return "Instance not found: " + instanceId.value();
        }
    }

    /// Raised when a freshly-created instance fails to reach [InstanceStatus#RUNNING]
    /// within the provisioning-readiness window. The instance was created (or attempted)
    /// but never confirmed live — surfacing this FAILURE (instead of a phantom success)
    /// lets the caller (CTM) free the slot and avoids minting a node that poisons quorum.
    /// `lastStatus` records the terminal status observed (e.g. `Provisioning` on timeout,
    /// or `Terminated` when the container/VM exited during boot).
    record ProvisionReadinessTimeout(InstanceId instanceId, InstanceStatus lastStatus, long timeoutMillis) implements EnvironmentError {
        public static Result<ProvisionReadinessTimeout> provisionReadinessTimeout(InstanceId instanceId,
                                                                                  InstanceStatus lastStatus,
                                                                                  long timeoutMillis) {
            return success(new ProvisionReadinessTimeout(instanceId, lastStatus, timeoutMillis));
        }

        @Override
        public String message() {
            return "Instance '" + instanceId.value()
                 + "' did not reach RUNNING within " + timeoutMillis
                 + "ms (last observed status: " + lastStatus
                 + "); refusing to report a phantom provision success.";
        }
    }

    /// Raised when a cloud provider rejects a provision because the target zone has no
    /// capacity (e.g. Hetzner returns 412 `resource_unavailable` "error during placement").
    /// Distinct from [ProvisionFailed] because it is RETRYABLE in a different zone — the
    /// bootstrap rotates to the next configured zone instead of aborting the whole cluster.
    /// `zone` records the location that was attempted (empty when the provider could not
    /// surface it).
    record CapacityUnavailable(String zone, Throwable cause) implements EnvironmentError {
        public static Result<CapacityUnavailable> capacityUnavailable(String zone, Throwable cause) {
            return success(new CapacityUnavailable(zone, cause));
        }

        @Override
        public String message() {
            return "Capacity unavailable in zone '" + zone + "': " + cause.getMessage();
        }
    }

    record ListInstancesFailed(Throwable cause) implements EnvironmentError {
        public static Result<ListInstancesFailed> listInstancesFailed(Throwable cause) {
            return success(new ListInstancesFailed(cause));
        }

        @Override
        public String message() {
            return "Failed to list instances: " + cause.getMessage();
        }
    }

    record SecretResolutionFailed(String path, Throwable cause) implements EnvironmentError {
        public static Result<SecretResolutionFailed> secretResolutionFailed(String path, Throwable cause) {
            return success(new SecretResolutionFailed(path, cause));
        }

        @Override
        public String message() {
            return "Secret resolution failed for '" + path + "': " + cause.getMessage();
        }
    }

    record DiscoveryFailed(String detail, Throwable cause) implements EnvironmentError {
        public static Result<DiscoveryFailed> discoveryFailed(String detail, Throwable cause) {
            return success(new DiscoveryFailed(detail, cause));
        }

        @Override
        public String message() {
            return "Discovery failed: " + detail + " — " + cause.getMessage();
        }
    }

    record OperationNotSupported(String operation) implements EnvironmentError {
        public static Result<OperationNotSupported> operationNotSupported(String operation) {
            return success(new OperationNotSupported(operation));
        }

        @Override
        public String message() {
            return "Operation not supported: " + operation;
        }
    }

    record CredentialsMissing(String provider, java.util.List<String> missingEnvVars) implements EnvironmentError {
        public static Result<CredentialsMissing> credentialsMissing(String provider,
                                                                    java.util.List<String> missingEnvVars) {
            return success(new CredentialsMissing(provider, java.util.List.copyOf(missingEnvVars)));
        }

        @Override
        public String message() {
            return "Cloud credentials missing for provider '" + provider + "': set " + String.join(", ", missingEnvVars);
        }
    }

    /// #298 — the operator-set fleet cap refused a provision. Raised at the single provisioning
    /// chokepoint ([NodeLifecycleManager#provisionNode]) BEFORE any provider call, so a runaway
    /// reconciler or a bad config cannot mint an unbounded fleet.
    ///
    /// Bound honesty: the check is check-then-act against a live provider count, so N provisions
    /// racing the same cap can each observe `observed < cap` and all proceed. The guarantee is
    /// therefore "fleet is bounded by `cap` plus whatever was concurrently in flight", NOT "the
    /// fleet never exceeds `cap`". It bounds the runaway case (which is sequential reconciler
    /// passes), not a deliberate parallel burst.
    ///
    /// Operator recovery: raise `max_nodes` for the source, or terminate instances until the
    /// cluster is back under the cap. Provisioning resumes on the next reconcile pass with no
    /// further action.
    record NodeCapExceeded(String clusterName, int cap, int observed) implements EnvironmentError {
        public static Result<NodeCapExceeded> nodeCapExceeded(String clusterName, int cap, int observed) {
            return success(new NodeCapExceeded(clusterName, cap, observed));
        }

        @Override
        public String message() {
            return "Provisioning refused for cluster '" + clusterName
                 + "': node cap " + cap
                 + " reached (" + observed
                 + " already provisioned). Raise max_nodes for the source "
                 + "or terminate instances to go below the cap.";
        }
    }

    record unused() implements EnvironmentError {
        public static Result<unused > unused() {
            return success(new unused());
        }

        @Override
        public String message() {
            return "";
        }
    }

    static EnvironmentError provisionFailed(Throwable cause) {
        return ProvisionFailed.provisionFailed(cause).unwrap();
    }

    static EnvironmentError capacityUnavailable(String zone, Throwable cause) {
        return CapacityUnavailable.capacityUnavailable(zone, cause).unwrap();
    }

    static EnvironmentError terminateFailed(InstanceId instanceId, Throwable cause) {
        return TerminateFailed.terminateFailed(instanceId, cause).unwrap();
    }

    static EnvironmentError instanceNotFound(InstanceId instanceId) {
        return InstanceNotFound.instanceNotFound(instanceId).unwrap();
    }

    static EnvironmentError provisionReadinessTimeout(InstanceId instanceId,
                                                      InstanceStatus lastStatus,
                                                      long timeoutMillis) {
        return ProvisionReadinessTimeout.provisionReadinessTimeout(instanceId, lastStatus, timeoutMillis).unwrap();
    }

    static EnvironmentError listInstancesFailed(Throwable cause) {
        return ListInstancesFailed.listInstancesFailed(cause).unwrap();
    }

    static EnvironmentError secretResolutionFailed(String path, Throwable cause) {
        return SecretResolutionFailed.secretResolutionFailed(path, cause).unwrap();
    }

    static EnvironmentError discoveryFailed(String detail, Throwable cause) {
        return DiscoveryFailed.discoveryFailed(detail, cause).unwrap();
    }

    static EnvironmentError operationNotSupported(String operation) {
        return OperationNotSupported.operationNotSupported(operation).unwrap();
    }

    static EnvironmentError nodeCapExceeded(String clusterName, int cap, int observed) {
        return NodeCapExceeded.nodeCapExceeded(clusterName, cap, observed).unwrap();
    }
}
