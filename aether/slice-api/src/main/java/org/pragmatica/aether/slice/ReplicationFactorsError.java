// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;


/// #1564: why a replication policy was refused. One type for streams, durable topics and durable entities, so
/// every kind reports the same refusal the same way.
public sealed interface ReplicationFactorsError extends Cause {
    record FactorBelowOne(int replicationFactor) implements ReplicationFactorsError {
        @Override
        public String message() {
            return "replication_factor = " + replicationFactor + " is invalid: must be at least 1";
        }
    }

    record ConfirmationOutOfRange(int replicationFactor, int confirmationFactor) implements ReplicationFactorsError {
        @Override
        public String message() {
            return "confirmation_factor = " + confirmationFactor
                 + " is invalid for replication_factor = " + replicationFactor
                 + ": 1 <= confirmation_factor <= replication_factor must hold";
        }
    }

    /// An RF below 3 that the resource did not declare itself (it came from a default). Only an explicit
    /// declaration may go below 3 (owner ruling, know 267792392).
    record ImplicitFactorBelowThree(int replicationFactor) implements ReplicationFactorsError {
        @Override
        public String message() {
            return "replication_factor = " + replicationFactor
                 + " comes from a default; a factor below 3 must be declared explicitly on the resource";
        }
    }

    record ExceedsCoreCount(int replicationFactor, int desiredCoreCount) implements ReplicationFactorsError {
        @Override
        public String message() {
            return "replication_factor = " + replicationFactor
                 + " exceeds the cluster's desired core count of " + desiredCoreCount;
        }
    }

    record ChangedOnLiveResource(String resource, ReplicationFactors committed, ReplicationFactors declared) implements ReplicationFactorsError {
        @Override
        public String message() {
            return resource
                 + " is committed with " + committed
                 + " and is now declared with " + declared
                 + "; the replication policy of a live resource is not changed in place";
        }
    }

    /// A declared value that is not a whole number (the key named).
    record NotAnInteger(String key, String value) implements ReplicationFactorsError {
        @Override
        public String message() {
            return key + " = '" + value + "' is not an integer";
        }
    }
}
