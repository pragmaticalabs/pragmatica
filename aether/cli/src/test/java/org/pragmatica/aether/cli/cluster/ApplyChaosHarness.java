// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The harness every phase of the cluster-apply chain asserts against (cluster-apply spec section 9).
///
/// A counting fake `ComputeProvider` (provision and terminate calls per node id and per server id), listings that
/// can lag a create (a create may be made to LAND LATE: accepted and billed, listable only after k listings), a kill
/// seam at every step S0..S7 of the write discipline, and a controllable clock.
///
/// The universal invariant, asserted at the end of every scenario in every phase: the provider's inventory filtered
/// by the apply label equals exactly the ledger's rows that hold a VM, and no node id was created more than once.
/// `ApplyChaosHarnessTest` proves the invariant can fail; a check that cannot fail proves nothing.
final class ApplyChaosHarness {
    static final String APPLY_LABEL = "aether-apply";
    static final String NODE_LABEL = "aether-node-id";

    /// The steps of cluster-apply spec 4.2 a scenario can be killed at.
    enum Step {
        S0,
        S1,
        S2,
        S3,
        S4,
        S5,
        S6,
        S7
    }

    static final class Killed extends RuntimeException {
        Killed(Step step) {
            super("killed at " + step);
        }
    }

    final FakeProvider provider = new FakeProvider();
    private final AtomicLong clock = new AtomicLong();
    private Step armedStep;
    private int armedCountdown;

    long now() {
        return clock.get();
    }

    void advance(long millis) {
        clock.addAndGet(millis);
    }

    /// Kills the scenario the `nth` time (1-based) it reaches `step`.
    void armKill(Step step, int nth) {
        armedStep = step;
        armedCountdown = nth;
    }

    /// Called by the scenario (and by production seams from P2 on) as it passes a step.
    void reach(Step step) {
        if (step == armedStep && --armedCountdown == 0) {
            armedStep = null;

            throw new Killed(step);
        }
    }

    /// Empty when the invariant holds; otherwise one line per discrepancy.
    List<String> leakReport(String applyId, ApplyState ledger) {
        var inventory = provider.inventoryByLabel(APPLY_LABEL, applyId);
        var report = new ArrayList<String>();
        var held = new LinkedHashSet<String>();

        for (var row : ledger.resources()) {
            if (row.state().holdsVm()) {
                row.serverId().onPresent(held::add).onEmpty(() -> report.add("row " + row.nodeId() + " (" + row.state()
                                                                             + ") holds a VM but names no server id"));
            }
        }

        inventory.forEach(server -> report.addAll(missingFromLedger(held, server)));
        held.forEach(server -> report.addAll(missingFromProvider(inventory, server)));

        return report;
    }

    private static List<String> missingFromLedger(Set<String> held, String server) {
        return held.contains(server)
               ? List.of()
               : List.of("provider holds " + server + " under the apply label, the ledger does not");
    }

    private static List<String> missingFromProvider(Set<String> inventory, String server) {
        return inventory.contains(server)
               ? List.of()
               : List.of("ledger holds " + server + ", the provider has no such VM under the apply label");
    }

    void assertNoLeak(String applyId, ApplyState ledger) {
        assertThat(leakReport(applyId, ledger)).as("leak invariant: provider inventory by apply label == ledger rows that hold a VM")
                                               .isEmpty();
    }

    void assertAtMostOneCreatePerNodeId() {
        assertThat(provider.createCalls()
                           .entrySet()).as("an id is used for at most one create call, ever")
                                       .allSatisfy(entry -> assertThat(entry.getValue()).isLessThanOrEqualTo(1));
    }

    static final class FakeProvider implements ComputeProvider {
        private final Map<String, InstanceInfo> visible = new LinkedHashMap<>();
        private final Map<String, Integer> hiddenUntilListing = new LinkedHashMap<>();
        private final Map<String, InstanceInfo> hidden = new LinkedHashMap<>();
        private final Map<String, Integer> lateNodes = new LinkedHashMap<>();
        private final Map<String, Integer> createCalls = new LinkedHashMap<>();
        private final Map<String, Integer> terminateCalls = new LinkedHashMap<>();
        private int listCalls;
        private int serverSeq;

        /// The next create for `nodeId` is accepted and billed at once but becomes listable only after
        /// `listings` more `listInstances` calls (the slow-landing create).
        void landLate(String nodeId, int listings) {
            lateNodes.put(nodeId, listings);
        }

        Map<String, Integer> createCalls() {
            return Map.copyOf(createCalls);
        }

        Map<String, Integer> terminateCalls() {
            return Map.copyOf(terminateCalls);
        }

        int totalCreates() {
            return createCalls.values()
                              .stream()
                              .mapToInt(Integer::intValue)
                              .sum();
        }

        int totalTerminates() {
            return terminateCalls.values()
                                 .stream()
                                 .mapToInt(Integer::intValue)
                                 .sum();
        }

        /// Ground truth: server ids carrying `label=value`, including VMs accepted but not yet listable.
        Set<String> inventoryByLabel(String label, String value) {
            var all = new LinkedHashMap<>(visible);

            all.putAll(hidden);

            var matching = new LinkedHashSet<String>();

            all.forEach((server, info) -> {
                if (value.equals(info.tags().get(label))) {
                    matching.add(server);
                }
            });

            return matching;
        }

        /// Creates a VM carrying the given tags plus the node-id label, as a provider adapter would.
        InstanceInfo create(String nodeId, Map<String, String> tags) {
            createCalls.merge(nodeId, 1, Integer::sum);

            var server = "srv-" + (++serverSeq);
            var allTags = new LinkedHashMap<>(tags);

            allTags.put(NODE_LABEL, nodeId);

            var info = new InstanceInfo(new InstanceId(server),
                                        InstanceStatus.RUNNING,
                                        List.of("10.0.0." + serverSeq),
                                        InstanceType.ON_DEMAND,
                                        Map.copyOf(allTags));
            var late = lateNodes.remove(nodeId);

            if (late == null) {
                visible.put(server, info);
            } else {
                hidden.put(server, info);
                hiddenUntilListing.put(server, listCalls + late);
            }

            return info;
        }

        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            var context = spec.context();

            return Promise.success(create(context.nodeId().or(""), context.extraTags()));
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Causes.cause("not used: the harness captures provision(spec)").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            terminateCalls.merge(instanceId.value(), 1, Integer::sum);

            var removed = visible.remove(instanceId.value());
            var cancelled = hidden.remove(instanceId.value());

            return removed == null && cancelled == null
                   ? EnvironmentError.instanceNotFound(instanceId).promise()
                   : Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            listCalls++;
            landDueVms();

            return Promise.success(List.copyOf(visible.values()));
        }

        private void landDueVms() {
            hiddenUntilListing.entrySet()
                              .removeIf(entry -> landIfDue(entry.getKey(), entry.getValue()));
        }

        private boolean landIfDue(String server, int dueAt) {
            if (listCalls < dueAt) {
                return false;
            }

            visible.put(server, hidden.remove(server));

            return true;
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            var info = visible.get(instanceId.value());

            return info == null
                   ? EnvironmentError.instanceNotFound(instanceId).promise()
                   : Promise.success(info);
        }
    }
}
