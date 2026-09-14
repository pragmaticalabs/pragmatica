// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public sealed interface InstanceStatus {
    record Provisioning() implements InstanceStatus {
        public static Result<Provisioning> provisioning() {
            return success(new Provisioning());
        }
    }

    record Running() implements InstanceStatus {
        public static Result<Running> running() {
            return success(new Running());
        }
    }

    record Stopping() implements InstanceStatus {
        public static Result<Stopping> stopping() {
            return success(new Stopping());
        }
    }

    record Terminated() implements InstanceStatus {
        public static Result<Terminated> terminated() {
            return success(new Terminated());
        }
    }

    /// #1049 — the provider reported a status that says neither "coming" nor "going": a documented
    /// indeterminate value (Hetzner `unknown`, GCP `REPAIRING`, Azure `PowerState/unknown`) or any value the
    /// provider's mapping does not recognise. Never read as stopping or terminated: readiness keeps polling it
    /// until its timeout, and the auto-heal in-flight tracker keeps the replacement until its ceiling.
    record Unknown() implements InstanceStatus {
        public static Result<Unknown> unknown() {
            return success(new Unknown());
        }
    }

    InstanceStatus PROVISIONING = Provisioning.provisioning().unwrap();
    InstanceStatus RUNNING = Running.running().unwrap();
    InstanceStatus STOPPING = Stopping.stopping().unwrap();
    InstanceStatus TERMINATED = Terminated.terminated().unwrap();
    InstanceStatus UNKNOWN = Unknown.unknown().unwrap();

    record unused() implements InstanceStatus {
        public static Result<unused > unused() {
            return success(new unused());
        }
    }
}
