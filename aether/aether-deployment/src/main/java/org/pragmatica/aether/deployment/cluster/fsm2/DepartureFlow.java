// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.PendingDeparture;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.deployment.cluster.fsm2.TimerId.Kind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Node departure (spec C17, C17s, C17b, C11c, R-4.3.0, R-4.3.3, R-4.3.5): the fenced `NodeDepartureKey` record and what
/// follows it.
///
/// - **C17** (a departure input): one transaction, `Put(DEPARTED(epoch, process, cause))` **expecting the record value it
///   observed** (absent, or ADMITTED), plus the removal of the node's desired keys and route keys. A refusal applies none of
///   them. It never touches an observed key or the directive.
/// - **C17s** (the backstop's inference, step 7): the Put alone, with cause STALE, fenced the same way. It removes nothing.
/// - **C17b** (after a current DEPARTED commits): the removal of the node's observed rows, each at its exact value, and of
///   any desired and route keys still present, guarded on that exact record. Never the directive or the placement key.
/// - A DEPARTED record that names an older incarnation than the current admission is **ignored on read** (R-4.3.0); the
///   ADMITTED rewrite replaces it.
public sealed interface DepartureFlow {
    // -- C17 / C17s: the fenced record -----------------------------------------------------------------------------
    /// C17: a membership departure input for `node`.
    static ClusterModel onDeparture(ClusterModel m, Ctx ctx, NodeId node, DepartureCause cause) {
        return m.departed.contains(node) || m.departing.containsKey(node)
               ? m
               : depart(m, ctx, node, cause, false);
    }

    /// C17s: the backstop records the departure its class's own signals imply.
    static ClusterModel infer(ClusterModel m, Ctx ctx, NodeId node) {
        return depart(m, ctx, node, DepartureCause.STALE, true);
    }

    private static ClusterModel depart(ClusterModel m, Ctx ctx, NodeId node, DepartureCause cause, boolean inferred) {
        var admission = m.admissions.get(node).or(Admission.UNKNOWN);
        var expected = m.records.get(node);

        m.departing = m.departing.put(node,
                                      new PendingDeparture(admission,
                                                           expected,
                                                           Option.some(cause),
                                                           inferred,
                                                           ctx.monoNs()));
        dropGoalsOf(m, node);
        m.pendingJoins = m.pendingJoins.remove(node);
        if (!inferred) {
            Absence.clear(m, node);
        }

        Outbox.direct(m, departureDraft(m, ctx, node, admission, expected, cause, inferred, 0));

        return Reconcile.request(m, ctx);
    }

    /// The pairs of a node that is leaving get no new goals.
    private static ClusterModel dropGoalsOf(ClusterModel m, NodeId node) {
        Idx.members(m.pendingByNode, node)
           .toList()
           .forEach(artifact -> dropGoal(m,
                                         SlicePair.slicePair(node, artifact)));
        m.artifactIntents.values()
                         .stream()
                         .flatMap(intent -> intent.goals()
                                                  .stream())
                         .filter(goal -> goal.pair()
                                             .node()
                                             .equals(node))
                         .forEach(goal -> Indexer.clearPending(m,
                                                               goal.pair()));
        m.artifactIntents.entries()
                         .forEach(entry -> Outbox.artifact(m,
                                                           entry.getKey(),
                                                           intent -> intent.withoutGoalsOf(node)));

        return m;
    }

    private static ClusterModel dropGoal(ClusterModel m, SlicePair pair) {
        Indexer.clearPending(m, pair);
        m.goalIntents = m.goalIntents.remove(pair);

        return m;
    }

    private static TxDraft departureDraft(ClusterModel m,
                                          Ctx ctx,
                                          NodeId node,
                                          Admission admission,
                                          Option<NodeDepartureValue> expected,
                                          DepartureCause cause,
                                          boolean inferred,
                                          int attempt) {
        var key = new NodeDepartureKey(node);
        var record = new NodeDepartureValue(DepartureState.DEPARTED,
                                            admission.epoch(),
                                            admission.process(),
                                            Option.some(cause),
                                            ctx.nowMs());
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        mutations.add(new KVCommand.Mutation<>(key,
                                               expected.map(v -> (AetherValue) v),
                                               Option.some((AetherValue) record)));
        if (!inferred) {
            mutations.addAll(desiredRemovals(m, node));
            mutations.addAll(routeRemovals(m, node));
        }

        return new TxDraft(inferred
                           ? SubmitKind.INFERRED_DEPARTURE
                           : SubmitKind.DEPARTURE,
                           key,
                           mutations,
                           List.of(),
                           Set.of(),
                           Option.some(node),
                           attempt,
                           List.of());
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> desiredRemovals(ClusterModel m, NodeId node) {
        return sorted(Idx.members(m.desiredByNode, node).toList()).stream()
                     .flatMap(artifact -> m.desired.get(SlicePair.slicePair(node, artifact))
                                                   .map(value -> removal(SlicePair.slicePair(node, artifact).desiredKey(),
                                                                         value))
                                                   .stream())
                     .toList();
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> routeRemovals(ClusterModel m, NodeId node) {
        return sorted(Idx.members(m.routesByNode, node).toList()).stream()
                     .flatMap(artifact -> m.routes.get(SlicePair.slicePair(node, artifact))
                                                  .map(value -> removal(new NodeRoutesKey(node, artifact),
                                                                        value))
                                                  .stream())
                     .toList();
    }

    private static List<Artifact> sorted(List<Artifact> artifacts) {
        return artifacts.stream()
                        .sorted(Comparator.comparing(Artifact::asString))
                        .toList();
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> removal(AetherKey key, AetherValue value) {
        return new KVCommand.Mutation<>(key, Option.some(value), Option.none());
    }

    // -- C11c: the record as notified ------------------------------------------------------------------------------
    static ClusterModel onRecordPut(ClusterModel m, Ctx ctx, NodeId node, NodeDepartureValue value) {
        var previous = m.records.get(node);

        Indexer.indexRecord(m, node, value);
        resolveInFlight(m, ctx, node);
        if (m.departed.contains(node)) {
            departed(m, ctx, node);
        } else if (value.state() == DepartureState.ADMITTED) {
            admitted(m, ctx, node, previous);
        } else if (Departures.isIgnored(value, m.admissions.get(node))) {
            admittedRewrite(m, ctx, node);
        }

        Joins.reevaluate(m, ctx, node);

        return Reconcile.request(m, ctx);
    }

    static ClusterModel onRecordRemove(ClusterModel m, Ctx ctx, NodeId node) {
        Indexer.removeRecord(m, node);
        resolveInFlight(m, ctx, node);
        Joins.reevaluate(m, ctx, node);

        return Reconcile.request(m, ctx);
    }

    /// R-4.3.0, "resolving an in-flight departure": a committed DEPARTED means it landed; an ADMITTED means it is obsolete.
    private static ClusterModel resolveInFlight(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.departing.containsKey(node) && m.records.get(node)
                                                      .map(record -> !isUnchanged(m, node, record) && !admittedAtOrBelow(m,
                                                                                                                         node,
                                                                                                                         record))
                                                      .or(true)) {
            m.departing = m.departing.remove(node);
            Lifecycle.cancel(m, ctx, rereadTimer(node));
        }

        return m;
    }

    private static boolean admittedAtOrBelow(ClusterModel m, NodeId node, NodeDepartureValue record) {
        return record.state() == DepartureState.ADMITTED && m.departing.get(node)
                                                                       .map(pending -> record.epoch() <= pending.admission()
                                                                                                                .epoch())
                                                                       .or(false);
    }

    private static boolean isUnchanged(ClusterModel m, NodeId node, NodeDepartureValue record) {
        return m.departing.get(node)
                          .flatMap(PendingDeparture::expected)
                          .map(record::equals)
                          .or(false);
    }

    /// A current DEPARTED committed: the exclusions of the node end (R-4.2.5). C17b is built by the reconcile this input queues
    /// (step 10), which runs after the notifications of the same transaction (its desired and route removals) were applied to the
    /// mirror, so that C17b is built on what is really there [guess: C11c says "C17b" directly; the reconcile is the same
    /// transition one mailbox item later].
    private static ClusterModel departed(ClusterModel m, Ctx ctx, NodeId node) {
        Absence.clear(m, node);
        Idx.members(m.unresponsiveByNode, node)
           .toList()
           .forEach(artifact -> Outbox.artifact(m,
                                                artifact,
                                                intent -> intent.withOp(new JudgmentOp.ClearUnresponsive(node))));
        // C17b runs from the reconcile this step queues (step 10): the committed transaction notifies its mutations in
        // order, so the desired and route removals of C17 arrive after the record, and a cleanup built now would expect
        // values that are about to go and be refused as a whole.
        return m;
    }

    /// An ADMITTED record committed. If it replaced a DEPARTED(STALE), the wrong recording is reversed (C11f, R-2.16).
    private static ClusterModel admitted(ClusterModel m, Ctx ctx, NodeId node, Option<NodeDepartureValue> previous) {
        previous.filter(record -> record.state() == DepartureState.DEPARTED && record.cause()
                                                                                     .map(DepartureCause.STALE::equals)
                                                                                     .or(false))
                .onPresent(record -> reversed(m, ctx, node, record));

        return m;
    }

    private static ClusterModel reversed(ClusterModel m, Ctx ctx, NodeId node, NodeDepartureValue record) {
        var replacements = Idx.members(m.desiredByNode, node).size();

        ctx.warn("departure-inferred-reversed",
                 node.id(),
                 "Worker " + node.id()
                + " recorded departed (STALE) at " + record.decidedAt()
                + " was re-admitted; its slices were re-placed (" + replacements
                + " pair(s) still keyed)");
        ctx.metric("aether_deployment_fsm_departures_inferred_reversed_total", Map.of("fsm", "cluster"));

        return m;
    }

    // -- C17b ------------------------------------------------------------------------------------------------------
    /// One transaction, guarded on the committed DEPARTED record: the node's observed rows at their exact values (current
    /// or stale alike), and any desired and route keys still present. Never the directive or the placement key.
    static ClusterModel cleanup(ClusterModel m, Ctx ctx, NodeId node, int attempt) {
        if (!m.departed.contains(node) || cleanupInFlight(m, node)) {
            return m;
        }

        var record = m.records.get(node);
        var mutations = cleanupMutations(m, node);

        return mutations.isEmpty() || record.isEmpty()
               ? m
               : Outbox.direct(m, cleanupDraft(node, mutations, record, attempt));
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> cleanupMutations(ClusterModel m, NodeId node) {
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        sorted(Idx.members(m.observedByNode, node).toList()).forEach(artifact -> m.observed.get(SlicePair.slicePair(node,
                                                                                                                    artifact))
                                                                                           .onPresent(value -> mutations.add(removal(SlicePair.slicePair(node,
                                                                                                                                                         artifact).observedKey(),
                                                                                                                                     value))));
        mutations.addAll(desiredRemovals(m, node));
        mutations.addAll(routeRemovals(m, node));

        return mutations;
    }

    private static TxDraft cleanupDraft(NodeId node,
                                        List<KVCommand.Mutation<AetherKey, AetherValue>> mutations,
                                        Option<NodeDepartureValue> record,
                                        int attempt) {
        var guard = new KVCommand.ReadWitness<AetherKey>(new NodeDepartureKey(node), record.map(v -> (Object) v));

        return new TxDraft(SubmitKind.CLEANUP,
                           mutations.getFirst().key(),
                           mutations,
                           List.of(guard),
                           Set.of(),
                           Option.some(node),
                           attempt,
                           List.of());
    }

    private static boolean cleanupInFlight(ClusterModel m, NodeId node) {
        return m.submitted.values()
                          .stream()
                          .flatMap(submitted -> submitted.drafts()
                                                         .stream())
                          .anyMatch(draft -> draft.kind() == SubmitKind.CLEANUP && draft.node()
                                                                                        .equals(Option.some(node))) || m.outbox.stream()
                                                                                                                               .anyMatch(draft -> draft.kind() == SubmitKind.CLEANUP && draft.node()
                                                                                                                                                                                             .equals(Option.some(node)));
    }

    /// A refused C17b (R-5.7.2): stop for good if the record moved. Otherwise the mutations whose keys moved are split out (a
    /// node that wrote a row keeps it), and the rest are resubmitted at once, at most five times.
    // FER (degrade forward): a refused C17b whose record moved is not resubmitted. Guarantee: nothing is removed against a record that
    // is no longer the one the cleanup was derived from. Mechanism: the guard on the DEPARTED record; the departed node's rows are
    // collected by the next reconcile's step 10, which derives a fresh C17b from the mirror.
    static ClusterModel cleanupRefused(ClusterModel m, Ctx ctx, TxDraft draft) {
        return draft.node()
                    .fold(() -> m,
                          node -> resubmitIfUnchanged(m, node, draft));
    }

    private static ClusterModel resubmitIfUnchanged(ClusterModel m, NodeId node, TxDraft draft) {
        return draft.attempt() < Outbox.GOAL_RESUBMISSIONS && recordUnchanged(m, node, draft)
               ? resubmitUnmoved(m, draft)
               : m;
    }

    private static ClusterModel resubmitUnmoved(ClusterModel m, TxDraft draft) {
        var unmoved = draft.mutations()
                           .stream()
                           .filter(mutation -> mutation.expected()
                                                       .equals(Outbox.mirrored(m,
                                                                               mutation.key())))
                           .toList();

        return unmoved.isEmpty()
               ? m
               : Outbox.direct(m,
                               new TxDraft(SubmitKind.CLEANUP,
                                           unmoved.getFirst().key(),
                                           unmoved,
                                           draft.guards(),
                                           draft.pairs(),
                                           draft.node(),
                                           draft.attempt() + 1,
                                           List.of()));
    }

    private static boolean recordUnchanged(ClusterModel m, NodeId node, TxDraft draft) {
        return draft.guards()
                    .stream()
                    .allMatch(guard -> guard.expected()
                                            .equals(m.records.get(node).map(v -> (Object) v)));
    }

    // -- the ADMITTED rewrite of an ignored record (R-4.3.0, step 10) -----------------------------------------------
    /// An ignored DEPARTED record leaves the node counted; it is replaced by ADMITTED(current), expecting the DEPARTED value.
    static ClusterModel admittedRewrite(ClusterModel m, Ctx ctx, NodeId node) {
        var record = m.records.get(node);
        var admission = m.admissions.get(node);

        if (record.isEmpty() || admission.isEmpty() || admissionInFlight(m, node)) {
            return m;
        }

        return Joins.writeAdmission(m, ctx, node, admission.or(Admission.UNKNOWN), record);
    }

    /// Step 10, "the `departing` re-reads" (R-4.3.0): a departure that has stood for one consensus timeout without resolving
    /// is read again. A timer covers a submission that failed; this covers one whose draft was dropped (a suspended flush, an
    /// expired submission) and so never reached a settlement. A submission older than the timeout is dropped by
    /// `Outbox.expire`, which runs first in the same pass, so nothing in flight is submitted twice.
    static ClusterModel rereadOverdue(ClusterModel m, Ctx ctx) {
        var bound = ctx.settings().timing().departingReread() * 1_000_000L;

        m.departing.entries()
                   .stream()
                   .filter(entry -> ctx.monoNs() - entry.getValue()
                                                        .sinceMono() >= bound)
                   .sorted(Map.Entry.comparingByKey())
                   .forEach(entry -> rereadPending(m,
                                                   ctx,
                                                   entry.getKey(),
                                                   entry.getValue()));

        return m;
    }

    static boolean admissionInFlight(ClusterModel m, NodeId node) {
        return m.submitted.values()
                          .stream()
                          .flatMap(submitted -> submitted.drafts()
                                                         .stream())
                          .anyMatch(draft -> draft.kind() == SubmitKind.ADMISSION && draft.node()
                                                                                          .equals(Option.some(node))) || m.outbox.stream()
                                                                                                                                 .anyMatch(draft -> draft.kind() == SubmitKind.ADMISSION && draft.node()
                                                                                                                                                                                                 .equals(Option.some(node)));
    }

    // -- settlement of a departure write (R-4.3.0, 6.2) -------------------------------------------------------------
    /// Refused (after the re-read): an ADMITTED with an epoch at or above the departed one makes the departure obsolete;
    /// otherwise it is re-derived from the mirror. A C17s is re-derived only while step 7 still warrants it.
    static ClusterModel refused(ClusterModel m, Ctx ctx, TxDraft draft) {
        return draft.node()
                    .fold(() -> m,
                          node -> refusedFor(m, ctx, draft, node));
    }

    private static ClusterModel refusedFor(ClusterModel m, Ctx ctx, TxDraft draft, NodeId node) {
        if (draft.kind() == SubmitKind.ADMISSION) {
            return Joins.rerun(m, ctx, node);
        }

        return m.departing.get(node)
                          .fold(() -> m,
                                pending -> refusedPending(m,
                                                          ctx,
                                                          node,
                                                          pending,
                                                          draft.attempt() + 1));
    }

    private static ClusterModel refusedPending(ClusterModel m,
                                               Ctx ctx,
                                               NodeId node,
                                               PendingDeparture pending,
                                               int attempt) {
        return obsolete(m, node, pending)
               ? resolveObsolete(m, ctx, node)
               : resubmit(m, ctx, node, pending, attempt);
    }

    private static boolean obsolete(ClusterModel m, NodeId node, PendingDeparture pending) {
        return m.records.get(node)
                        .map(record -> record.state() == DepartureState.ADMITTED && record.epoch() > pending.admission()
                                                                                                            .epoch())
                        .or(false);
    }

    // Design-out: the departure is dropped only when a committed ADMITTED record shows the node was re-admitted (R-4.3.0), so there is
    // no failure to absorb. Mechanism: `obsolete` reads the committed record; the re-read timer is cancelled with it.
    private static ClusterModel resolveObsolete(ClusterModel m, Ctx ctx, NodeId node) {
        m.departing = m.departing.remove(node);

        return Lifecycle.cancel(m, ctx, rereadTimer(node));
    }

    /// Re-derive the identical write against the mirror's current record. A C17s is re-derived only while step 7 still
    /// warrants it for the node's class (R-4.3.0): a node present again, exempt, unjudgeable or behind a holding breaker
    /// gets no resubmission and leaves `departing`.
    private static ClusterModel resubmit(ClusterModel m, Ctx ctx, NodeId node, PendingDeparture pending, int attempt) {
        if (pending.inferred() && !Backstop.warrants(m, ctx, node)) {
            return resolveObsolete(m, ctx, node);
        }

        if (attempt > Outbox.GOAL_RESUBMISSIONS) {
            return rereadLater(m, ctx, node);
        }

        var expected = m.records.get(node);
        var cause = pending.cause().or(DepartureCause.NODE_REMOVED);

        m.departing = m.departing.put(node,
                                      new PendingDeparture(pending.admission(),
                                                           expected,
                                                           pending.cause(),
                                                           pending.inferred(),
                                                           ctx.monoNs()));

        return Outbox.direct(m,
                             departureDraft(m,
                                            ctx,
                                            node,
                                            pending.admission(),
                                            expected,
                                            cause,
                                            pending.inferred(),
                                            attempt));
    }

    /// Failed or unattributable: n stays in `departing`, and a timer re-reads the record after one consensus timeout.
    static ClusterModel failed(ClusterModel m, Ctx ctx, TxDraft draft) {
        return draft.kind() == SubmitKind.DEPARTURE || draft.kind() == SubmitKind.INFERRED_DEPARTURE
               ? draft.node()
                      .fold(() -> m,
                            node -> rereadLater(m, ctx, node))
               : m;
    }

    private static ClusterModel rereadLater(ClusterModel m, Ctx ctx, NodeId node) {
        return m.departing.containsKey(node) && !m.timers.contains(rereadTimer(node))
               ? Lifecycle.schedule(m,
                                    ctx,
                                    rereadTimer(node),
                                    ctx.settings().timing().departingReread())
               : m;
    }

    private static TimerId rereadTimer(NodeId node) {
        return TimerId.timerId(Kind.DEPARTING_REREAD, node.id());
    }

    /// The timer: the record is read again, and an unresolved departure is resubmitted identically (R-4.3.0).
    static ClusterModel reread(ClusterModel m, Ctx ctx, String subject) {
        var node = new NodeId(subject);

        return m.departing.get(node)
                          .fold(() -> m,
                                pending -> rereadPending(m, ctx, node, pending));
    }

    private static ClusterModel rereadPending(ClusterModel m, Ctx ctx, NodeId node, PendingDeparture pending) {
        if (obsolete(m, node, pending)) {
            return resolveObsolete(m, ctx, node);
        }

        if (m.records.get(node)
                     .map(record -> !isUnchanged(m, node, record) && !admittedAtOrBelow(m, node, record))
                     .or(false)) {
            m.departing = m.departing.remove(node);

            return m;
        }

        resubmit(m, ctx, node, pending, 1);

        return rereadLater(m, ctx, node);
    }

    // -- step 10: departed rows, records ---------------------------------------------------------------------------
    /// C17b for every departed node that still has rows, desired keys or route keys (C1, the tick), and the ADMITTED
    /// rewrite of ignored DEPARTED records.
    static ClusterModel sweep(ClusterModel m, Ctx ctx) {
        m.departed.toList().stream().sorted().forEach(node -> cleanup(m, ctx, node, 0));
        m.records.entries()
                 .stream()
                 .filter(entry -> Departures.isIgnored(entry.getValue(),
                                                       m.admissions.get(entry.getKey())))
                 .map(Map.Entry::getKey)
                 .sorted()
                 .forEach(node -> admittedRewrite(m, ctx, node));

        return m;
    }

    /// R-4.3.5 (b): a record is removed when its node has no directive, no observed key, no desired key, no `departing`
    /// entry, and is not a current member (R-2.13).
    static ClusterModel collectRecords(ClusterModel m, Ctx ctx) {
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        m.records.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()
                                                            .id()))
                 .filter(entry -> collectable(m,
                                              entry.getKey()))
                 .forEach(entry -> mutations.add(removal(new NodeDepartureKey(entry.getKey()),
                                                         entry.getValue())));
        if (!mutations.isEmpty() && !recordGcInFlight(m)) {
            Outbox.direct(m,
                          TxDraft.draft(SubmitKind.GC,
                                        mutations.getFirst().key(),
                                        mutations));
        }

        return m;
    }

    private static boolean recordGcInFlight(ClusterModel m) {
        return m.submitted.values()
                          .stream()
                          .flatMap(submitted -> submitted.drafts()
                                                         .stream())
                          .anyMatch(draft -> draft.kind() == SubmitKind.GC && draft.key() instanceof NodeDepartureKey) || m.outbox.stream()
                                                                                                                                  .anyMatch(draft -> draft.kind() == SubmitKind.GC && draft.key() instanceof NodeDepartureKey);
    }

    private static boolean collectable(ClusterModel m, NodeId node) {
        return ! m.directives.containsKey(node)
               && !m.observedByNode.containsKey(node)
               && !m.desiredByNode.containsKey(node)
               && !m.departing.containsKey(node)
               && !isCurrentMember(m, node);
    }

    /// R-2.13: n has an admission and is neither departed nor departing.
    static boolean isCurrentMember(ClusterModel m, NodeId node) {
        return m.admissions.containsKey(node)
               && !m.departed.contains(node)
               && !m.departing.containsKey(node);
    }

    record unused() implements DepartureFlow {}
}
