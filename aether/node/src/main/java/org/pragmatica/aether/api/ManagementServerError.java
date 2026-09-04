// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


public sealed interface ManagementServerError extends Cause, HttpStatusAware {
    /// Default 500 — BindFailed is server-side. Input-validation variants and routing
    /// variants override.
    @Override
    default HttpStatus httpStatus() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    record BindFailed(int port, Throwable cause) implements ManagementServerError {
        @Override
        public String message() {
            return "Failed to bind management server on port " + port + ": " + cause.getMessage();
        }
    }

    record MissingField(String fieldName) implements ManagementServerError {
        @Override
        public String message() {
            return "Missing '" + fieldName + "' field";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    record InvalidArtifactPath(String path) implements ManagementServerError {
        @Override
        public String message() {
            return "Invalid artifact path. Expected: /repository/info/{groupPath}/{artifactId}/{version}, got: " + path;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    /// #524: an explicit `partition` on a Management-API publish named a partition the stream does not
    /// declare. Names the valid range rather than silently writing to partition 0 or 500ing.
    record InvalidPartition(int requested, int partitionCount) implements ManagementServerError {
        @Override
        public String message() {
            return "Partition %d is out of range; this stream has partitions [0, %d)".formatted(requested,
                                                                                                partitionCount);
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    record NotLeader(String leaderId) implements ManagementServerError {
        @Override
        public String message() {
            return "This operation requires the leader node." + (leaderId.isEmpty()
                                                                 ? ""
                                                                 : " Current leader: " + leaderId);
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    enum StrategyChangeNotSupported implements ManagementServerError {
        INSTANCE;
        @Override
        public String message() {
            return "Runtime strategy change not supported. Strategy must be set at node startup.";
        }
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.NOT_IMPLEMENTED;
        }
    }
}
