// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Provisioning-readiness bounds for [ComputeProvider#confirmRunning].
///
/// Confirms INFRASTRUCTURE readiness only — that the container/VM reached
/// [InstanceStatus#RUNNING]. It deliberately does NOT cover cluster-join,
/// first-pong, or KV-registration; those belong to a higher layer (CTM).
///
/// `timeout` bounds the whole poll loop; `pollInterval` is the delay between
/// consecutive [ComputeProvider#instanceStatus] reads. Local containers come up
/// in seconds; cloud VMs boot slowly and flakily, hence the separate
/// [#dockerDefault] / [#cloudDefault] presets rather than a single magic literal.
public record ReadinessPolicy(TimeSpan timeout, TimeSpan pollInterval) {
    /// Docker: containers reach `running` almost immediately after `docker run -d`;
    /// a short window with tight polling catches an exited/dead boot fast.
    private static final TimeSpan DOCKER_TIMEOUT = timeSpan(30).seconds();
    private static final TimeSpan DOCKER_POLL_INTERVAL = timeSpan(500).millis();
    /// Cloud: VM boot is slow and flaky, so confirmation matters MORE here; allow a
    /// generous window with a relaxed poll cadence to avoid hammering the provider API.
    private static final TimeSpan CLOUD_TIMEOUT = timeSpan(5).minutes();
    private static final TimeSpan CLOUD_POLL_INTERVAL = timeSpan(5).seconds();

    public static Result<ReadinessPolicy> readinessPolicy(TimeSpan timeout, TimeSpan pollInterval) {
        return success(new ReadinessPolicy(timeout, pollInterval));
    }

    public static ReadinessPolicy dockerDefault() {
        return new ReadinessPolicy(DOCKER_TIMEOUT, DOCKER_POLL_INTERVAL);
    }

    public static ReadinessPolicy cloudDefault() {
        return new ReadinessPolicy(CLOUD_TIMEOUT, CLOUD_POLL_INTERVAL);
    }

    record unused() {}
}
