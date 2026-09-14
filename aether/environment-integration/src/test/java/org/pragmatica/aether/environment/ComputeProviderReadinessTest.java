// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #1049 round 3 (S2) — infrastructure readiness treats a status the provider could not state as "not yet
/// running", never as a boot crash. A crash verdict fails the provision, and the auto-heal tracker then drops
/// a replacement whose instance may still exist and mints another.
class ComputeProviderReadinessTest {
    private static final ReadinessPolicy FAST_POLICY = new ReadinessPolicy(timeSpan(5).seconds(), timeSpan(10).millis());

    @Test
    void confirmRunning_unknownStatusBeforeRunning_keepsPollingAndSucceeds() {
        var provider = new ScriptedStatusProvider(List.of(InstanceStatus.UNKNOWN, InstanceStatus.UNKNOWN, InstanceStatus.RUNNING));

        provider.confirmRunning(instanceIn(InstanceStatus.PROVISIONING), FAST_POLICY)
                .await()
                .onFailure(cause -> fail("an UNKNOWN status is not a crash, got: " + cause.message()))
                .onSuccess(info -> assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING));
        assertThat(provider.polls()).isEqualTo(3);
    }

    /// The guard beside it: a terminal status still fails readiness at once.
    @Test
    void confirmRunning_terminatedStatus_failsWithoutPollingAgain() {
        var provider = new ScriptedStatusProvider(List.of(InstanceStatus.TERMINATED, InstanceStatus.RUNNING));

        provider.confirmRunning(instanceIn(InstanceStatus.PROVISIONING), FAST_POLICY)
                .await()
                .onSuccess(info -> fail("a terminated instance must not be reported running"))
                .onFailure(cause -> assertThat(cause).isInstanceOf(EnvironmentError.ProvisionReadinessTimeout.class));
        assertThat(provider.polls()).isEqualTo(1);
    }

    private static InstanceInfo instanceIn(InstanceStatus status) {
        return new InstanceInfo(new InstanceId("srv-1"), status, List.of(), InstanceType.ON_DEMAND, Map.of());
    }

    /// Answers `instanceStatus` from a script, repeating the last entry once it runs out.
    private static final class ScriptedStatusProvider implements ComputeProvider {
        private final List<InstanceStatus> script;
        private final AtomicInteger polls = new AtomicInteger();

        ScriptedStatusProvider(List<InstanceStatus> script) {
            this.script = List.copyOf(script);
        }

        int polls() {
            return polls.get();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.success(instanceIn(script.get(Math.min(polls.getAndIncrement(), script.size() - 1))));
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("createFrom").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return EnvironmentError.operationNotSupported("terminate").promise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }
    }
}
