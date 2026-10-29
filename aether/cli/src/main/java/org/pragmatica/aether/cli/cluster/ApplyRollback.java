// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.success;


/// `aether cluster apply --rollback` (cluster-apply spec 4.5): terminates the VMs the ledger recorded, newest first.
///
/// - Every VM is looked up first and must carry BOTH `aether-cluster=<c>` and `aether-apply=<applyId>`; one that exists
///   under other labels is refused and named (the #572 incident: never delete what this apply did not label).
/// - Absence is decided by LISTING, before the terminate (not terminating what is gone) and after a failed one (a
///   failed terminate of something that is gone is a TERMINATED row). Never by the shape of a terminate error: Hetzner
///   answers 404, Azure/GCP/Docker a generic failure, and AWS is unread.
/// - A node that joined the cluster is drained first, bounded; an unreachable cluster is said and the terminate goes
///   ahead, because a billed VM leaks money every hour while a node being rolled back belongs to no desired config.
/// - A failed terminate on a present VM stays TERMINATE_FAILED, is named with its provider, server id and removal
///   command, and the ledger is kept so the next `--rollback` retries only those rows. A second rollback issues zero
///   terminate calls for rows already TERMINATED.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-PAT-01"})
final class ApplyRollback {
    private ApplyRollback() {}

    /// The cluster-side drain of a node that joined; a test replaces it.
    @FunctionalInterface
    interface Drain {
        Result<Unit> drain(String nodeId);
    }

    /// The outcome: the lines printed, and whether every recorded VM is now gone.
    record Report(List<String> lines, boolean complete) {}

    static Result<Report> rollback(ApplyRecorder recorder,
                                   ClusterName cluster,
                                   Function<String, Result<ComputeProvider>> computeFor,
                                   Drain drain,
                                   Consumer<String> out) {
        return ApplyLabels.requireSafe(cluster,
                                       recorder.applyId())
                          .flatMap(_ -> ApplyReconciler.audit(recorder, cluster, computeFor))
                          .flatMap(audit -> recorder.phase(ApplyPhase.ROLLING_BACK)
                                                    .map(_ -> audit))
                          .map(audit -> terminateAll(recorder, cluster, computeFor, drain, out, audit));
    }

    private static Report terminateAll(ApplyRecorder recorder,
                                       ClusterName cluster,
                                       Function<String, Result<ComputeProvider>> computeFor,
                                       Drain drain,
                                       Consumer<String> out,
                                       ApplyReconciler.AuditReport audit) {
        var lines = new ArrayList<String>();
        var holding = new ArrayList<>(recorder.snapshot()
                                              .resources()
                                              .stream()
                                              .filter(row -> row.state()
                                                                .holdsVm())
                                              .toList());

        audit.adopted()
             .forEach(item -> note(lines,
                                   out,
                                   "adopted before rolling back (found under the apply label, not in the ledger): " + item));
        audit.gone().forEach(item -> note(lines, out, "already gone from the provider: " + item));
        audit.relabelled()
             .forEach(item -> note(lines,
                                   out,
                                   "no longer under the apply label (relabelled or foreign), checking before touching it: " + item));
        java.util.Collections.reverse(holding);
        var complete = true;

        for (var row : holding) {
            if (!terminateRow(recorder, cluster, computeFor, drain, row, lines, out)) {
                complete = false;
            }
        }

        recorder.snapshot()
                .destroyedNodeIds()
                .forEach(id -> note(lines,
                                    out,
                                    "NOT restored (removed by the apply, rollback does not recreate it): " + id));

        return new Report(List.copyOf(lines),
                          complete && recorder.snapshot().resources().stream().noneMatch(row -> row.state()
                                                                                                   .holdsVm()));
    }

    /// True when the row's VM is gone afterwards.
    private static boolean terminateRow(ApplyRecorder recorder,
                                        ClusterName cluster,
                                        Function<String, Result<ComputeProvider>> computeFor,
                                        Drain drain,
                                        ApplyResource row,
                                        List<String> lines,
                                        Consumer<String> out) {
        var serverId = row.serverId().or("");

        if (serverId.isEmpty()) {
            return keep(lines, out, row, "the ledger recorded no server id");
        }

        return computeFor.apply(row.provider())
                         .fold(cause -> keep(lines,
                                             out,
                                             row,
                                             "provider unavailable: " + cause.message()),
                               compute -> terminateWith(recorder, cluster, compute, drain, row, lines, out));
    }

    private static boolean terminateWith(ApplyRecorder recorder,
                                         ClusterName cluster,
                                         ComputeProvider compute,
                                         Drain drain,
                                         ApplyResource row,
                                         List<String> lines,
                                         Consumer<String> out) {
        var serverId = row.serverId().or("");

        return switch (presenceOf(compute, row.provider(), cluster, recorder.applyId(), serverId)) {
            case UNKNOWN -> keep(lines, out, row, "the provider could not be listed, so nothing was terminated");
            case ABSENT -> markGone(recorder, row, lines, out);
            case FOREIGN -> keep(lines,
                                 out,
                                 row,
                                 "server " + serverId + " exists but does not carry aether-cluster=" + cluster.value() + " and aether-apply=" + recorder.applyId() + "; it was NOT terminated (not this apply's)");
            case OURS -> terminateOurs(recorder, cluster, compute, drain, row, lines, out);
        };
    }

    private enum Presence {
        OURS,
        FOREIGN,
        ABSENT,
        UNKNOWN
    }

    private static Presence presenceOf(ComputeProvider compute,
                                       String provider,
                                       ClusterName cluster,
                                       String applyId,
                                       String serverId) {
        var ours = compute.listInstances(Map.of("aether-cluster",
                                                cluster.value(),
                                                WaveNodeProvisioning.APPLY_LABEL,
                                                applyId))
                          .await();

        if (ours.isFailure()) {
            return Presence.UNKNOWN;
        }
        // Exact id equality, then the labels as the provider REPORTS them: a selector the provider ignored or got wrong
        // never makes a server "ours". A failed listing is UNKNOWN, never an empty list.
        if (ours.or(List.<InstanceInfo> of())
                .stream()
                .anyMatch(info -> info.id()
                                      .value()
                                      .equals(serverId) && WaveNodeProvisioning.ownedBy(provider,
                                                                                        info,
                                                                                        cluster.value(),
                                                                                        applyId))) {
            return Presence.OURS;
        }

        return compute.listInstances()
                      .await()
                      .fold(_ -> Presence.UNKNOWN,
                            all -> all.stream()
                                      .anyMatch(info -> info.id()
                                                            .value()
                                                            .equals(serverId))
                                   ? Presence.FOREIGN
                                   : Presence.ABSENT);
    }

    private static boolean terminateOurs(ApplyRecorder recorder,
                                         ClusterName cluster,
                                         ComputeProvider compute,
                                         Drain drain,
                                         ApplyResource row,
                                         List<String> lines,
                                         Consumer<String> out) {
        var serverId = row.serverId().or("");

        if (row.state() == ApplyResource.State.JOINED) {
            drain.drain(row.nodeId())
                 .onFailure(cause -> note(lines,
                                          out,
                                          "could not drain " + row.nodeId()
                                         + " first (" + cause.message()
                                         + "); terminating anyway: it may hold slices it did not hand off"));
        }

        if (recorder.terminating(row.nodeId()).isFailure()) {
            return keep(lines, out, row, "the TERMINATING row could not be written, so nothing was terminated");
        }
        // The labels are checked again right before the call: the drain and the TERMINATING write took time, and a server that
        // is no longer verifiably this apply's, or whose listing failed, is never terminated.
        return switch (presenceOf(compute, row.provider(), cluster, recorder.applyId(), serverId)) {
            case OURS -> issueTerminate(recorder, cluster, compute, row, lines, out);
            case ABSENT -> markGone(recorder, row, lines, out);
            case FOREIGN, UNKNOWN -> keep(lines,
                                          out,
                                          row,
                                          "right before the terminate it was not verifiably this apply's (labels or listing); nothing was terminated");
        };
    }

    private static boolean issueTerminate(ApplyRecorder recorder,
                                          ClusterName cluster,
                                          ComputeProvider compute,
                                          ApplyResource row,
                                          List<String> lines,
                                          Consumer<String> out) {
        var serverId = row.serverId().or("");
        var terminated = InstanceId.instanceId(serverId).async().flatMap(compute::terminate).await();

        if (terminated.isSuccess() || presenceOf(compute, row.provider(), cluster, recorder.applyId(), serverId) == Presence.ABSENT) {
            note(lines, out, "terminated node " + row.nodeId() + " (server " + serverId + ")");

            return recorder.terminated(row.nodeId())
                           .isSuccess();
        }
        // FER: the terminate failure is the news and is reported below; a failed TERMINATE_FAILED write leaves the row at
        // TERMINATING, which holds a VM in every reading of the ledger, so the next --rollback retries it either way.
        recorder.terminateFailed(row.nodeId()).or(Unit.unit());
        note(lines,
             out,
             "STILL RUNNING AND BILLED: node " + row.nodeId()
            + "  provider " + row.provider()
            + "  server " + serverId
            + " (terminate failed: " + terminated.fold(Cause::message, _ -> "")
            + ")\n      remove: " + removal(row.provider(), serverId));

        return false;
    }

    private static boolean markGone(ApplyRecorder recorder,
                                    ApplyResource row,
                                    List<String> lines,
                                    Consumer<String> out) {
        note(lines, out, "node " + row.nodeId() + " is no longer at the provider; nothing to terminate");

        return recorder.gone(row.nodeId())
                       .isSuccess();
    }

    private static boolean keep(List<String> lines, Consumer<String> out, ApplyResource row, String why) {
        note(lines,
             out,
             "KEPT node " + row.nodeId()
            + " (provider " + row.provider()
            + ", server " + row.serverId().or("?")
            + "): " + why);

        return false;
    }

    static String removal(String provider, String serverId) {
        return switch (provider) {
            case "hetzner" -> "hcloud server delete " + serverId;
            case "docker" -> "docker rm -f " + serverId;
            default -> "delete instance " + serverId + " in the " + provider + " console";
        };
    }

    @Contract
    private static void note(List<String> lines, Consumer<String> out, String line) {
        lines.add(line);
        out.accept(line);
    }

    /// The rollback ended with VMs still standing: the ledger is kept and the exit is non-zero.
    record Incomplete(Report report) implements Cause {
        @Override
        public String message() {
            return "Rollback incomplete; the ledger was kept so the next --rollback retries only what is left:\n  " + String.join("\n  ",
                                                                                                                                  report.lines());
        }
    }

    static Result<Report> orIncomplete(Result<Report> outcome) {
        return outcome.flatMap(report -> report.complete()
                                         ? success(report)
                                         : new Incomplete(report).result());
    }
}
