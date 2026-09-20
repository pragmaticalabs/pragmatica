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

    /// #524 SHOULD-FIX 1: the Management-API publish auto-create guard (`ensureStreamExists`) could
    /// not materialize the stream -- a genuine failure (capacity exhausted, or STRONG consistency
    /// requiring AHSE storage), never a transient config-visibility race, which the manager resolves
    /// internally and never surfaces here. The declared partition count is therefore genuinely
    /// unknown at the route, so `validatePartition` must never guess one (e.g. against a hardcoded
    /// default) -- this reports 409 naming the stream and the underlying cause instead of silently
    /// accepting an invalid partition or rejecting a valid one against a fabricated count.
    record StreamUnavailable(String streamName, String reason) implements ManagementServerError {
        @Override
        public String message() {
            return "Stream '" + streamName + "' is not available: " + reason;
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
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

    /// #1224: `STREAMS_CREATE` maps `StreamRegistryError.General.ALREADY_REGISTERED` here so the
    /// duplicate-catalog-entry case reports 409 rather than the sealed interface's plain-`Cause`
    /// default of 500 (`StreamRegistryError` is not `HttpStatusAware`).
    record StreamAlreadyRegistered(String address) implements ManagementServerError {
        @Override
        public String message() {
            return "Stream '" + address + "' is already registered in the catalog";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    /// #1282: a Management-API write would mint a stream whose engine name carries a reserved kind
    /// prefix (`system:`, `topic:`, `entity:`). Those streams are created only by internal provisioning;
    /// minting one here would plant an operator-chosen config the real resource later finds in place.
    record ReservedStreamName(String streamName, String prefix) implements ManagementServerError {
        @Override
        public String message() {
            return "Stream name '" + streamName
                 + "' uses the reserved prefix '" + prefix
                 + "'; streams under it are created only by internal provisioning";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
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
