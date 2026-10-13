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
    /// A join input: update `admissions[n]`, re-classify n's rows (R-3.3.1), clear `absentSince[n]`, then evaluate the table. A row that
    /// admits n marks it joined (R-2.11 (a)).
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

    /// R-2.11 (a): `node` is joined. A join input processed after `Rebuild` joins the node in every row that admits it: J0, J2, J3, the J8
    /// core arm (`write`) and J5/J6. J1 and the J8 worker arm refuse it, and J7 defers it, so none of them joins it `[guess: C16 says "add n
    /// to joined"; a refused or deferred join is not an admission, and counting such a worker would contradict J1 and J7 ("no write")]`.
    /// Record it, recompute `departed`, and reconcile when the node is counted from now on (its DEPARTED record was superseded).
    static ClusterModel markJoined(ClusterModel m, Ctx ctx, NodeId node) {
        var wasDeparted = m.departed.contains(node);

        m.joined = m.joined.add(node);
        Indexer.refreshDeparted(m, node);

        return wasDeparted && !m.departed.contains(node)
               ? Reconcile.request(m, ctx)
               : m;
    }

    /// R-2.11: a removal input, or a C17s submission that stands in for a lost one, ends the join evidence.
    static ClusterModel clearJoined(ClusterModel m, NodeId node) {
        m.joined = m.joined.remove(node);
        Liveness.broken(m, node);

        return Indexer.refreshDeparted(m, node);
    }

    private static boolean refusedAsSuperseded(ClusterModel m, NodeId node, boolean worker) {
        return worker && m.seams.superseded()
                                .contains(node);
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

        if (m.unforeseenJoins.contains(node)) {
            return unmatched(m, ctx, node, join, record);
        }

        if (refusedAsSuperseded(m, node, join.worker())) {
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

        if (record.map(value -> Departures.isDeparted(value) && stale(value)).or(false)) {
            return write(m, ctx, node, join, record, List.of());
        }

        if (record.map(Departures::isDeparted).or(false) && join.worker() && m.directives.get(node).isEmpty()) {
            return track(m, node, join);
        }

        return record.map(Departures::isDeparted)
                     .or(false)
               ? readmit(m, ctx, node, join, record)
               : afterTable(m, ctx, node, join, record);
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

    /// J3: the same process, any other cause. There is no epoch comparison (MQ-4): this join input is membership's admission after the
    /// removal the record ends, and the mailbox order orders the two within one leader incarnation. A worker needs its WORKER directive
    /// (else J7 defers, in `evaluate`); a racing C17b is refused because the witness on the directive moves.
    private static ClusterModel readmit(ClusterModel m,
                                        Ctx ctx,
                                        NodeId node,
                                        PendingJoin join,
                                        Option<NodeDepartureValue> record) {
        var directive = m.directives.get(node);
        var guards = directive.isPresent()
                     ? List.of(new KVCommand.ReadWitness<AetherKey>(new ActivationDirectiveKey(node),
                                                                    directive.map(v -> (Object) v)))
                     : List.<KVCommand.ReadWitness<AetherKey>> of();

        return write(m, ctx, node, join, record, guards);
    }

    /// J5/J6 (not departed: assign the directive of its role, or nothing if it has one), J9 (anything else is visible and retried).
    /// Under MQ-4 every DEPARTED record takes J2, J3 or J7 before this row, so J9 is reached only by an unforeseen combination.
    private static ClusterModel afterTable(ClusterModel m,
                                           Ctx ctx,
                                           NodeId node,
                                           PendingJoin join,
                                           Option<NodeDepartureValue> record) {
        return record.map(Departures::isDeparted)
                     .or(false)
               ? unmatched(m, ctx, node, join, record)
               : directive(markJoined(m, ctx, node), ctx, node, join, record);
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

    /// The join stays pending, and whatever unmatched episode it was in is over.
    private static ClusterModel track(ClusterModel m, NodeId node, PendingJoin join) {
        m.pendingJoins = m.pendingJoins.put(node, join.unwarned());

        return m;
    }

    private static ClusterModel trackIfQueued(ClusterModel m, NodeId node, PendingJoin join) {
        return Directory.directiveInFlight(m, node)
               ? track(m, node, join)
               : m;
    }

    /// J9: no row matched. It is visible and retried, never silently left uncounted. The WARN is emitted once per unmatched episode:
    /// every reconcile pass evaluates the join again (step 10), and a WARN per pass would grow with write load; the tick re-arms it.
    static ClusterModel unmatched(ClusterModel m,
                                  Ctx ctx,
                                  NodeId node,
                                  PendingJoin join,
                                  Option<NodeDepartureValue> record) {
        if (!join.warned()) {
            ctx.warn("join-unmatched",
                     node.id(),
                     "No join row matched " + node.id()
                    + ": record=" + record
                    + " admission=" + join.admission()
                    + " worker=" + join.worker());
        }

        m.pendingJoins = m.pendingJoins.put(node, join.asWarned());

        return m;
    }

    private static ClusterModel refuse(ClusterModel m, Ctx ctx, NodeId node, String code, String message) {
        ctx.warn(code, node.id(), message);

        return m;
    }

    // -- the admission write (J0, J2, J3, J8 core) -----------------------------------------------------------------
    /// `Put(ADMITTED(e⁺, process))` expecting `expected`, the value the departure write expected or the record observed, with
    /// `e⁺ = epochOf(expected) + 1`: the epoch is leader-assigned and read from the committed record, never from membership (MQ-4,
    /// R-4.3.0). The join is remembered so a refusal re-runs the table (6.2).
    private static ClusterModel write(ClusterModel m,
                                      Ctx ctx,
                                      NodeId node,
                                      PendingJoin join,
                                      Option<NodeDepartureValue> expected,
                                      List<KVCommand.ReadWitness<AetherKey>> guards) {
        markJoined(m, ctx, node);
        track(m, node, join);
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
                                           Departures.epochOf(expected) + 1L,
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

    /// Every reconcile pass evaluates the deferred joins again until a row matches or the node departs. The pass the 30 s tick queued
    /// first re-arms the `join-unmatched` WARN of the joins that are still unmatched.
    static ClusterModel retryPending(ClusterModel m, Ctx ctx) {
        if (m.joinWarningsDue) {
            rearmWarnings(m);
        }

        m.pendingJoins.keys().stream().sorted().forEach(node -> reevaluate(m, ctx, node));

        return m;
    }

    private static ClusterModel rearmWarnings(ClusterModel m) {
        m.joinWarningsDue = false;
        m.pendingJoins.entries().forEach(entry -> track(m, entry.getKey(), entry.getValue()));

        return m;
    }

    /// C11f: a worker with a DEPARTED record reappeared in its community's fresh roster or in the local alive view, **observed by this
    /// leader after `Rebuild`**, and is re-admitted through the join table as a `WorkerJoin` (R-2.11, G-3 narrowed by H-1).
    /// - A `STALE` record, whoever recorded it, takes J2.
    /// - Any other cause takes J3, but only when the record is `inherited` (committed before this leader's `Rebuild`). A departure this
    ///   leader decided needs a `WorkerJoin`, so a governor announcement built before the governor saw the worker leave cannot re-admit it.
    /// A different known process is refused by J8 as usual.
    static ClusterModel reappeared(ClusterModel m, Ctx ctx, NodeId node) {
        return m.records.get(node)
                        .filter(record -> evidenceApplies(m, node, record))
                        .filter(_ -> Rows.recordedWorker(m, node) && listedOrAlive(m, ctx, node))
                        .filter(_ -> !DepartureFlow.admissionInFlight(m, node))
                        .fold(() -> m,
                              record -> readmitOnEvidence(m, ctx, node, record));
    }

    /// A STALE record (only a DEPARTED record carries that cause), or one that `inherited` names: `inherited` holds DEPARTED records only, and
    /// leaves the set whenever its record changes (`Indexer#forgetInherited`).
    private static boolean evidenceApplies(ClusterModel m, NodeId node, NodeDepartureValue record) {
        return stale(record) || m.inherited.contains(node);
    }

    private static ClusterModel readmitOnEvidence(ClusterModel m, Ctx ctx, NodeId node, NodeDepartureValue record) {
        if (!stale(record)) {
            ctx.warn("worker-readmitted-on-evidence",
                     node.id(),
                     "Worker " + node.id()
                    + " departed (" + record.cause()
                    + ") before this leader's rebuild and is listed again: re-admitted");
        }

        return evaluate(m,
                        ctx,
                        node,
                        new PendingJoin(true,
                                        m.admissions.get(node).or(Admission.UNKNOWN)));
    }

    private static boolean listedOrAlive(ClusterModel m, Ctx ctx, NodeId node) {
        var community = Absence.communityOf(m, node);
        var listed = Absence.isFresh(m, ctx, community) && m.rosters.get(community)
                                                                    .map(roster -> roster.members()
                                                                                         .contains(node))
                                                                    .or(false);

        return listed || m.sampledAfterRebuild && m.seams.localAlive()
                                                         .map(alive -> alive.contains(node))
                                                         .or(false);
    }

    /// J5 retry: when nothing is allocatable right after a core joined, reconcile again in 2 s.
    static ClusterModel retryAllocation(ClusterModel m, Ctx ctx) {
        return Lifecycle.schedule(m, ctx, TimerId.timerId(Kind.RECONCILE, "join-retry"), 2_000L);
    }

    record unused() implements Joins {}
}
