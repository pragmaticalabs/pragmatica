// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.Map;
import java.util.function.Function;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/// A ledger plus a provider holding the VMs it records, built the way P2 builds them (intent row, provider create with
/// the apply labels, created row), so the resume and rollback tests start from honest states.
final class ApplyScenario {
    static final String ACTION = "AddRole|eu-1|worker|->3";

    final ApplyChaosHarness harness = new ApplyChaosHarness();
    final HookedProvider provider = new HookedProvider(harness);
    final ClusterName cluster;
    final LedgerDirExtension ledger;
    final ApplyRecorder recorder;

    ApplyScenario(LedgerDirExtension ledger, ClusterName cluster) {
        this.ledger = ledger;
        this.cluster = cluster;
        this.recorder = ledger.recorder(cluster, java.util.List.of(ACTION));
    }

    Function<String, Result<ComputeProvider>> computes() {
        return _ -> Result.success(provider);
    }

    Map<String, String> labels() {
        return Map.of(ApplyChaosHarness.APPLY_LABEL, recorder.applyId(), "aether-cluster", cluster.value());
    }

    /// Intent, create at the provider, created row: returns the server id.
    String createRecorded(String nodeId) {
        recorder.intend(nodeId, "hetzner").unwrap();

        var serverId = harness.provider.create(nodeId, labels()).id().value();

        recorder.created(nodeId, serverId).unwrap();

        return serverId;
    }

    /// Intent row only, the VM exists at the provider: a create whose reply was lost.
    String createWithLostReply(String nodeId) {
        recorder.intend(nodeId, "hetzner").unwrap();

        return harness.provider.create(nodeId, labels()).id().value();
    }

    ApplyState onDisk() {
        return ledger.ledger(cluster);
    }

    ApplyResource row(String nodeId) {
        return onDisk().resources().stream().filter(row -> row.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    /// Wraps an existing recorder (a ledger saved with a different hash, say) in a scenario of its own provider.
    ApplyScenario(LedgerDirExtension ledger, ClusterName cluster, ApplyRecorder recorder) {
        this.ledger = ledger;
        this.cluster = cluster;
        this.recorder = recorder;
    }

    static Option<String> some(String value) {
        return Option.some(value);
    }
}
