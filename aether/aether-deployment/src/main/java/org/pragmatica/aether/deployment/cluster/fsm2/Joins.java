// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.PendingJoin;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.deployment.cluster.fsm2.TimerId.Kind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Admission (spec C15, C16, C11d and the join table J0 to J9): a core joins (`NodeJoined`) or a worker joins
/// (`WorkerJoin`), and the FSM records membership's admission, fenced against any departure in flight.
///
/// Admission is membership's decision (C5); the FSM keeps no refusal of its own for cores. Every admission write is a
/// `LeaderTransaction` that **expects the record value it observed** (R-4.3.0), so a racing departure and a re-admission are
/// ordered by the applier and the loser is refused as a whole. Rows are evaluated in order and the first match wins.
public sealed interface Joins {
    String WORKER_COMMUNITY_SUFFIX = "-w-0";
    String DEFAULT_SOURCE = "default";

    // -- C15 / C16 -------------------------------------------------------------------------------------------------
    /// A join input: update `admissions[n]`, re-classify n's rows (R-3.3.1), clear `absentSince[n]`, then evaluate the table.
    static ClusterModel onJoin(ClusterModel m, Ctx ctx, NodeId node, Admission admission, boolean worker) {
        var before = staleRows(m, node);

        m.admissions = m.admissions.put(node, admission);
        m.admittedAt = m.admittedAt.put(node, ctx.monoNs());
        Absence.clear(m, node);
        Indexer.refreshDeparted(m, node);
        Indexer.refreshNodeRows(m, node);
        noteStale(m, ctx, node, before);

        return evaluate(m, ctx, node, new PendingJoin(worker, admission));
    }

    /// C11d / CI-15b: the seam reports a token for `node` that it reported as unknown before. Re-classify its rows; nothing
    /// is written (C8). A row that turns stale restarts its clock, so learning a token cannot retract a pair at once.
    static ClusterModel onProcessKnown(ClusterModel m, Ctx ctx, NodeId node, long token) {
        var before = staleRows(m, node);
        var known = m.admissions.get(node).or(Admission.UNKNOWN);

        m.admissions = m.admissions.put(node,
                                        known.withProcess(new ProcessIncarnation(known.process().bootIncarnation(),
                                                                                 token)));
        Indexer.refreshDeparted(m, node);
        Indexer.refreshNodeRows(m, node);
        var turned = noteStale(m, ctx, node, before);

        if (turned > 0) {
            restartClocks(m, ctx, node);
            Reconcile.request(m, ctx);
        }

        return m;
    }

    private static int staleRows(ClusterModel m, NodeId node) {
        return (int) Idx.members(m.observedByNode, node)
                        .toList()
                        .stream()
                        .map(artifact -> SlicePair.slicePair(node, artifact))
                        .filter(pair -> m.observed.get(pair)
                                                  .map(row -> Rows.isStale(m, pair, row))
                                                  .or(false))
                        .count();
    }

    /// The number of rows of `node` that turned stale since `before`; counts them in `stale_rows_total`.
    private static int noteStale(ClusterModel m, Ctx ctx, NodeId node, int before) {
        var now = staleRows(m, node);

        if (now > before) {
            ctx.metric("aether_deployment_fsm_stale_rows_total",
                       now - before,
                       Map.of("fsm", "cluster", "side", "leader"));
            Reconcile.request(m, ctx);
        }

        return Math.max(0, now - before);
    }

    /// After a (re-)admission the divergence clocks restart (R-6.1.2): a healed node has a full T to resume.
    private static ClusterModel restartClocks(ClusterModel m, Ctx ctx, NodeId node) {
        Idx.members(m.observedByNode, node)
           .toList()
           .forEach(artifact -> m.seenAt = m.seenAt.put(SlicePair.slicePair(node, artifact),
                                                        ctx.monoNs()));
        Idx.members(m.desiredByNode, node)
           .toList()
           .forEach(artifact -> m.seenAt = m.seenAt.put(SlicePair.slicePair(node, artifact),
                                                        ctx.monoNs()));

        return m;
    }

    // -- the table -------------------------------------------------------------------------------------------------
    private static ClusterModel evaluate(ClusterModel m, Ctx ctx, NodeId node, PendingJoin join) {
        m.pendingJoins = m.pendingJoins.remove(node);
        var admission = join.admission();
        var record = m.records.get(node);

        if (m.seams.superseded().contains(node) && join.worker()) {
            return refuse(m,
                          ctx,
                          node,
                          "join-refused-superseded",
                          "Worker " + node.id() + " is superseded: not re-admitted");
        }

        if (record.map(value -> admission.isDifferentProcessFrom(value.process())).or(false)) {
            return differentProcess(m, ctx, node, join, record);
        }

        if (m.departing.containsKey(node)) {
            return write(m,
                         ctx,
                         node,
                         join,
                         m.departing.get(node).flatMap(ClusterModel.PendingDeparture::expected),
                         List.of());
        }

        if (record.map(value -> value.state() == DepartureState.DEPARTED && stale(value)).or(false)) {
            return write(m, ctx, node, join, record, List.of());
        }

        if (record.map(value -> value.state() == DepartureState.DEPARTED && admission.epoch() > value.epoch()).or(false)) {
            return readmit(m, ctx, node, join, record);
        }

        return afterTable(m, ctx, node, join, record);
    }

    private static boolean stale(NodeDepartureValue record) {
        return record.cause()
                     .map(DepartureCause.STALE::equals)
                     .or(false);
    }

    /// J8: a different process under a known id, on positive evidence. A worker is refused; a core is recorded, and its
    /// earlier rows read as stale (C5, N4-2).
    private static ClusterModel differentProcess(ClusterModel m,
                                                 Ctx ctx,
                                                 NodeId node,
                                                 PendingJoin join,
                                                 Option<NodeDepartureValue> record) {
        ctx.warn("identity-process-mismatch",
                 node.id(),
                 "Node " + node.id() + " was admitted as a different process than the one on record");
        if (join.worker()) {
            return refuse(m,
                          ctx,
                          node,
                          "join-refused-process",
                          "Worker " + node.id() + " is a different process under a known id: refused");
        }

        endExclusions(m, node);
        var written = write(m, ctx, node, join, record, List.of());

        return directive(written, ctx, node, join, record);
    }

    /// R-4.2.5: the admission of a different process (positive evidence) ends the exclusion the old process earned.
    private static ClusterModel endExclusions(ClusterModel m, NodeId node) {
        Idx.members(m.unresponsiveByNode, node)
           .toList()
           .forEach(artifact -> Outbox.artifact(m,
                                                artifact,
                                                intent -> intent.withOp(new JudgmentOp.ClearUnresponsive(node))));

        return m;
    }

    /// J3: the same process, a higher epoch. A worker needs its WORKER directive (else J7 defers); a racing C17b is
    /// refused because the witness on the directive moves.
    private static ClusterModel readmit(ClusterModel m,
                                        Ctx ctx,
                                        NodeId node,
                                        PendingJoin join,
                                        Option<NodeDepartureValue> record) {
        var directive = m.directives.get(node);

        if (join.worker() && directive.isEmpty()) {
            m.pendingJoins = m.pendingJoins.put(node, join);

            return m;
        }

        var guards = directive.isPresent()
                     ? List.of(new KVCommand.ReadWitness<AetherKey>(new ActivationDirectiveKey(node),
                                                                    directive.map(v -> (Object) v)))
                     : List.<KVCommand.ReadWitness<AetherKey>> of();

        return write(m, ctx, node, join, record, guards);
    }

    /// J7 (a worker whose departed record blocks it and which has no directive is deferred), J5/J6 (not departed: assign the
    /// directive of its role, or nothing if it has one), J9 (anything else is visible and retried).
    private static ClusterModel afterTable(ClusterModel m,
                                           Ctx ctx,
                                           NodeId node,
                                           PendingJoin join,
                                           Option<NodeDepartureValue> record) {
        var recordDeparted = record.map(Departures::isDeparted).or(false);

        if (recordDeparted && join.worker() && m.directives.get(node).isEmpty()) {
            m.pendingJoins = m.pendingJoins.put(node, join);

            return m;
        }

        return recordDeparted
               ? unmatched(m, ctx, node, join, record)
               : directive(m, ctx, node, join, record);
    }

    private static ClusterModel directive(ClusterModel m,
                                          Ctx ctx,
                                          NodeId node,
                                          PendingJoin join,
                                          Option<NodeDepartureValue> record) {
        if (m.directives.containsKey(node)) {
            return m;
        }
        // A directive write stays tracked until its directive is observed: one that failed, was lost or was refused is derived again by
        // the reconcile (step 10) instead of waiting for the node to join again. Nodes that get no directive by design (a seed core, a
        // superseded core) queue no write and are not tracked.
        return Directory.directiveInFlight(m, node)
               ? track(m, node, join)
               : trackIfQueued(assign(m, ctx, node, join), node, join);
    }

    private static ClusterModel assign(ClusterModel m, Ctx ctx, NodeId node, PendingJoin join) {
        return join.worker()
               ? Directory.assignWorker(m, ctx, node)
               : Directory.assignCore(m, ctx, node);
    }

    private static ClusterModel track(ClusterModel m, NodeId node, PendingJoin join) {
        m.pendingJoins = m.pendingJoins.put(node, join);

        return m;
    }

    private static ClusterModel trackIfQueued(ClusterModel m, NodeId node, PendingJoin join) {
        return Directory.directiveInFlight(m, node)
               ? track(m, node, join)
               : m;
    }

    /// J9: no row matched. It is visible and retried, never silently left uncounted.
    private static ClusterModel unmatched(ClusterModel m,
                                          Ctx ctx,
                                          NodeId node,
                                          PendingJoin join,
                                          Option<NodeDepartureValue> record) {
        ctx.warn("join-unmatched",
                 node.id(),
                 "No join row matched " + node.id()
                + ": record=" + record
                + " admission=" + join.admission()
                + " worker=" + join.worker());
        m.pendingJoins = m.pendingJoins.put(node, join);

        return m;
    }

    private static ClusterModel refuse(ClusterModel m, Ctx ctx, NodeId node, String code, String message) {
        ctx.warn(code, node.id(), message);

        return m;
    }

    // -- the admission write (J0, J2, J3, J8 core) -----------------------------------------------------------------
    /// `Put(ADMITTED(epoch, process))` expecting `expected`, the value the departure write expected or the record observed.
    /// The join is remembered so a refusal re-runs the table (6.2).
    private static ClusterModel write(ClusterModel m,
                                      Ctx ctx,
                                      NodeId node,
                                      PendingJoin join,
                                      Option<NodeDepartureValue> expected,
                                      List<KVCommand.ReadWitness<AetherKey>> guards) {
        m.pendingJoins = m.pendingJoins.put(node, join);
        // Design-out: one admission write per node at a time. A second would expect the same record and be refused; the pending join above
        // is re-evaluated when the first one lands (C11c), so no input is lost by not writing again.
        return DepartureFlow.admissionInFlight(m, node)
               ? m
               : Outbox.direct(m, admissionDraft(ctx, node, join.admission(), expected, guards));
    }

    static ClusterModel writeAdmission(ClusterModel m,
                                       Ctx ctx,
                                       NodeId node,
                                       Admission admission,
                                       Option<NodeDepartureValue> expected) {
        return Outbox.direct(m, admissionDraft(ctx, node, admission, expected, List.of()));
    }

    private static TxDraft admissionDraft(Ctx ctx,
                                          NodeId node,
                                          Admission admission,
                                          Option<NodeDepartureValue> expected,
                                          List<KVCommand.ReadWitness<AetherKey>> guards) {
        var key = new NodeDepartureKey(node);
        var value = new NodeDepartureValue(DepartureState.ADMITTED,
                                           admission.epoch(),
                                           admission.process(),
                                           Option.none(),
                                           ctx.nowMs());
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      expected.map(v -> (AetherValue) v),
                                                                      Option.some((AetherValue) value));

        return TxDraft.draft(SubmitKind.ADMISSION,
                             key,
                             List.of(mutation))
                      .withGuards(guards)
                      .withNode(node);
    }

    // -- re-evaluation ---------------------------------------------------------------------------------------------
    /// A record changed, or the next membership input arrived: a deferred join is evaluated again.
    static ClusterModel reevaluate(ClusterModel m, Ctx ctx, NodeId node) {
        return m.pendingJoins.get(node)
                             .fold(() -> m,
                                   join -> evaluate(m, ctx, node, join));
    }

    static ClusterModel rerun(ClusterModel m, Ctx ctx, NodeId node) {
        return reevaluate(m, ctx, node);
    }

    /// The 30 s tick: every deferred join is evaluated again until a row matches or the node departs.
    static ClusterModel retryPending(ClusterModel m, Ctx ctx) {
        m.pendingJoins.keys().stream().sorted().forEach(node -> reevaluate(m, ctx, node));

        return m;
    }

    /// C11f: a worker recorded DEPARTED(STALE) reappeared in its community's fresh roster or in the local alive view: it is
    /// re-admitted through J2. A different known process is refused by J8 as usual.
    static ClusterModel reappeared(ClusterModel m, Ctx ctx, NodeId node) {
        var record = m.records.get(node);
        var recordedStale = record.map(value -> value.state() == DepartureState.DEPARTED && stale(value)).or(false);

        return recordedStale && Rows.recordedWorker(m, node) && listedOrAlive(m, ctx, node) && !DepartureFlow.admissionInFlight(m,
                                                                                                                                node)
               ? evaluate(m,
                          ctx,
                          node,
                          new PendingJoin(true,
                                          m.admissions.get(node).or(Admission.UNKNOWN)))
               : m;
    }

    private static boolean listedOrAlive(ClusterModel m, Ctx ctx, NodeId node) {
        var community = Absence.communityOf(m, node);
        var listed = Absence.isFresh(m, ctx, community) && m.rosters.get(community)
                                                                    .map(roster -> roster.members()
                                                                                         .contains(node))
                                                                    .or(false);

        return listed || m.seams.localAlive()
                                .map(alive -> alive.contains(node))
                                .or(false);
    }

    /// J5 retry: when nothing is allocatable right after a core joined, reconcile again in 2 s.
    static ClusterModel retryAllocation(ClusterModel m, Ctx ctx) {
        return Lifecycle.schedule(m, ctx, TimerId.timerId(Kind.RECONCILE, "join-retry"), 2_000L);
    }

    record unused() implements Joins {}
}
