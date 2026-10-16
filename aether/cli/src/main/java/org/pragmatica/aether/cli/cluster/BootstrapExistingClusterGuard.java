// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Owner Q5 (rc4) — a FRESH bootstrap must not run over a cluster that already exists under the same name.
///
/// Without this, `aether cluster bootstrap` with a live cluster's name mints a new secret, overwrites the
/// live cluster's `bootstrap-state.json` and registry entry, and on Hetzner adopts its firewall, after which
/// `destroy <name>` sweeps the live cluster's VMs by label. The refusal happens BEFORE `BootstrapPhaseValidate`
/// mints a secret and before any phase saves state, and it happens OUTSIDE the failure path of
/// `ClusterBootstrapOrchestrator.freshBootstrap`: that path runs `decorateAfterCleanup`, which reaps whatever
/// the persisted ledger of this NAME records — for a refused run that ledger belongs to the cluster we are
/// protecting.
///
/// Three independent checks, each refusing with `ClusterAlreadyExists` that names the check that matched:
/// the registry holds the name; a local bootstrap state exists for it; a cloud source's provider lists an
/// instance labelled `aether-cluster=<name>`. A check that cannot be made refuses too
/// (`ExistingClusterCheckFailed`): proceeding on a guess is the incident this guard exists to prevent.
///
/// Not covered, on purpose: `--resume` (does not call this), rollback, the ledger and the sweep. There is no
/// `--force`; an override is how the incident recurs, and the operator can `destroy` first.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapExistingClusterGuard {
    record unused() implements BootstrapExistingClusterGuard {}

    String CLUSTER_LABEL = "aether-cluster";
    String REGISTRY_CHECK = "registry";
    String INSTANCES_CHECK = "cloud instances";
    /// Bound on one provider listing, so a hung cloud API fails the check (and so refuses) instead of hanging
    /// the CLI before anything has been created.
    long LIST_TIMEOUT_SECONDS = 30;

    /// The three observations, injectable so a test needs neither the operator's home directory nor a cloud
    /// account. Defaults read the real registry, the real state file and the real provider.
    record Probes(Fn0<Result<ClusterRegistry>> registry,
                  Fn1<Result<Option<BootstrapState>>, ClusterName> state,
                  Fn2<Result<List<InstanceInfo>>, SourceProfile, ClusterName> instances) {
        static Probes probes() {
            return new Probes(ClusterRegistry::load,
                              BootstrapStatePersistence::read,
                              listingVia(ProviderResolver::resolveCloudCompute));
        }
    }

    static Result<ClusterBootstrapConfig> refuseExisting(ClusterBootstrapConfig config) {
        return refuseExisting(config, Probes.probes());
    }

    static Result<ClusterBootstrapConfig> refuseExisting(ClusterBootstrapConfig config, Probes probes) {
        var name = config.cluster().name();

        return refuseRegistered(name, probes).flatMap(_ -> refuseLocalState(name, probes))
                               .flatMap(_ -> refuseLabelledInstances(config, probes))
                               .map(_ -> config);
    }

    private static Result<Unit> refuseRegistered(ClusterName name, Probes probes) {
        return probes.registry()
                     .apply()
                     .mapError(cause -> unconfirmed(name, REGISTRY_CHECK, cause))
                     .flatMap(registry -> refuseIfRegistered(registry, name));
    }

    private static Result<Unit> refuseIfRegistered(ClusterRegistry registry, ClusterName name) {
        return refuseIf(registry.entries()
                                .stream()
                                .map(ClusterRegistry.ClusterEntry::name)
                                .anyMatch(name.value()::equals),
                        name,
                        "the cluster registry already holds it");
    }

    /// An UNREADABLE state file is a match, not a failed check: the file exists, and `BootstrapStatePersistence.read`
    /// raises only when it does, so refusing is the true answer and the bytes stay on disk for recovery.
    private static Result<Unit> refuseLocalState(ClusterName name, Probes probes) {
        return probes.state()
                     .apply(name)
                     .fold(_ -> alreadyExists(name, "a local bootstrap state exists for it but is unreadable").result(),
                           persisted -> refuseIfPersisted(persisted, name));
    }

    private static Result<Unit> refuseIfPersisted(Option<BootstrapState> persisted, ClusterName name) {
        return refuseIf(persisted.isPresent(),
                        name,
                        "a local bootstrap state exists for it (" + BootstrapStatePersistence.statePath(name) + ")");
    }

    private static Result<Unit> refuseLabelledInstances(ClusterBootstrapConfig config, Probes probes) {
        var name = config.cluster().name();

        return cloudSources(config).stream()
                           .reduce(Result.unitResult(),
                                   (checked, source) -> checked.flatMap(_ -> refuseLabelledOn(source, name, probes)),
                                   (left, _) -> left);
    }

    private static List<SourceProfile> cloudSources(ClusterBootstrapConfig config) {
        return config.sources()
                     .values()
                     .stream()
                     .filter(source -> source.type() == SourceType.CLOUD)
                     .toList();
    }

    private static Result<Unit> refuseLabelledOn(SourceProfile source, ClusterName name, Probes probes) {
        return probes.instances()
                     .apply(source, name)
                     .mapError(cause -> unconfirmed(name,
                                                    INSTANCES_CHECK + " of source '" + source.name() + "'",
                                                    cause))
                     .flatMap(found -> refuseIfAny(found, source, name));
    }

    private static Result<Unit> refuseIfAny(List<InstanceInfo> found, SourceProfile source, ClusterName name) {
        return refuseIf(!found.isEmpty(),
                        name,
                        found.size()
                       + " cloud instance(s) labelled " + CLUSTER_LABEL
                       + "=" + name
                       + " already exist in source '" + source.name()
                       + "'");
    }

    private static Result<Unit> refuseIf(boolean exists, ClusterName name, String matched) {
        return exists
               ? alreadyExists(name, matched).result()
               : Result.unitResult();
    }

    private static Cause alreadyExists(ClusterName name, String matched) {
        return new ClusterConfigError.ClusterAlreadyExists(name.value(), matched);
    }

    private static Cause unconfirmed(ClusterName name, String check, Cause cause) {
        return new BootstrapError.ExistingClusterCheckFailed(name.value(), check, cause);
    }

    /// The provider lookup is the seam: a test hands in a fake provider and reads the label map this guard sends.
    static Fn2<Result<List<InstanceInfo>>, SourceProfile, ClusterName> listingVia(Fn1<Result<ComputeProvider>, SourceProfile> providers) {
        return (source, name) -> listLabelledInstances(providers, source, name);
    }

    /// One page is enough: this asks only whether ANY instance carries the label, so the first page of the
    /// provider's label-filtered listing answers it (a non-empty page is a refusal, however many more there are).
    @TerminalOperation
    private static Result<List<InstanceInfo>> listLabelledInstances(Fn1<Result<ComputeProvider>, SourceProfile> providers,
                                                                    SourceProfile source,
                                                                    ClusterName name) {
        return providers.apply(source)
                        .flatMap(compute -> compute.listInstances(Map.of(CLUSTER_LABEL,
                                                                         name.value()))
                                                   .await(timeSpan(LIST_TIMEOUT_SECONDS).seconds()));
    }
}
