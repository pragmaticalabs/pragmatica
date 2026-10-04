// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


/// #833/#954: every variant carries its own status. Untyped, each reached the management routes as a 500, so a
/// conclude naming an unknown test or variant, a duplicate create, and the manager's own not-leader refusal read
/// on the wire as a server fault.
public sealed interface AbTestDeploymentError extends Cause, HttpStatusAware {
    record TestNotFound(String testId) implements AbTestDeploymentError {
        public static TestNotFound testNotFound(String testId) {
            return new TestNotFound(testId);
        }

        @Override
        public String message() {
            return "A/B test not found: " + testId;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_FOUND;
        }
    }

    record TestAlreadyExists(ArtifactBase artifactBase) implements AbTestDeploymentError {
        public static TestAlreadyExists testAlreadyExists(ArtifactBase artifactBase) {
            return new TestAlreadyExists(artifactBase);
        }

        @Override
        public String message() {
            return "A/B test already in progress for " + artifactBase;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record InvalidTestState(AbTestState from, AbTestState to) implements AbTestDeploymentError {
        public static InvalidTestState invalidTestState(AbTestState from, AbTestState to) {
            return new InvalidTestState(from, to);
        }

        @Override
        public String message() {
            return "Invalid A/B test state transition from " + from + " to " + to;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record InitialDeployment(ArtifactBase artifactBase) implements AbTestDeploymentError {
        public static InitialDeployment initialDeployment(ArtifactBase artifactBase) {
            return new InitialDeployment(artifactBase);
        }

        @Override
        public String message() {
            return "Initial deployment for " + artifactBase + " (no previous version)";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record VariantNotFound(String testId, String variant) implements AbTestDeploymentError {
        public static VariantNotFound variantNotFound(String testId, String variant) {
            return new VariantNotFound(testId, variant);
        }

        @Override
        public String message() {
            return "Variant '" + variant + "' not found in A/B test " + testId;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    enum NotLeader implements AbTestDeploymentError {
        INSTANCE;
        @Override
        public String message() {
            return "A/B test operations can only be performed by the leader node";
        }
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }
}
