// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


/// Failure causes raised by the deployment domain, each projecting itself onto the HTTP status its
/// semantics demand (#569).
///
/// These variants were typed but status-less, and `ProblemResponses.resolveStatus` tests
/// `cause instanceof HttpStatusAware` and silently defaults everything else to 500 — so deploying a
/// blueprint that does not exist answered `500 Internal Server Error`, indistinguishable on the wire
/// from a genuine node fault. The information needed to answer correctly already existed in this
/// hierarchy and was simply discarded at the HTTP boundary. Same defect class as
/// [org.pragmatica.aether.api.routes.SchemaRouteError] (#542) and
/// `SchemaError.DatasourceOwnershipConflict`.
///
/// **`httpStatus()` is deliberately left abstract** rather than defaulted to 500. Every variant here
/// has a genuine semantic code, and a default would let the next variant added silently inherit the
/// very 500 this change exists to eliminate — making the omission a compile error is the point.
public sealed interface DeploymentError extends Cause, HttpStatusAware {
    /// 404 — the addressed deployment id does not exist.
    record DeploymentNotFound(String deploymentId) implements DeploymentError {
        public static DeploymentNotFound deploymentNotFound(String deploymentId) {
            return new DeploymentNotFound(deploymentId);
        }

        @Override
        public String message() {
            return "Deployment not found: " + deploymentId;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_FOUND;
        }
    }

    /// 409 — the request is well-formed and the blueprint exists; the conflict is with current
    /// cluster state (a deployment for it is already running), which is CONFLICT, not BAD_REQUEST.
    record DeploymentAlreadyExists(String blueprintId) implements DeploymentError {
        public static DeploymentAlreadyExists deploymentAlreadyExists(String blueprintId) {
            return new DeploymentAlreadyExists(blueprintId);
        }

        @Override
        public String message() {
            return "Deployment already in progress for blueprint: " + blueprintId;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// 404 — the blueprint named in the request is not registered on this cluster. This is THE
    /// variant behind #569: it was the observed 500 on `POST /api/deploy`.
    record BlueprintNotFound(String blueprintId) implements DeploymentError {
        public static BlueprintNotFound blueprintNotFound(String blueprintId) {
            return new BlueprintNotFound(blueprintId);
        }

        @Override
        public String message() {
            return "Blueprint not found: " + blueprintId;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_FOUND;
        }
    }

    /// 409 — an upgrade was requested for an artifact that has nothing currently deployed. The
    /// request is well-formed; it conflicts with cluster state, and the message already tells the
    /// operator which endpoint to use instead.
    record NoCurrentVersion(String artifactBase) implements DeploymentError {
        public static NoCurrentVersion noCurrentVersion(String artifactBase) {
            return new NoCurrentVersion(artifactBase);
        }

        @Override
        public String message() {
            return "No current version for " + artifactBase + " (initial deployment — use blueprint deploy instead)";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// 409 — the requested version is already the active one. State conflict, not malformed input.
    record SameVersionDeployment(String version) implements DeploymentError {
        public static SameVersionDeployment sameVersionDeployment(String version) {
            return new SameVersionDeployment(version);
        }

        @Override
        public String message() {
            return "Cannot deploy version " + version
                 + " — already active. Use /api/blueprints/deploy for redeployment.";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// 500 — the ONLY genuinely server-side variant: the request was fine and the cluster failed to
    /// apply it. Keeping this at 500 is what makes the other variants' codes meaningful.
    record ConsensusFailure(Cause cause) implements DeploymentError {
        public static ConsensusFailure consensusFailure(Cause cause) {
            return new ConsensusFailure(cause);
        }

        @Override
        public String message() {
            return "Consensus apply failed: " + cause.message();
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    /// Fixed-message deployment failures. Each constant carries its own status rather than sharing
    /// one for the enum: they differ genuinely — a misrouted request, a state conflict and malformed
    /// input are three different answers.
    enum General implements DeploymentError {
        /// 503 — the client did nothing wrong; this node simply does not hold the STRATEGIES task
        /// group. The operation may succeed against the assigned node or after reassignment, which is
        /// SERVICE_UNAVAILABLE ("try again / try elsewhere"), not a client error.
        NOT_ASSIGNED("Operation requires the node currently assigned the STRATEGIES task group",
                     HttpStatus.SERVICE_UNAVAILABLE),
        /// 409 — the deployment exists but is not in a state where the operation applies.
        NOT_ACTIVE("Deployment is not in an active state", HttpStatus.CONFLICT),
        /// 400 — the submitted strategy config does not match the declared strategy: malformed input.
        INVALID_STRATEGY_CONFIG("Strategy config does not match deployment strategy", HttpStatus.BAD_REQUEST);
        private final String msg;
        private final HttpStatus status;
        General(String msg, HttpStatus status) {
            this.msg = msg;
            this.status = status;
        }
        @Override
        public String message() {
            return msg;
        }
        @Override
        public HttpStatus httpStatus() {
            return status;
        }
    }
}
