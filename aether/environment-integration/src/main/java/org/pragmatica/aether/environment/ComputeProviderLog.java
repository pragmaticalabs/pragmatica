// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Logging + cause construction for [ComputeProvider#confirmRunning]. Kept out of the
/// interface body so the readiness-poll flow stays a pure monadic chain while the
/// boot-crash / timeout decisions are still observable to operators.
sealed interface ComputeProviderLog {
    Logger log = LoggerFactory.getLogger(ComputeProvider.class);

    /// Instance reached a terminal non-RUNNING state during boot (e.g. Docker `exited`,
    /// cloud VM `Terminated`). The container/VM crashed before becoming live.
    static Cause bootCrashed(InstanceId instanceId, InstanceStatus lastStatus) {
        log.warn("Provision readiness FAILED for {}: instance reached terminal status {} during boot — refusing to report a phantom RUNNING node",
                 instanceId.value(),
                 lastStatus);

        return EnvironmentError.provisionReadinessTimeout(instanceId, lastStatus, 0L);
    }

    /// Readiness poll loop exhausted [ReadinessPolicy#timeout] while the instance was
    /// still PROVISIONING (or status reads kept failing). Surface a provisioning failure
    /// so the caller frees the slot instead of minting a phantom.
    static Cause readinessTimeout(InstanceId instanceId, long timeoutMillis, Cause underlying) {
        log.warn("Provision readiness TIMED OUT for {} after {}ms (last poll outcome: {}) — refusing to report a phantom RUNNING node",
                 instanceId.value(),
                 timeoutMillis,
                 underlying.message());

        return EnvironmentError.provisionReadinessTimeout(instanceId, InstanceStatus.PROVISIONING, timeoutMillis);
    }

    record unused() implements ComputeProviderLog {}
}
