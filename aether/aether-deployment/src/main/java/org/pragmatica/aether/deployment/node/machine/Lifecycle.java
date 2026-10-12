// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.SliceNotInStore;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// The outer states (spec 3.4): Dormant to Active on a quorum, with the Rebuild of R-3.4.1; Active to Dormant on a genuine
/// quorum loss, suspending what serves without a KV write.
sealed interface Lifecycle {
    String BOOT_TOKEN_ABSENT = "boot-token-absent";

    /// Dormant to Active: a new incarnation, the snapshot taken as the model, then the Rebuild in the spec's order:
    /// 1 self-ready, 2 drop stale deferrals, 3 N0, 4 reconcile every own pair, 5 reactivate the suspended.
    static Transition established(NodeFsm fsm, NodeFsmModel model, RebuildSnapshot snapshot) {
        var entered = model.withActive(true,
                                       model.incarnation() + 1L)
                           .withSnapshot(snapshot)
                           .withInstances(seed(model, snapshot));
        var ready = Transition.transition(entered).emit(NodeFsmEffect.selfReady());
        var pruned = dropStaleDeferrals(ready);
        var swept = staleRowSweep(fsm, pruned);

        return reactivateSuspended(fsm, reconcileAll(fsm, swept));
    }

    /// The records of the new incarnation: desired and observed from the snapshot; what survives a quorum cycle (a park, a
    /// suspension, the serving flag) from the old records.
    private static Map<Artifact, InstanceRecord> seed(NodeFsmModel model, RebuildSnapshot snapshot) {
        var keys = new HashSet<Artifact>(snapshot.desired().keySet());

        keys.addAll(snapshot.observed().keySet());
        keys.addAll(model.instances().keySet());

        return keys.stream()
                   .collect(Collectors.toMap(artifact -> artifact,
                                             artifact -> seeded(model.instance(artifact),
                                                                snapshot,
                                                                artifact)));
    }

    private static InstanceRecord seeded(InstanceRecord old, RebuildSnapshot snapshot, Artifact artifact) {
        var observed = option(snapshot.observed().get(artifact));

        return InstanceRecord.instanceRecord()
                             .withDesired(option(snapshot.desired().get(artifact)))
                             .withObserved(observed)
                             .withLastVersion(observed.map(SliceObservedValue::version).or(0L))
                             .withParked(old.parked())
                             .withSuspended(old.suspended())
                             .withServing(old.serving());
    }

    private static List<Artifact> sorted(Transition t) {
        return t.model()
                .instances()
                .keySet()
                .stream()
                .sorted(Comparator.comparing(Artifact::asString))
                .toList();
    }

    /// Step 2: a deferral whose goal is gone or changed is dropped before anything is re-driven.
    private static Transition dropStaleDeferrals(Transition t) {
        var stale = sorted(t).stream().filter(artifact -> isStaleDeferral(t.instance(artifact))).toList();

        return t.each(stale,
                      (current, artifact) -> Planner.unpark(current,
                                                            artifact,
                                                            "the entry no longer holds the parked goal"));
    }

    private static boolean isStaleDeferral(InstanceRecord rec) {
        return rec.parked()
                  .filter(parked -> !asksFor(rec.desired(),
                                             parked))
                  .isPresent();
    }

    private static boolean asksFor(Option<SliceDesiredValue> desired, Parked parked) {
        return desired.filter(value -> value.goal() == parked.goal() && value.generation()
                                                                             .equals(parked.generation()))
                      .isPresent();
    }

    // ---------------------------------------------------------------- N0
    /// Step 3, N0: the own-row incarnation check (spec R-3.4.1). A node without a boot token cannot tell processes apart:
    /// the sweep is skipped and `boot-token-absent` is raised once.
    private static Transition staleRowSweep(NodeFsm fsm, Transition t) {
        var announced = fsm.config().selfToken() == 0L
                        ? t.emit(tokenAbsent(fsm))
                        : t;

        return announced.each(sorted(announced), (current, artifact) -> sweepRow(fsm, current, artifact));
    }

    private static NodeFsmEffect tokenAbsent(NodeFsm fsm) {
        var self = fsm.config().self().id();

        return new NodeFsmEffect.Warn(BOOT_TOKEN_ABSENT,
                                      self,
                                      "Node " + self
                                     + " runs without a boot token: own rows cannot be told from another process's");
    }

    private static Transition sweepRow(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);

        return rec.observed()
                  .fold(() -> t,
                        row -> sweepOne(fsm, t, artifact, rec, row));
    }

    private static Transition sweepOne(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       InstanceRecord rec,
                                       SliceObservedValue row) {
        return fsm.config()
                  .isStale(row.incarnation().bootToken())
               ? staleRow(fsm, t, artifact, rec, row)
               : t.update(artifact,
                          r -> r.withAppliedGeneration(some(row.forGeneration()))
                                .withLastVersion(row.version()));
    }

    /// The row belongs to another process and both tokens are known: its version chain is continued, never trusted
    /// (nothing is corroborated), and never removed. With a goal the ordinary N1 / N4b chain overwrites it as LOADING; with no
    /// goal, or UNLOADED, it is overwritten with the tombstone.
    private static Transition staleRow(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       InstanceRecord rec,
                                       SliceObservedValue row) {
        var counted = t.update(artifact,
                               r -> r.withAppliedGeneration(none())
                                     .withLastVersion(row.version()))
                       .emit(staleMetric())
                       .emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN,
                                                   staleLine(fsm, artifact, row)));

        return rec.desired()
                  .filter(desired -> desired.goal() != SliceGoal.UNLOADED)
                  .fold(() -> overwriteWithTombstone(fsm, counted, artifact, rec, row),
                        _ -> counted);
    }

    private static NodeFsmEffect staleMetric() {
        return new NodeFsmEffect.Metric("aether_deployment_fsm_stale_rows_total",
                                        Map.of("fsm", "node", "side", "node"),
                                        1L);
    }

    private static String staleLine(NodeFsm fsm, Artifact artifact, SliceObservedValue row) {
        return "Node " + fsm.config()
                            .self()
                            .id()
             + " found a row of another process for " + artifact
             + ": " + row.state()
             + " at version " + row.version()
             + " (boot token " + row.incarnation()
                                    .bootToken()
             + ", own " + fsm.config()
                             .selfToken()
             + "); overwriting it";
    }

    private static Transition overwriteWithTombstone(NodeFsm fsm,
                                                     Transition t,
                                                     Artifact artifact,
                                                     InstanceRecord rec,
                                                     SliceObservedValue row) {
        var generation = rec.desired().map(SliceDesiredValue::generation).or(row.forGeneration());

        return Chains.start(fsm,
                            t,
                            artifact,
                            Chain.ChainKind.SINGLE_WRITE,
                            generation,
                            List.of(Chain.write(SliceObservedState.UNLOADED)));
    }

    // ---------------------------------------------------------------- steps 4 and 5
    private static Transition reconcileAll(NodeFsm fsm, Transition t) {
        return t.each(sorted(t), (current, artifact) -> reconcileEntry(fsm, current, artifact));
    }

    /// A suspended instance whose goal is still ACTIVE waits for step 5; any other pair is reconciled now, cause "entry".
    private static Transition reconcileEntry(NodeFsm fsm, Transition t, Artifact artifact) {
        return staysActive(t.instance(artifact))
               ? t
               : Planner.reconcile(fsm,
                                   t.update(artifact, r -> r.withSuspended(false)),
                                   artifact);
    }

    private static boolean staysActive(InstanceRecord rec) {
        return rec.suspended()
               && rec.hasGoal(SliceGoal.ACTIVE)
               && rec.hasRow(SliceObservedState.ACTIVE);
    }

    /// R-3.4.7: the same process, so the rows stay current: re-register, re-publish, write ACTIVE through the N4 final batch
    /// without ACQUIRE. A slice that left the store is a retryable FAILED (B-110).
    private static Transition reactivateSuspended(NodeFsm fsm, Transition t) {
        var suspended = sorted(t).stream().filter(artifact -> t.instance(artifact)
                                                               .suspended()).toList();

        return t.each(suspended, (current, artifact) -> reactivate(fsm, current, artifact));
    }

    private static Transition reactivate(NodeFsm fsm, Transition t, Artifact artifact) {
        var cleared = t.update(artifact, rec -> rec.withSuspended(false));

        return cleared.instance(artifact)
                      .desired()
                      .fold(() -> cleared,
                            desired -> reactivateGoal(fsm, cleared, artifact, desired));
    }

    private static Transition reactivateGoal(NodeFsm fsm, Transition t, Artifact artifact, SliceDesiredValue desired) {
        return t.model()
                .isLoaded(artifact)
               ? Chains.start(fsm,
                              t,
                              artifact,
                              Chain.ChainKind.REACTIVATE,
                              desired.generation(),
                              Plans.reactivateSteps())
               : Chains.failWith(fsm, t, artifact, sliceGone(artifact, desired));
    }

    private static FailureNote sliceGone(Artifact artifact, SliceDesiredValue desired) {
        var cause = SliceNotInStore.sliceNotInStore(artifact.asString(), "reactivation after quorum restore");

        return FailureNote.failureNote(cause.message(), false, desired.generation());
    }

    // ---------------------------------------------------------------- Active to Dormant
    /// A genuine quorum loss (a demotion is not one, `9454522a0`): serving instances are unpublished with no KV write and
    /// carried as suspended; every other chain and write is dropped; parked starts are kept. Timers and steps of this
    /// incarnation die with it (R-3.1.3).
    static Transition disappeared(NodeFsm fsm, NodeFsmModel model) {
        var outcomes = model.instances()
                            .keySet()
                            .stream()
                            .sorted(Comparator.comparing(Artifact::asString))
                            .map(artifact -> suspension(fsm, model, artifact))
                            .toList();
        var instances = outcomes.stream().collect(Collectors.toMap(Suspension::artifact, Suspension::record));
        var ops = outcomes.stream().flatMap(outcome -> outcome.ops()
                                                              .stream()).toList();

        return Transition.transition(model.withActive(false, model.incarnation()).withInstances(instances)).emitAll(ops);
    }

    /// What a quorum loss does to one instance.
    record Suspension(Artifact artifact, InstanceRecord record, List<NodeFsmEffect> ops) {
        static Suspension suspension(Artifact artifact, InstanceRecord record, List<NodeFsmEffect> ops) {
            return new Suspension(artifact, record, ops);
        }
    }

    private static Suspension suspension(NodeFsm fsm, NodeFsmModel model, Artifact artifact) {
        var rec = model.instance(artifact);
        var serves = isServing(fsm, rec);
        var ops = serves
                  ? List.<NodeFsmEffect> of(new NodeFsmEffect.LocalOp(model.incarnation(),
                                                                      0L,
                                                                      some(artifact),
                                                                      LocalStep.SUSPEND,
                                                                      0L,
                                                                      none()))
                  : List.<NodeFsmEffect> of();

        return Suspension.suspension(artifact, dropped(rec, serves), ops);
    }

    private static boolean isServing(NodeFsm fsm, InstanceRecord rec) {
        return rec.serving()
               && rec.chain()
                     .isEmpty()
               && Rows.current(fsm.config(),
                               rec)
                      .filter(row -> row.state() == SliceObservedState.ACTIVE)
                      .isPresent();
    }

    /// Only what survives the outage is kept: a park, the serving flag, a suspension. Chains, writes and owed failures are not
    /// carried; the next Rebuild seeds every key from the snapshot and reconciles it (spec 3.4: in-flight keys are reconciled).
    private static InstanceRecord dropped(InstanceRecord rec, boolean serves) {
        return rec.withSuspended(serves)
                  .withServing(rec.serving() && !serves);
    }

    record unused() implements Lifecycle {}
}
