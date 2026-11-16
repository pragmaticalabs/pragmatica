// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Snapshot;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.aether.deployment.cluster.fsm2.TimerId.Kind;
import org.pragmatica.cluster.state.kvstore.LeaderValue;


/// The Active entry and the housekeeping that belongs to no single key family: `Rebuild` (C1), the leader mirror and the
/// witness exit (3.2, C23), the seam refresh (C11e) and the timers (C21).
public sealed interface Lifecycle {
    // -- Rebuild (C1) ----------------------------------------------------------------------------------------------
    /// Build every index from the committed mirror, once (R-3.3.1, R-7.3.1: the only full scan). `absentSince` and
    /// `announcementSeenAt` start **empty** (R-2.14c, R-2.15), `joined` starts **empty** (R-2.11, D-4 (ii): the seed never
    /// supersedes a committed DEPARTED), `inherited` is seeded from the snapshot's non-STALE DEPARTED records (H-1), and
    /// `incarnationStart` is taken from the monotonic clock. The `coreView` of the snapshot only starts the liveness clocks
    /// (R-2.11 (b)). Then schedule the two timers (NT-07) and queue the first reconcile; strays, intake re-drive and departed rows are
    /// handled by that reconcile. A family warning the rebuild raises after `inherit` (a schema hold re-raised at activation) is held until
    /// its WARNINGS row commits, as a step's is (#3085, `Warnings.noteRaised`); the recoveries `inherit` raises stay immediate.
    ///
    /// ```tla Fsm2Leader:Activate
    /// \* CI-1 Activate, then Rebuild(Snapshot) in the same dispatch: ClusterFsm.java; Lifecycle.java.
    /// \* code, pre3085: rows from the snapshot (Indexer), Warnings.inherit (Warnings.java) seeds the family sets and marks dirty.
    /// \* pre2834: Lifecycle.rebuild has no inherit; family sets start empty.
    /// Activate(n) ==
    ///   /\ fsm[n] = "Dormant" /\ (kvLeader = n \/ MUT = "twoleaders")   \* the mutant names the node itself as leader, whatever is committed
    ///   /\ LET w == IF Persists /\ MUT # "noinherit" THEN kv ELSE {}
    ///      IN /\ fsm' = [fsm EXCEPT ![n] = "Active"]
    ///         /\ inc' = [inc EXCEPT ![n] = inc[n] + 1]
    ///         /\ view' = [view EXCEPT ![n] = IF MUT = "twoleaders" THEN <<n, term>> ELSE Current]
    ///         /\ warned' = [warned EXCEPT ![n] = w]
    ///         /\ rows' = [rows EXCEPT ![n] = IF Persists THEN kv ELSE {}]
    ///         /\ dirty' = [dirty EXCEPT ![n] = Persists]
    ///         /\ flushQ' = [flushQ EXCEPT ![n] = FALSE]
    ///         /\ wInfl' = [wInfl EXCEPT ![n] = FALSE]
    ///         /\ gInfl' = [gInfl EXCEPT ![n] = FALSE]
    ///         /\ gIntent' = [gIntent EXCEPT ![n] = FALSE]
    ///         /\ fired' = [fired EXCEPT ![n] = {}]
    ///         /\ held' = [held EXCEPT ![n] = <<>>]
    ///         /\ redo' = [redo EXCEPT ![n] = {}]
    ///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, cond, shown, net, settles, condBudget,
    ///                  faultBudget>>
    /// ```
    static ClusterModel rebuild(Ctx ctx, long incarnation, Snapshot snapshot) {
        var m = new ClusterModel();

        m.incarnation = incarnation;
        m.incarnationStart = ctx.monoNs();
        m.leader = snapshot.leader();
        m.seams = snapshot.seams();
        snapshot.admissions().forEach((node, admission) -> m.admissions = m.admissions.put(node, admission));
        snapshot.entries().forEach(entry -> Indexer.indexPut(m, ctx, entry.key(), entry.value()));
        Warnings.inherit(m, ctx);
        var from = ctx.effectCount();

        m.inherited = inheritedAtRebuild(m);
        m.announcementSeenAt = PMap.empty(m.scans);
        m.absentSince = PMap.empty(m.scans);
        Liveness.refresh(m, ctx);
        m.auditDue = true;
        schedule(m,
                 ctx,
                 TimerId.timerId(Kind.POST_ACTIVATION),
                 ctx.settings().timing().postActivation());
        schedule(m,
                 ctx,
                 TimerId.timerId(Kind.TICK),
                 ctx.settings().timing().tick());
        Schemas.resumeMigrations(m, ctx);
        Slices.activateLoadedAtRebuild(m, ctx);
        Reconcile.request(m, ctx);

        return Warnings.noteRaised(m, ctx, from);
    }

    /// C17r (#2937): one synthesised removal input per id in local membership's current DEAD set, `NodeRemoved` for a core and
    /// `WorkerLeave` for a worker, in id order (a deterministic `Rebuild`). The FSM applies them right after `Rebuild`, before
    /// the inputs held while rebuilding, so each counts as a removal input processed after `Rebuild` (W4, R-2.11). They run C17
    /// unchanged: its guard (n not in `departed` or `departing`) refuses a node already recorded, and the R-4.3.0 fence refuses
    /// a re-admission that commits first. A verdict this node never reached is left to C9-C11. Draft 8.1a guard (NT-169): only
    /// an id with residue in the rebuilt model is synthesised, because membership keeps DEAD ids forever while R-4.3.3 removes
    /// the DEPARTED record with the last residue; a ghost id would otherwise get a fresh DEPARTED on every `Rebuild`.
    static List<ClusterInput> removalsAtRebuild(ClusterModel m, Snapshot snapshot) {
        return Stream.concat(synthesised(m,
                                         snapshot.dead().cores(),
                                         ClusterInput.NodeRemoved::new),
                             synthesised(m,
                                         snapshot.dead().workers(),
                                         ClusterInput.WorkerLeave::new))
                     .toList();
    }

    private static Stream<ClusterInput> synthesised(ClusterModel m,
                                                    Set<NodeId> nodes,
                                                    Function<NodeId, ClusterInput> removal) {
        return nodes.stream()
                    .filter(node -> hasResidue(m, node))
                    .sorted(Comparator.comparing(NodeId::id))
                    .map(removal);
    }

    /// C17r residue (draft 8.1a): a committed `NodeDepartureKey(n)` record, a directive, or desired or observed rows.
    private static boolean hasResidue(ClusterModel m, NodeId node) {
        return m.records.containsKey(node) || m.directives.containsKey(node) || m.desiredByNode.containsKey(node) || m.observedByNode.containsKey(node);
    }

    /// H-1: C11f's non-STALE extension applies to the DEPARTED records that were already committed when this leader rebuilt.
    private static PSet<NodeId> inheritedAtRebuild(ClusterModel m) {
        var inherited = PSet.<NodeId> empty(m.scans);

        for (var entry : m.records.entries()) {
            if (Departures.isDeparted(entry.getValue()) && !isStale(entry.getValue())) {
                inherited = inherited.add(entry.getKey());
            }
        }

        return inherited;
    }

    private static boolean isStale(NodeDepartureValue record) {
        return record.cause()
                     .map(DepartureCause.STALE::equals)
                     .or(false);
    }

    // -- the leader mirror and the witness (3.2, R-5.7.1 (a), C23) ---------------------------------------------------
    /// CI-16. Naming this node after a stretch of not naming it: resume, re-deriving the intents that were dropped.
    /// Naming another node: stay suspended (the CI-1 `Deactivate` follows). While suspended nothing is submitted.
    ///
    /// ```tla Fsm2Leader:SeeLeader
    /// \* CI-16 LeaderChange input: Lifecycle.java onLeader (a resume requests a reconcile; Tick models it).
    /// SeeLeader(n) ==
    ///   /\ fsm[n] = "Active" /\ view[n] # Current
    ///   /\ view' = [view EXCEPT ![n] = Current]
    ///   /\ Apply(n, warned[n], rows[n], dirty[n], wInfl[n], gInfl[n], gIntent[n], FALSE, flushQ[n], shown, held[n])
    ///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, cond, fsm, inc, fired, net, settles,
    ///                  condBudget, faultBudget, redo>>
    /// ```
    static ClusterModel onLeader(ClusterModel m, Ctx ctx, LeaderValue value) {
        var wasSuspended = isSuspended(m, ctx);

        m.leader = Option.some(value);
        if (wasSuspended && !isSuspended(m, ctx)) {
            Schemas.resetStalledMigrations(m, ctx);
            Schemas.announceWithheld(m, ctx);
            Deadlines.rearm(m);
            m.joinWarningsDue = true;
            Reconcile.request(m, ctx);
        }

        return m;
    }

    /// The interpreter's self-check found the committed leader does not name this node (R-5.7.1 (a)).
    static ClusterModel onWitnessStale(ClusterModel m) {
        m.leader = Option.none();

        return m;
    }

    /// R-5.7.1 (a): the FSM submits only while the committed leader value names this node.
    static boolean isSuspended(ClusterModel m, Ctx ctx) {
        return ! m.leader.map(value -> value.leader()
                                            .equals(ctx.self()))
                         .or(false);
    }

    // -- seams (CI-15, CI-15c, C11e) -------------------------------------------------------------------------------
    /// A fresh sample of the read seams. The counting set may have changed, and so may every absence judgment.
    static ClusterModel onSeams(ClusterModel m, Ctx ctx, Seams seams) {
        var before = m.seams;

        m.seams = seams;
        m.sampledAfterRebuild = true;
        Liveness.refresh(m, ctx);
        Absence.refreshAll(m, ctx);
        if (!before.countedCores().equals(seams.countedCores()) || !before.ready().equals(seams.ready()) || !before.draining()
                                                                                                                   .equals(seams.draining())) {
            Reconcile.request(m, ctx);
        }

        return m;
    }

    // -- timers (C21) ----------------------------------------------------------------------------------------------
    /// Arm a timer of this incarnation (the interpreter's injected scheduler fires it back).
    static ClusterModel schedule(ClusterModel m, Ctx ctx, TimerId timer, long delayMs) {
        m.timers = m.timers.add(timer);
        ctx.emit(new ClusterEffect.Schedule(timer, m.incarnation, delayMs));

        return m;
    }

    static ClusterModel cancel(ClusterModel m, Ctx ctx, TimerId timer) {
        if (m.timers.contains(timer)) {
            m.timers = m.timers.remove(timer);
            ctx.emit(new ClusterEffect.Cancel(timer));
        }

        return m;
    }

    static ClusterModel onTimer(ClusterModel m, Ctx ctx, TimerId timer) {
        m.timers = m.timers.remove(timer);

        return switch (timer.kind()) {
            case TICK -> tick(m, ctx);
            case POST_ACTIVATION, RECONCILE -> Reconcile.request(m, ctx);
            case DEPARTING_REREAD -> DepartureFlow.reread(m, ctx, timer.subject());
            case DRAIN_STEP -> Drains.step(m, ctx, timer.subject());
        };
    }

    /// The 30 s tick: sample-driven work (completion repair, record GC, pending joins) rides the reconcile it queues.
    private static ClusterModel tick(ClusterModel m, Ctx ctx) {
        m.tickCount = m.tickCount + 1;
        m.joinWarningsDue = true;
        Liveness.refresh(m, ctx);
        m.auditDue = m.auditDue || m.tickCount % Orphans.AUDIT_EVERY_TICKS == 0;
        schedule(m,
                 ctx,
                 TimerId.timerId(Kind.TICK),
                 ctx.settings().timing().tick());

        return Reconcile.request(m, ctx);
    }

    record unused() implements Lifecycle {}
}
