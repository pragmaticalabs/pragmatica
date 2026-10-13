// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;

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
    /// handled by that reconcile.
    static ClusterModel rebuild(Ctx ctx, long incarnation, Snapshot snapshot) {
        var m = new ClusterModel();

        m.incarnation = incarnation;
        m.incarnationStart = ctx.monoNs();
        m.leader = snapshot.leader();
        m.seams = snapshot.seams();
        snapshot.admissions().forEach((node, admission) -> m.admissions = m.admissions.put(node, admission));
        snapshot.entries().forEach(entry -> Indexer.indexPut(m, ctx, entry.key(), entry.value()));
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

        return m;
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
    static ClusterModel onLeader(ClusterModel m, Ctx ctx, LeaderValue value) {
        var wasSuspended = isSuspended(m, ctx);

        m.leader = Option.some(value);
        if (wasSuspended && !isSuspended(m, ctx)) {
            Schemas.resetStalledMigrations(m, ctx);
            Schemas.announceWithheld(m, ctx);
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
