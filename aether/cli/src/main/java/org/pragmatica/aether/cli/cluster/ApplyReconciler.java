// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.success;


/// Reconciles an apply's ledger with what the providers hold (cluster-apply spec 4.3 step 3 and 4.4): the resume
/// lookup of every INTENDED row, and the apply-label audit.
///
/// Both are decided by LISTING the provider, never by the shape of an error. A lookup that fails or is inconclusive
/// refuses and creates nothing: guessing "not there" is how a node gets created twice.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-PAT-01"})
final class ApplyReconciler {
    /// Consecutive empty listings before an INTENDED row is declared ABSENT. The CTM's answer to the same question for a
    /// dropped create (`ABSENT_LISTINGS_TO_CONFIRM`, `FAILED_REAP_RETRIES`): a create killed mid-flight can complete
    /// server-side after the process died (a queued request, a pending provider action), so two reads seconds apart are
    /// not evidence. A precedent, not a proof; what makes a node that lands later safe is the pre-commit audit.
    static final int ABSENT_LISTINGS_TO_CONFIRM = 12;

    /// Spacing of those listings: a sixth of the provisioning timeout, the CTM's own spacing. 12 listings span
    /// 11 intervals, about twice the provisioning timeout.
    static final Duration LISTING_INTERVAL = Duration.ofNanos(AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT.nanos() / 6);

    /// The wait between two listings; a test replaces it.
    static volatile Consumer<Duration> pause = ApplyReconciler::sleep;

    private ApplyReconciler() {}

    /// Looks up every INTENDED row: found becomes CREATED, 12 empty listings become ABSENT, a failed listing refuses.
    static Result<Unit> reconcileIntents(ApplyRecorder recorder, Function<String, Result<ComputeProvider>> computeFor) {
        return ApplyLabels.requireSafe("apply id",
                                       recorder.applyId())
                          .flatMap(_ -> lookupIntended(recorder, computeFor));
    }

    private static Result<Unit> lookupIntended(ApplyRecorder recorder,
                                               Function<String, Result<ComputeProvider>> computeFor) {
        var intended = recorder.snapshot()
                               .resources()
                               .stream()
                               .filter(row -> row.state() == ApplyResource.State.INTENDED)
                               .toList();

        for (var row : intended) {
            var outcome = ApplyLabels.requireSafe("node id",
                                                  row.nodeId())
                                     .flatMap(_ -> computeFor.apply(row.provider()))
                                     .flatMap(compute -> lookupWindow(compute, row, recorder));

            if (outcome.isFailure()) {
                return outcome;
            }
        }

        return Result.unitResult();
    }

    private static Result<Unit> lookupWindow(ComputeProvider compute, ApplyResource row, ApplyRecorder recorder) {
        for (int listing = 1; listing <= ABSENT_LISTINGS_TO_CONFIRM; listing++) {
            var found = findNode(compute, row, recorder);

            if (found.isFailure()) {
                return found.mapError(cause -> new LookupFailed(row.nodeId(),
                                                                row.provider(),
                                                                cause))
                            .map(_ -> Unit.unit());
            }

            var present = found.or(Option.<InstanceInfo> none());

            if (present.isPresent()) {
                return present.map(info -> recorder.found(row.nodeId(),
                                                          info.id().value()))
                              .or(Result.unitResult());
            }

            if (listing < ABSENT_LISTINGS_TO_CONFIRM) {
                pause.accept(LISTING_INTERVAL);
            }
        }

        return recorder.absent(row.nodeId());
    }

    /// The node by its minted identity. Docker lists by the apply label (its listing filter takes labels, and the node
    /// identity is the container name); the cloud providers list by `aether-node-id`, which the provider stamps itself
    /// and so survives a custom label being dropped. A cloud server found by that label is the node only if it is OURS (G1-f): node
    /// ids are unique per cluster, but a label is not unique, so a server of another cluster or apply that carries the same
    /// `aether-node-id` is never adopted as the node's VM.
    private static Result<Option<InstanceInfo>> findNode(ComputeProvider compute, ApplyResource row, ApplyRecorder recorder) {
        var applyId = recorder.applyId();
        var filter = "docker".equals(row.provider())
                     ? Map.of(WaveNodeProvisioning.APPLY_LABEL, applyId)
                     : Map.of(WaveNodeProvisioning.NODE_LABEL, row.nodeId());

        return compute.listInstances(filter)
                      .await()
                      .map(listed -> listed.stream()
                                           .filter(info -> isNode(info,
                                                                  row.nodeId()))
                                           .filter(info -> isOurs(row.provider(), info, recorder))
                                           .findFirst()
                                           .map(Option::some)
                                           .orElse(Option.none()));
    }

    private static boolean isOurs(String provider, InstanceInfo info, ApplyRecorder recorder) {
        return "docker".equals(provider) || WaveNodeProvisioning.ownedBy(provider,
                                                                         info,
                                                                         recorder.snapshot().clusterName().value(),
                                                                         recorder.applyId());
    }

    private static boolean isNode(InstanceInfo info, String nodeId) {
        return info.nodeId()
                   .equals(Option.some(nodeId)) || nodeId.equals(info.tags().get(WaveNodeProvisioning.NODE_LABEL));
    }

    /// What the audit changed in the ledger.
    record AuditReport(List<String> adopted, List<String> gone, List<String> relabelled) {
        boolean clean() {
            return adopted.isEmpty()
                   && gone.isEmpty()
                   && relabelled.isEmpty();
        }
    }

    /// 4.4: lists `aether-cluster=<c>, aether-apply=<applyId>` at every provider the ledger uses and compares server ids
    /// with the ledger. A VM the ledger lacks is recorded ADOPTED and named; a ledger VM the provider no longer lists is
    /// GONE. A listing that fails refuses: an audit that could not look is not a clean audit.
    static Result<AuditReport> audit(ApplyRecorder recorder,
                                     ClusterName cluster,
                                     Function<String, Result<ComputeProvider>> computeFor) {
        return ApplyLabels.requireSafe(cluster,
                                       recorder.applyId())
                          .flatMap(_ -> auditProviders(recorder, cluster, computeFor));
    }

    private static Result<AuditReport> auditProviders(ApplyRecorder recorder,
                                                      ClusterName cluster,
                                                      Function<String, Result<ComputeProvider>> computeFor) {
        var adopted = new ArrayList<String>();
        var gone = new ArrayList<String>();
        var relabelled = new ArrayList<String>();

        for (var provider : providersOf(recorder.snapshot())) {
            var outcome = computeFor.apply(provider)
                                    .flatMap(compute -> auditProvider(compute,
                                                                      provider,
                                                                      cluster,
                                                                      recorder,
                                                                      adopted,
                                                                      gone,
                                                                      relabelled));

            if (outcome.isFailure()) {
                return outcome.map(_ -> new AuditReport(List.of(), List.of(), List.of()));
            }
        }

        return success(new AuditReport(List.copyOf(adopted), List.copyOf(gone), List.copyOf(relabelled)));
    }

    private static List<String> providersOf(ApplyState state) {
        return state.resources()
                    .stream()
                    .map(ApplyResource::provider)
                    .distinct()
                    .toList();
    }

    private static Result<Unit> auditProvider(ComputeProvider compute,
                                              String provider,
                                              ClusterName cluster,
                                              ApplyRecorder recorder,
                                              List<String> adopted,
                                              List<String> gone,
                                              List<String> relabelled) {
        return compute.listInstances(Map.of("aether-cluster",
                                            cluster.value(),
                                            WaveNodeProvisioning.APPLY_LABEL,
                                            recorder.applyId()))
                      .await()
                      .mapError(cause -> new AuditFailed(provider, cause))
                      .flatMap(listed -> reconcile(compute,
                                                   listed,
                                                   provider,
                                                   cluster,
                                                   recorder,
                                                   adopted,
                                                   gone,
                                                   relabelled));
    }

    private static Result<Unit> reconcile(ComputeProvider compute,
                                          List<InstanceInfo> listed,
                                          String provider,
                                          ClusterName cluster,
                                          ApplyRecorder recorder,
                                          List<String> adopted,
                                          List<String> gone,
                                          List<String> relabelled) {
        var state = recorder.snapshot();

        for (var info : listed) {
            var known = state.resources()
                             .stream()
                             .anyMatch(row -> row.serverId()
                                                 .map(id -> id.equals(info.id().value()))
                                                 .or(false));

            if (!known && !WaveNodeProvisioning.ownedBy(provider, info, cluster.value(), recorder.applyId())) {
                relabelled.add(info.id().value()
                              + " (listed under the apply selector but does not carry both labels, left alone)");
            } else if (!known) {
                var nodeId = info.nodeId()
                                 .orElse(() -> Option.option(info.tags().get(WaveNodeProvisioning.NODE_LABEL)))
                                 .or(info.id().value());
                var written = recorder.adopt(nodeId,
                                             provider,
                                             info.id().value());

                if (written.isFailure()) {
                    return written;
                }

                adopted.add(nodeId + " (server " + info.id().value() + ")");
            }
        }

        var missing = state.resources()
                           .stream()
                           .filter(row -> row.provider()
                                             .equals(provider)
                                          && row.state()
                                                .holdsVm()
                                          && row.serverId()
                                                .isPresent())
                           .filter(row -> listed.stream()
                                                .noneMatch(info -> info.id()
                                                                       .value()
                                                                       .equals(row.serverId().or(""))))
                           .toList();

        return missing.isEmpty()
               ? Result.unitResult()
               : compute.listInstances()
                        .await()
                        .mapError(cause -> new AuditFailed(provider, cause))
                        .flatMap(all -> markMissing(missing, all, recorder, gone, relabelled));
    }

    /// Not listed under the apply label is not the same as gone: a VM that still exists under other labels was relabelled or
    /// is foreign. It is left in the ledger and named, so a rollback refuses it by name instead of calling it gone.
    private static Result<Unit> markMissing(List<ApplyResource> missing,
                                            List<InstanceInfo> all,
                                            ApplyRecorder recorder,
                                            List<String> gone,
                                            List<String> relabelled) {
        for (var row : missing) {
            var stillThere = all.stream().anyMatch(info -> info.id()
                                                               .value()
                                                               .equals(row.serverId().or("")));

            if (stillThere) {
                relabelled.add(row.nodeId() + " (server " + row.serverId().or("") + ")");
            } else {
                var written = recorder.gone(row.nodeId());

                if (written.isFailure()) {
                    return written;
                }

                gone.add(row.nodeId());
            }
        }

        return Result.unitResult();
    }

    @Contract
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The provider could not be listed for a node the ledger intended: nothing is created, nothing is guessed.
    record LookupFailed(String nodeId, String provider, Cause origin) implements Cause.Wrapped {
        @Override
        public String message() {
            return "Could not look up node " + nodeId
                 + " at provider " + provider
                 + ": " + origin.message()
                 + ". Nothing was created; run --resume again when the provider is reachable.";
        }
    }

    record AuditFailed(String provider, Cause origin) implements Cause.Wrapped {
        @Override
        public String message() {
            return "Could not audit the apply label at provider " + provider
                 + ": " + origin.message()
                 + ". The audit did not run, so the apply is not treated as clean.";
        }
    }
}
