// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker;

import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;


public sealed interface WorkerError extends Cause, HttpStatusAware {
    /// Default 500 — most worker errors are server-side. Conflict variants (state-machine
    /// pre-conditions) and unavailable variants (transient) override.
    @Override
    default HttpStatus httpStatus() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    enum General implements WorkerError {
        NO_CORE_NODES("No core nodes configured", HttpStatus.CONFLICT),
        NODE_ALREADY_STARTED("Worker node is already started", HttpStatus.CONFLICT),
        NODE_NOT_STARTED("Worker node has not been started", HttpStatus.CONFLICT),
        BOOTSTRAP_FAILED("Bootstrap failed: no snapshot source available", HttpStatus.INTERNAL_SERVER_ERROR),
        GOVERNOR_UNAVAILABLE("Governor node is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        private final String message;
        private final HttpStatus status;
        General(String message, HttpStatus status) {
            this.message = message;
            this.status = status;
        }
        @Override
        public String message() {
            return message;
        }
        @Override
        public HttpStatus httpStatus() {
            return status;
        }
    }

    record NetworkFailure(Throwable cause) implements WorkerError {
        @Override
        public String message() {
            return "Worker network failure: " + Causes.fromThrowable(cause);
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    record ConfigurationError(String detail) implements WorkerError {
        @Override
        public String message() {
            return "Worker configuration error: " + detail;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    record unused() implements WorkerError {
        @Override
        public String message() {
            return "unused";
        }
    }
}
