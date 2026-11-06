// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Result.success;


public sealed interface EnvironmentError extends Cause {
    /// A provider refused (or failed) to create a node.
    ///
    /// `instanceType` and `zone` carry WHAT WAS ASKED FOR, because the provider's own complaint
    /// routinely does not. Measured against Hetzner on 2026-09-10: three bootstrap runs in two
    /// regions died with the verbatim body `422 (invalid_input): unsupported location for server
    /// type` — which names neither the server type nor the location, so an operator cannot tell
    /// which of the two is wrong. That incident is STILL UNDIAGNOSED for exactly this reason.
    ///
    /// This message is the ONLY channel that reaches the operator, which is why the data belongs
    /// here rather than in a log line. The shipped CLI jar binds slf4j to `NOPServiceProvider`, so
    /// every `log.*` call it makes is discarded — including `HetznerComputeProvider.logCreateRequest`,
    /// which already assembles the exact `serverType` that was sent and throws it away. Do not
    /// assume a log statement covers anything on this path.
    ///
    /// Both fields are [Option] because not every provisioning failure has a request behind it —
    /// a refusal raised before the request is assembled (spot rejection, unresolved cluster name)
    /// or a status lookup on an existing instance has no requested spec. Absent means absent: the
    /// requested-spec clause is omitted entirely rather than rendered with a blank or a guess, and
    /// a partially-known spec says so per field.
    ///
    /// The message deliberately does NOT list valid alternatives. Naming them would require either
    /// a network call on the failure path (rejected) or a catalogue baked into this repository —
    /// and a baked-in catalogue is the very rot that produced the incident, so it points at the
    /// provider's live catalogue instead.
    record ProvisionFailed(Option<String> instanceType, Option<String> zone, Throwable cause) implements EnvironmentError {
        public static Result<ProvisionFailed> provisionFailed(Throwable cause) {
            return provisionFailed(Option.empty(), Option.empty(), cause);
        }

        public static Result<ProvisionFailed> provisionFailed(Option<String> instanceType,
                                                              Option<String> zone,
                                                              Throwable cause) {
            return success(new ProvisionFailed(instanceType, zone, cause));
        }

        @Override
        public String message() {
            return "Node provisioning failed: " + cause.getMessage() + requestedSpec();
        }

        private String requestedSpec() {
            return known(instanceType).isEmpty() && known(zone).isEmpty()
                   ? ""
                   : " [requested instance type: " + describe(instanceType)
                    + ", location: " + describe(zone)
                    + "; providers retire instance types and vary availability by location — "
                    + "check both against the provider's current catalogue before retrying]";
        }

        private static Option<String> known(Option<String> value) {
            return value.filter(Verify.Is::present);
        }

        private static String describe(Option<String> value) {
            return known(value).or("not recorded at this failure point");
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

    /// A provider scoped to ONE cluster was asked to act on an instance that does not carry that cluster's label (another
    /// cluster's, or none: an unrelated container on a shared host). Nothing was done to the instance. `found` is the label the
    /// instance carries, empty when it carries none. #1543 F2.
    record OutOfClusterScope(InstanceId instanceId, String cluster, String found) implements EnvironmentError {
        public static Result<OutOfClusterScope> outOfClusterScope(InstanceId instanceId, String cluster, String found) {
            return success(new OutOfClusterScope(instanceId, cluster, found));
        }

        @Override
        public String message() {
            return "Refusing to act on '" + instanceId.value()
                 + "': it belongs to " + (found.isBlank()
                                          ? "no aether cluster"
                                          : "cluster '" + found + "'")
                 + ", this provider acts only on cluster '" + cluster
                 + "'. Nothing was changed.";
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
    record NodeCapExceeded(ClusterName clusterName, int cap, int observed) implements EnvironmentError {
        public static Result<NodeCapExceeded> nodeCapExceeded(ClusterName clusterName, int cap, int observed) {
            return success(new NodeCapExceeded(clusterName, cap, observed));
        }

        @Override
        public String message() {
            return "Provisioning refused for cluster '" + clusterName.value()
                 + "': node cap " + cap
                 + " reached (" + observed
                 + " already provisioned). Raise max_nodes for the source "
                 + "or terminate instances to go below the cap.";
        }
    }

    /// G1-a: the provider refused a create because the server name is held, and the holder IS this create's own server: it carries
    /// the cluster and node-id labels of the request. The create therefore already happened (an earlier attempt whose reply was
    /// lost, or a racing resume); nothing new was made. `existing` is the server that holds the name, as the provider lists it.
    ///
    /// Typed so a caller can adopt it instead of retrying: the two callers that may (the core `dispatchBound` and the apply's
    /// `createRecorded`) adopt `existing`; every other caller sees a failed create, which is the safe reading. It travels on the
    /// failure channel only because [ComputeProvider#createFrom] answers `Promise<InstanceInfo>`; it is never a reason to create again.
    record AlreadyExists(OperationId name, InstanceInfo existing) implements EnvironmentError {
        public static Result<AlreadyExists> alreadyExists(OperationId name, InstanceInfo existing) {
            return success(new AlreadyExists(name, existing));
        }

        @Override
        public String message() {
            return "A server named '" + name.value()
                 + "' already exists (instance " + existing.id()
                                                           .value()
                 + "); the create was not repeated and the existing server is the answer.";
        }
    }

    /// G1-a: the provider refused a create because the server name is held by a server that is NOT this create's own (it carries
    /// another cluster's labels, or another apply's). It is never adopted: taking it over would hand this cluster a server it does
    /// not own, and terminating it later would delete someone else's.
    record NameHeldByOther(OperationId name, InstanceId holder) implements EnvironmentError {
        public static Result<NameHeldByOther> nameHeldByOther(OperationId name, InstanceId holder) {
            return success(new NameHeldByOther(name, holder));
        }

        @Override
        public String message() {
            return "The server name '" + name.value()
                 + "' is held by instance " + holder.value()
                 + ", which does not belong to this create; it was neither adopted nor replaced.";
        }
    }

    /// G1-a: a request names its create with an [OperationId] and this provider cannot honour it (no unique server name to deduplicate
    /// on). Refused instead of ignored, because ignoring it would let the caller believe a repeated create is harmless.
    record OperationIdUnsupported(String provider, OperationId name) implements EnvironmentError {
        public static Result<OperationIdUnsupported> operationIdUnsupported(String provider, OperationId name) {
            return success(new OperationIdUnsupported(provider, name));
        }

        @Override
        public String message() {
            return "Provider '" + provider
                 + "' cannot honour operation id '" + name.value()
                 + "': it has no server name that refuses a duplicate create, so the request was refused.";
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

    /// Provisioning failure raised where the requested spec IS known — the provider's `createFrom`
    /// path. Prefer this over the single-argument form wherever a [ProvisionRequest] is in scope;
    /// see [ProvisionFailed] for why the pair is worth carrying.
    static EnvironmentError provisionFailed(String instanceType, String zone, Throwable cause) {
        return ProvisionFailed.provisionFailed(Option.option(instanceType),
                                               Option.option(zone),
                                               cause)
                              .unwrap();
    }

    static EnvironmentError capacityUnavailable(String zone, Throwable cause) {
        return CapacityUnavailable.capacityUnavailable(zone, cause).unwrap();
    }

    static EnvironmentError terminateFailed(InstanceId instanceId, Throwable cause) {
        return TerminateFailed.terminateFailed(instanceId, cause).unwrap();
    }

    static EnvironmentError outOfClusterScope(InstanceId instanceId, String cluster, String found) {
        return OutOfClusterScope.outOfClusterScope(instanceId, cluster, found).unwrap();
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

    static EnvironmentError alreadyExists(OperationId name, InstanceInfo existing) {
        return AlreadyExists.alreadyExists(name, existing).unwrap();
    }

    static EnvironmentError nameHeldByOther(OperationId name, InstanceId holder) {
        return NameHeldByOther.nameHeldByOther(name, holder).unwrap();
    }

    static EnvironmentError operationIdUnsupported(String provider, OperationId name) {
        return OperationIdUnsupported.operationIdUnsupported(provider, name).unwrap();
    }

    static EnvironmentError nodeCapExceeded(ClusterName clusterName, int cap, int observed) {
        return NodeCapExceeded.nodeCapExceeded(clusterName, cap, observed).unwrap();
    }
}
