// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The container names a docker bootstrap has recorded in its ledger, and the way to record one more (#2124).
///
/// A docker node's identity is its container name, and the peers list every core is created with names every core, so the
/// names are fixed before the first container exists. They used to live nowhere: a re-run minted a second set and left the
/// first beside it. Each name is now a [CreatedResource.ProvisionedVm] row written BEFORE the create it names, so a re-run
/// reads the same names back and a failed run's containers are reachable by cleanup.
///
/// `prior` is the ledger as the phase STARTED: what an earlier run may already have created. It is not updated as this run
/// records, so "recorded earlier" and "recorded now" stay distinguishable: only the first can name a container that exists.
record DockerNodeLedger(List<CreatedResource.ProvisionedVm> prior,
                        Fn1<Result<Unit>, CreatedResource.ProvisionedVm> recorder) {
    /// Records nothing and remembers nothing: the seam for callers that run the provisioning without a persisted bootstrap
    /// (every name is new, so nothing is looked up).
    static DockerNodeLedger none() {
        return new DockerNodeLedger(List.of(), _ -> Result.unitResult());
    }

    /// Test seam (package-private): replaces the persisted ledger for a test that drives the phase without writing the operator's home.
    /// Null in production.
    static volatile Fn1<DockerNodeLedger, BootstrapState> override;

    /// The ledger of the persisted bootstrap state: `state` is the phase's starting state, rows are appended to the file.
    static DockerNodeLedger persisted(BootstrapState state) {
        var seam = override;

        return seam != null
               ? seam.apply(state)
               : new DockerNodeLedger(vmRows(state),
                                      vm -> BootstrapStatePersistence.appendResource(state.clusterName(), vm));
    }

    private static List<CreatedResource.ProvisionedVm> vmRows(BootstrapState state) {
        return state.createdResources()
                    .stream()
                    .filter(CreatedResource.ProvisionedVm.class::isInstance)
                    .map(CreatedResource.ProvisionedVm.class::cast)
                    .toList();
    }

    /// The names recorded earlier for the slots of `sourceName`/`role`, in slot order (rows are appended in slot order).
    List<String> priorNames(String sourceName, NodeRole role) {
        return prior.stream()
                    .filter(vm -> vm.sourceName()
                                    .equals(sourceName) && vm.role()
                                                             .equals(role.value()))
                    .map(CreatedResource.ProvisionedVm::resourceId)
                    .toList();
    }

    boolean wasRecorded(String name) {
        return prior.stream()
                    .anyMatch(vm -> vm.resourceId()
                                      .equals(name));
    }

    /// Appends the row unless an earlier run already recorded this name. A failure is the caller's to act on: the name must
    /// not be created while the ledger cannot hold it.
    Result<Unit> record(String providerName, String sourceName, NodeRole role, String name) {
        return wasRecorded(name)
               ? Result.unitResult()
               : recorder.apply(CreatedResource.ProvisionedVm.provisionedVm(providerName, name, sourceName, role.value()));
    }
}
