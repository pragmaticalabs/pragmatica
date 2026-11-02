// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// The harness provider with hooks and call records, for the resume, audit and rollback tests: a test replaces what a
/// terminate or a listing does, and reads which servers were terminated, in what order, and how often it was listed.
final class HookedProvider implements ComputeProvider {
    private final ApplyChaosHarness harness;
    final List<String> terminated = new ArrayList<>();
    int filteredListings;
    int unfilteredListings;
    Function<InstanceId, Promise<Unit>> onTerminate;
    Function<Map<String, String>, Promise<List<InstanceInfo>>> onFilteredList;
    Function<Unit, Promise<List<InstanceInfo>>> onList;

    HookedProvider(ApplyChaosHarness harness) {
        this.harness = harness;
        this.onTerminate = id -> harness.provider.terminate(id);
        this.onFilteredList = filter -> harness.provider.listInstances(filter);
        this.onList = _ -> harness.provider.listInstances();
    }

    @Override
    public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        return harness.provider.provision(spec);
    }

    @Override
    public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
        throw new UnsupportedOperationException("not used");
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        terminated.add(instanceId.value());

        return onTerminate.apply(instanceId);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        unfilteredListings++;

        return onList.apply(Unit.unit());
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        filteredListings++;

        return onFilteredList.apply(tagFilter);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return harness.provider.instanceStatus(instanceId);
    }
}
