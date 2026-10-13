// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.TreeMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.ArtifactIntent;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalIntent;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalWrite;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.PendingGoal;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.Submitted;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The write side of the cluster FSM (spec 5.7, 6.2): intents collected by the handlers of one mailbox drain, turned
/// into `LeaderTransaction`s at the flush, and the settlement and refusal handling that follows.
///
/// - **Goals** (placement, adoption, condemnation, re-issue) are collected per pair and written **per node** (R-5.7.4).
/// - **Artifact intents** (failures, retractions, retry resets) are collected per artifact; their judgment operations
///   fold into **one** mutation that rides the artifact's transaction (R-4.2.2, NT-108). A pair that is in both an
///   artifact transaction and the goal intents is written by the artifact transaction: failure handling wins.
/// - **Direct drafts** are single-purpose transactions (departures, targets, outcomes, ...), each submitted as is.
///
/// Every transaction carries the committed `LeaderValue` and exact expected values from the mirror (R-5.7.1). All the
/// transactions of one flush ride one `Submit` (one `apply(List)`).
public sealed interface Outbox {
    int GOAL_RESUBMISSIONS = 5;
    int ROLLBACK_RESUBMISSIONS = 5;

    // -- intents ---------------------------------------------------------------------------------------------------
    /// A fresh generation (R-1.6.1): the issuing leader's committed `viewSequence` and the next counter value.
    static Generation freshGeneration(ClusterModel m) {
        var sequence = m.leader.map(LeaderValue::viewSequence).or(0L);
        var generation = new Generation(sequence, m.genCounter);

        m.genCounter = m.genCounter + 1;

        return generation;
    }

    /// Record the intent to write `goal` for `pair`. A later intent for the same pair in the same drain replaces an
    /// earlier one. The goal counts as placed from now on, through `pendingGoals` (R-2.6, M-3).
    static ClusterModel goal(ClusterModel m,
                             SlicePair pair,
                             SliceGoal goal,
                             GoalMode mode,
                             Option<Generation> given,
                             String why) {
        return leaving(m, pair.node())
               ? m
               : record(m, pair, goal, mode, given, why);
    }

    /// A node that is departing or departed gets no new goals: its rows are removed with its departure (R-4.3.0, R-4.3.5).
    private static boolean leaving(ClusterModel m, NodeId node) {
        return m.departing.containsKey(node) || m.departed.contains(node);
    }

    private static ClusterModel record(ClusterModel m,
                                       SlicePair pair,
                                       SliceGoal goal,
                                       GoalMode mode,
                                       Option<Generation> given,
                                       String why) {
        var generation = switch (mode) {
            case FRESH -> freshGeneration(m);
            case ADOPT -> given.or(() -> freshGeneration(m));
            case REISSUE -> m.desired.get(pair).map(SliceDesiredValue::generation).or(() -> freshGeneration(m));
        };

        m.goalIntents = m.goalIntents.put(pair, new GoalIntent(goal, mode, generation, m.observed.get(pair), why));
        Indexer.putPending(m, pair, new PendingGoal(goal, generation));

        return m;
    }

    /// Edit the intent of one artifact for this drain.
    static ClusterModel artifact(ClusterModel m, Artifact artifact, UnaryOperator<ArtifactIntent> edit) {
        var current = m.artifactIntents.get(artifact).or(ArtifactIntent.none());

        m.artifactIntents = m.artifactIntents.put(artifact, edit.apply(current));

        return m;
    }

    /// A goal write that rides the artifact's transaction (failure UNLOADED, retraction). Counts as placed-not from now.
    static ClusterModel artifactGoal(ClusterModel m, SlicePair pair, SliceGoal goal) {
        return leaving(m, pair.node())
               ? m
               : recordArtifactGoal(m, pair, goal);
    }

    private static ClusterModel recordArtifactGoal(ClusterModel m, SlicePair pair, SliceGoal goal) {
        var generation = freshGeneration(m);
        var write = new GoalWrite(pair, goal, generation, m.observed.get(pair));

        Indexer.putPending(m, pair, new PendingGoal(goal, generation));
        m.goalIntents = m.goalIntents.remove(pair);

        return artifact(m, pair.artifact(), intent -> intent.withGoal(write));
    }

    /// Queue a single-purpose transaction.
    static ClusterModel direct(ClusterModel m, TxDraft draft) {
        var next = new ArrayList<>(m.outbox);

        next.add(draft);
        m.outbox = next;

        return m;
    }

    static boolean hasIntents(ClusterModel m) {
        return ! m.goalIntents.isEmpty() || !m.artifactIntents.isEmpty() || !m.outbox.isEmpty();
    }

    /// At the end of the step: if the handlers left intents and no flush is queued, queue one at the tail of the
    /// mailbox, so every input already queued is handled first (one drain, R-5.7.4).
    static ClusterModel requestFlush(ClusterModel m, Ctx ctx) {
        if (hasIntents(m) && !m.flushQueued) {
            m.flushQueued = true;
            ctx.emit(new ClusterEffect.Enqueue(new ClusterInput.Flush()));
        }

        return m;
    }

    // -- flush -----------------------------------------------------------------------------------------------------
    /// The end of a drain: build the transactions. While suspended (the committed leader is not this node) nothing is
    /// submitted and the intents are dropped; the exit on CI-16 re-derives them (3.2, R-5.7.1 (a)).
    static ClusterModel flush(ClusterModel m, Ctx ctx, boolean suspended) {
        m.flushQueued = false;
        // Design-out by parking: while suspended nothing is submitted; event-only intents are parked and replayed on the CI-16 resume
        // (`Lifecycle.onLeader`), the rest are level-triggered and re-derived by the reconcile the resume queues.
        if (suspended || m.leader.isEmpty()) {
            park(m);
            dropIntents(m, ctx);
            clearIntents(m);

            return m;
        }

        var drafts = materialize(m, ctx);

        clearIntents(m);
        // Design-out: the leader is present here (an empty leader returned above), so the empty arm only totals the fold.
        return drafts.isEmpty()
               ? m
               : m.leader.fold(() -> m, leader -> submit(m, ctx, drafts, leader));
    }

    /// Event-only intents (a blueprint removal, a directive, judgment operations) survive a suspension: nothing re-derives them.
    private static ClusterModel park(ClusterModel m) {
        m.parked = Stream.concat(m.parked.stream(),
                                 m.outbox.stream()
                                         .filter(draft -> draft.kind() == SubmitKind.BLUEPRINT_REMOVAL || draft.kind() == SubmitKind.DIRECTIVE))
                         .toList();
        m.parkedOps = Stream.concat(m.parkedOps.stream(),
                                    m.artifactIntents.entries()
                                                     .stream()
                                                     .flatMap(entry -> entry.getValue()
                                                                            .ops()
                                                                            .stream()
                                                                            .map(op -> Map.entry(entry.getKey(),
                                                                                                 op))))
                            .toList();

        return m;
    }

    private static ClusterModel clearIntents(ClusterModel m) {
        m.goalIntents = PMap.empty(m.scans);
        m.artifactIntents = PMap.empty(m.scans);
        m.outbox = List.of();

        return m;
    }

    private static ClusterModel dropIntents(ClusterModel m, Ctx ctx) {
        var dropped = m.goalIntents.size() + m.artifactIntents.size() + m.outbox.size();

        m.goalIntents.keys().forEach(pair -> Indexer.clearPending(m, pair));
        m.artifactIntents.values()
                         .forEach(intent -> intent.goals()
                                                  .forEach(write -> Indexer.clearPending(m,
                                                                                         write.pair())));
        if (dropped > 0) {
            ctx.metric("aether_deployment_fsm_submissions_total",
                       dropped,
                       Map.of("fsm", "cluster", "kind", "any", "outcome", "refused_witness_stale"));
        }

        return m;
    }

    private static ClusterModel submit(ClusterModel m, Ctx ctx, List<TxDraft> drafts, LeaderValue leader) {
        drafts.forEach(draft -> submitOne(m, ctx, draft, leader));

        return m;
    }

    private static ClusterModel submitOne(ClusterModel m, Ctx ctx, TxDraft draft, LeaderValue leader) {
        var correlation = "c" + m.incarnation + "-" + (++m.txSeq);

        m.submitted = m.submitted.put(correlation,
                                      new Submitted(correlation,
                                                    draft.kind(),
                                                    List.of(draft),
                                                    m.incarnation,
                                                    leader,
                                                    ctx.monoNs()));
        ctx.emit(new ClusterEffect.Submit(correlation,
                                          m.incarnation,
                                          draft.kind(),
                                          transaction(correlation, draft, leader)));
        ctx.metric("aether_deployment_fsm_submissions_total",
                   Map.of("fsm",
                          "cluster",
                          "kind",
                          draft.kind().name(),
                          "outcome",
                          "submitted"));

        return m;
    }

    private static KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction(String correlation,
                                                                                   TxDraft draft,
                                                                                   LeaderValue leader) {
        return new KVCommand.LeaderTransaction<>(draft.key(), correlation, leader, draft.guards(), draft.mutations());
    }

    /// Turn the collected intents into drafts: artifact transactions first (failure handling wins), then per-node goal
    /// transactions, then the direct drafts in the order they were queued.
    static List<TxDraft> materialize(ClusterModel m, Ctx ctx) {
        var drafts = new ArrayList<TxDraft>();
        var covered = new HashSet<SlicePair>();

        m.artifactIntents.entries()
                         .stream()
                         .sorted(Comparator.comparing(entry -> entry.getKey()
                                                                    .asString()))
                         .forEach(entry -> artifactDraft(m,
                                                         ctx,
                                                         entry.getKey(),
                                                         entry.getValue(),
                                                         covered).onPresent(drafts::add));
        drafts.addAll(goalDrafts(m, ctx, covered));
        drafts.addAll(m.outbox);

        return drafts;
    }

    private static Option<TxDraft> artifactDraft(ClusterModel m,
                                                 Ctx ctx,
                                                 Artifact artifact,
                                                 ArtifactIntent intent,
                                                 Set<SlicePair> covered) {
        var live = liveWrites(m, intent.goals());
        var mutations = Stream.of(judgmentMutation(m, artifact, intent).stream(),
                                  live.stream()
                                      .map(write -> desiredMutation(m,
                                                                    ctx,
                                                                    write.pair(),
                                                                    write.goal(),
                                                                    write.generation())),
                                  intent.extras().stream())
                              .flatMap(Function.identity())
                              .toList();

        live.forEach(write -> covered.add(write.pair()));

        return mutations.isEmpty()
               ? Option.none()
               : Option.some(TxDraft.draft(SubmitKind.FAILURE,
                                           new ArtifactJudgmentKey(artifact),
                                           mutations)
                                    .withGuards(live.stream()
                                                    .map(write -> observedGuard(write.pair(),
                                                                                write.guard()))
                                                    .toList())
                                    .withPairs(live.stream().map(GoalWrite::pair).collect(Collectors.toSet()))
                                    .withOnAccepted(intent.routed()));
    }

    /// The goal writes whose decision still stands; the others release their pending entry.
    private static List<GoalWrite> liveWrites(ClusterModel m, List<GoalWrite> writes) {
        writes.stream()
              .filter(write -> !stillCurrent(m,
                                             write.pair(),
                                             write.guard()))
              .forEach(write -> Indexer.clearPending(m,
                                                     write.pair()));

        return writes.stream()
                     .filter(write -> stillCurrent(m,
                                                   write.pair(),
                                                   write.guard()))
                     .toList();
    }

    /// The artifact's judgment as one mutation, only when the folded operations change it.
    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> judgmentMutation(ClusterModel m,
                                                                                       Artifact artifact,
                                                                                       ArtifactIntent intent) {
        var committed = m.judgments.get(artifact);
        var folded = JudgmentOp.fold(committed, intent.ops());

        return intent.ops()
                     .isEmpty() || folded.equals(committed)
               ? Option.none()
               : Option.some(new KVCommand.Mutation<>(new ArtifactJudgmentKey(artifact),
                                                      committed.map(v -> (AetherValue) v),
                                                      folded.map(v -> (AetherValue) v)));
    }

    private static List<TxDraft> goalDrafts(ClusterModel m, Ctx ctx, Set<SlicePair> covered) {
        var byNode = new TreeMap<NodeId, List<Map.Entry<SlicePair, GoalIntent>>>();

        m.goalIntents.entries()
                     .stream()
                     .filter(entry -> !covered.contains(entry.getKey()))
                     .sorted(Comparator.comparing(entry -> entry.getKey(),
                                                  Rows.PAIR_ORDER))
                     .forEach(entry -> byNode.computeIfAbsent(entry.getKey().node(),
                                                              _ -> new ArrayList<>())
                                             .add(entry));

        return byNode.entrySet()
                     .stream()
                     .flatMap(node -> goalDraft(m,
                                                ctx,
                                                node.getKey(),
                                                node.getValue()).stream())
                     .toList();
    }

    private static Option<TxDraft> goalDraft(ClusterModel m,
                                             Ctx ctx,
                                             NodeId node,
                                             List<Map.Entry<SlicePair, GoalIntent>> entries) {
        entries.stream()
               .filter(entry -> !stillCurrent(m,
                                              entry.getKey(),
                                              entry.getValue().guard()))
               .forEach(entry -> Indexer.clearPending(m,
                                                      entry.getKey()));
        var live = entries.stream().filter(entry -> stillCurrent(m,
                                                                 entry.getKey(),
                                                                 entry.getValue().guard())).toList();
        var mutations = live.stream()
                            .map(entry -> desiredMutation(m,
                                                          ctx,
                                                          entry.getKey(),
                                                          entry.getValue().goal(),
                                                          entry.getValue().generation()))
                            .toList();

        return mutations.isEmpty()
               ? Option.none()
               : Option.some(TxDraft.draft(SubmitKind.GOALS,
                                           mutations.getFirst().key(),
                                           mutations)
                                    .withGuards(live.stream()
                                                    .map(entry -> observedGuard(entry.getKey(),
                                                                                entry.getValue().guard()))
                                                    .toList())
                                    .withPairs(live.stream().map(Map.Entry::getKey).collect(Collectors.toSet()))
                                    .withNode(node)
                                    .withOnAccepted(started(ctx, node, live)));
    }

    /// A fresh LOADED goal announces `DeploymentStarted` when its transaction is accepted.
    private static List<RoutedEvent> started(Ctx ctx, NodeId node, List<Map.Entry<SlicePair, GoalIntent>> live) {
        return live.stream()
                   .filter(entry -> entry.getValue()
                                         .mode() == GoalMode.FRESH && entry.getValue()
                                                                           .goal() == SliceGoal.LOADED)
                   .<RoutedEvent> map(entry -> new RoutedEvent.DeploymentStarted(entry.getKey().artifact(),
                                                                                 node,
                                                                                 ctx.nowMs()))
                   .toList();
    }

    /// A decision stands only while the observed value it was derived from is still the one in the mirror.
    private static boolean stillCurrent(ClusterModel m, SlicePair pair, Option<SliceObservedValue> guard) {
        return m.observed.get(pair)
                         .equals(guard);
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> desiredMutation(ClusterModel m,
                                                                              Ctx ctx,
                                                                              SlicePair pair,
                                                                              SliceGoal goal,
                                                                              Generation generation) {
        var expected = m.desired.get(pair);
        var replacement = expected.filter(value -> value.goal() == goal && value.generation()
                                                                                .equals(generation))
                                  .or(() -> new SliceDesiredValue(goal,
                                                                  generation,
                                                                  ctx.nowMs()));

        return new KVCommand.Mutation<>(pair.desiredKey(),
                                        expected.map(v -> (AetherValue) v),
                                        Option.some((AetherValue) replacement));
    }

    private static KVCommand.ReadWitness<AetherKey> observedGuard(SlicePair pair, Option<SliceObservedValue> guard) {
        return new KVCommand.ReadWitness<>(pair.observedKey(), guard.map(v -> (Object) v));
    }

    /// A submission that was never settled is lost (#2143): after one consensus timeout it no longer counts as in flight, its
    /// pending goals are released (M-3), and the next reconcile re-derives it.
    // BER-free absorption, design-out: a submission that never settled is dropped from `submitted` and its pending goals are released.
    // Guarantee: the loss is re-derived, never silent. Mechanism: the `lost` metric here, and the next reconcile derives the same
    // intent again from the mirror (level-triggered), so nothing depends on the lost write having landed.
    static ClusterModel expire(ClusterModel m, Ctx ctx) {
        var bound = ctx.settings().timing().departingReread() * 1_000_000L;

        m.submitted.entries()
                   .stream()
                   .filter(entry -> ctx.monoNs() - entry.getValue()
                                                        .atMono() >= bound)
                   .forEach(entry -> release(m,
                                             ctx,
                                             entry.getKey(),
                                             entry.getValue()));

        return m;
    }

    private static ClusterModel release(ClusterModel m, Ctx ctx, String correlation, Submitted submission) {
        m.submitted = m.submitted.remove(correlation);
        submission.drafts().forEach(draft -> dropPending(m, draft));
        metric(ctx, submission, "lost");

        return m;
    }

    // -- settlement (CI-14, 5.7.2, 6.2) ----------------------------------------------------------------------------
    /// The outcome of a submission. A settlement of another incarnation never reaches here (the FSM drops it, R-3.1.3).
    // FER (degrade forward): a settlement for a correlation that is not in `submitted` (expired by `expire`, or from a model replaced by a
    // Rebuild) is dropped and counted. Guarantee: nothing is applied twice and the loss is visible. Mechanism: the `settled_unknown` metric;
    // the submission's pending entries were already released and its intent is re-derived level-triggered from the mirror.
    static ClusterModel settle(ClusterModel m, Ctx ctx, ClusterInput.WriteSettled settled) {
        return m.submitted.get(settled.correlationId())
                          .fold(() -> unknownSettlement(m, ctx),
                                submission -> settleKnown(m, ctx, submission, settled));
    }

    private static ClusterModel unknownSettlement(ClusterModel m, Ctx ctx) {
        ctx.metric("aether_deployment_fsm_submissions_total",
                   Map.of("fsm", "cluster", "kind", "any", "outcome", "settled_unknown"));

        return m;
    }

    private static ClusterModel settleKnown(ClusterModel m,
                                            Ctx ctx,
                                            Submitted submission,
                                            ClusterInput.WriteSettled settled) {
        m.submitted = m.submitted.remove(settled.correlationId());

        return switch (settled.settlement()) {
            case Settlement.Accepted _ -> accepted(m, ctx, submission);
            case Settlement.Refused refused -> refused(m, ctx, submission, refused);
            case Settlement.Failed failed -> failed(m, ctx, submission, failed);
        };
    }

    /// An accepted transaction's event goes out now. A `DeploymentFailed` also marks its FAILED row as announced, so a lost write is
    /// announced when its re-derived transaction lands, and only once.
    private static ClusterModel announce(ClusterModel m, Ctx ctx, RoutedEvent event) {
        ctx.route(event);

        return event instanceof RoutedEvent.DeploymentFailed failed
               ? markAnnounced(m,
                               SlicePair.slicePair(failed.node(), failed.artifact()))
               : m;
    }

    private static ClusterModel markAnnounced(ClusterModel m, SlicePair pair) {
        m.observed.get(pair).onPresent(row -> m.failureRouted = m.failureRouted.put(pair, row.forGeneration()));

        return m;
    }

    private static ClusterModel accepted(ClusterModel m, Ctx ctx, Submitted submission) {
        for (var draft : submission.drafts()) {
            draft.onAccepted().forEach(event -> announce(m, ctx, event));
            ctx.metric("aether_deployment_fsm_submissions_total",
                       Map.of("fsm",
                              "cluster",
                              "kind",
                              draft.kind().name(),
                              "outcome",
                              "accepted"));
        }

        return m;
    }

    /// 5.7.2: re-read, then re-derive. Deposed (the committed leader is another node): suspend, nothing is
    /// resubmitted. Own witness stale (the committed value moved but still names this node): take the new witness and
    /// resubmit unchanged, unbudgeted. Otherwise re-derive per pair: pairs whose keys moved are split out, the rest are
    /// resubmitted at once, at most [#GOAL_RESUBMISSIONS] times per pair intent, then the tick.
    private static ClusterModel refused(ClusterModel m, Ctx ctx, Submitted submission, Settlement.Refused refused) {
        var mirrorBefore = mirrorFingerprint(m, refused);

        reindex(m, ctx, refused);
        m.leader = refused.leader();

        return routeRefusal(m, ctx, submission, refused, !mirrorBefore.equals(mirrorFingerprint(m, refused)));
    }

    /// The refusal carries the values the applier saw: the ones the mirror lags behind are indexed again.
    private static ClusterModel reindex(ClusterModel m, Ctx ctx, Settlement.Refused refused) {
        refused.values()
               .entrySet()
               .stream()
               .filter(entry -> !mirrored(m,
                                          entry.getKey()).equals(entry.getValue()))
               .forEach(entry -> entry.getValue()
                                      .fold(() -> Indexer.indexRemove(m,
                                                                      ctx,
                                                                      entry.getKey()),
                                            present -> Indexer.indexPut(m,
                                                                        ctx,
                                                                        entry.getKey(),
                                                                        present)));

        return m;
    }

    /// R-5.7.2: deposed, own witness stale, or the values moved.
    private static ClusterModel routeRefusal(ClusterModel m,
                                             Ctx ctx,
                                             Submitted submission,
                                             Settlement.Refused refused,
                                             boolean corrected) {
        // BER-free design-out: a deposed leader submits nothing more; the new leader derives the same intents from the mirror (R-5.7.2).
        if (!namesSelf(ctx, refused.leader())) {
            metric(ctx, submission, "refused_deposed");
            submission.drafts().forEach(draft -> dropPending(m, draft));

            return m;
        }

        if (!refused.leader().equals(Option.some(submission.leader()))) {
            metric(ctx, submission, "refused_witness_stale");
            submission.drafts().forEach(draft -> Outbox.direct(m, draft));

            return m;
        }

        metric(ctx,
               submission,
               corrected
               ? "refused_mirror_lag"
               : "refused_version");
        submission.drafts().forEach(draft -> rederive(m, ctx, draft));

        return m;
    }

    private static List<Option<AetherValue>> mirrorFingerprint(ClusterModel m, Settlement.Refused refused) {
        return refused.values()
                      .keySet()
                      .stream()
                      .map(key -> mirrored(m, key))
                      .toList();
    }

    /// What the model currently believes the value of `key` is.
    static Option<AetherValue> mirrored(ClusterModel m, AetherKey key) {
        return switch (key) {
            case AetherKey.SliceDesiredKey k -> m.desired.get(SlicePair.slicePair(k.nodeId(), k.artifact())).map(v -> (AetherValue) v);
            case AetherKey.SliceObservedKey k -> m.observed.get(SlicePair.slicePair(k.nodeId(), k.artifact())).map(v -> (AetherValue) v);
            case ArtifactJudgmentKey k -> m.judgments.get(k.artifact()).map(v -> (AetherValue) v);
            case AetherKey.NodeDepartureKey k -> m.records.get(k.nodeId()).map(v -> (AetherValue) v);
            case AetherKey.AppBlueprintKey k -> m.blueprints.get(k.blueprintId()).map(v -> (AetherValue) v);
            case AetherKey.NodeRoutesKey k -> m.routes.get(SlicePair.slicePair(k.nodeId(), k.artifact())).map(v -> (AetherValue) v);
            case AetherKey.SliceTargetKey k -> m.targets.get(k.artifactBase()).map(v -> (AetherValue) v);
            case AetherKey.DeploymentApplyKey k -> m.applies.get(k.blueprintId()).map(v -> (AetherValue) v);
            case AetherKey.DeploymentOutcomeKey k -> m.outcomes.get(k.blueprintId()).map(v -> (AetherValue) v);
            case AetherKey.ActivationDirectiveKey k -> m.directives.get(k.nodeId()).map(v -> (AetherValue) v);
            case AetherKey.CommunityKey k -> m.communities.get(k.communityId()).map(v -> (AetherValue) v);
            case AetherKey.SchemaVersionKey k -> m.schemas.get(k.datasourceName()).map(v -> (AetherValue) v);
            default -> Option.none();
        };
    }

    private static boolean namesSelf(Ctx ctx, Option<LeaderValue> leader) {
        return leader.map(value -> value.leader()
                                        .equals(ctx.self()))
                     .or(false);
    }

    private static Ctx metric(Ctx ctx, Submitted submission, String outcome) {
        submission.drafts()
                  .forEach(draft -> ctx.metric("aether_deployment_fsm_submissions_total",
                                               Map.of("fsm",
                                                      "cluster",
                                                      "kind",
                                                      draft.kind().name(),
                                                      "outcome",
                                                      outcome)));

        return ctx;
    }

    private static ClusterModel dropPending(ClusterModel m, TxDraft draft) {
        draft.pairs().forEach(pair -> Indexer.clearPending(m, pair));

        return m;
    }

    /// Whether any mutation of `draft` expects a value other than the one the mirror holds now.
    static boolean moved(ClusterModel m, TxDraft draft) {
        return draft.mutations()
                    .stream()
                    .anyMatch(mutation -> !mutation.expected()
                                                   .equals(mirrored(m,
                                                                    mutation.key()))) || draft.guards()
                                                                                              .stream()
                                                                                              .anyMatch(guard -> !guard.expected()
                                                                                                                       .equals(mirrored(m,
                                                                                                                                        guard.key()).map(v -> (Object) v)));
    }

    private static ClusterModel rederive(ClusterModel m, Ctx ctx, TxDraft draft) {
        return switch (draft.kind()) {
            case DEPARTURE, INFERRED_DEPARTURE, ADMISSION -> DepartureFlow.refused(m, ctx, draft);
            case CLEANUP -> DepartureFlow.cleanupRefused(m, ctx, draft);
            case BLUEPRINT_REMOVAL -> rederiveRemoval(m, draft);
            // 6.2: a refused target batch is not resubmitted at once; the awaited-target sweep (reconcile step 1) re-sends it after its bound.
            case TARGETS -> m;
            default -> rederiveByPair(m, ctx, draft);
        };
    }

    /// 5.7.2 / NT-122: the pairs of a refused goal transaction are split by whether their keys moved. The ones that
    /// did not are resubmitted at once (in this same step), up to the bound; the ones that did are re-derived by the
    /// next reconcile. A single-purpose draft is resubmitted if nothing it expects moved, else dropped.
    /// A refused blueprint removal is re-derived against the mirror's current values: no reconcile step derives it again, because the
    /// blueprint is already unindexed. A key that is gone needs no removal; a key whose value moved is removed at its new value.
    private static ClusterModel rederiveRemoval(ClusterModel m, TxDraft draft) {
        var current = draft.mutations()
                           .stream()
                           .flatMap(mutation -> mirrored(m,
                                                         mutation.key()).map(value -> new KVCommand.Mutation<AetherKey, AetherValue>(mutation.key(),
                                                                                                                                     Option.some(value),
                                                                                                                                     Option.none()))
                                                        .stream())
                           .toList();

        return current.isEmpty() || draft.attempt() >= GOAL_RESUBMISSIONS
               ? m
               : Outbox.direct(m,
                               new TxDraft(draft.kind(),
                                           current.getFirst().key(),
                                           current,
                                           List.of(),
                                           Set.of(),
                                           draft.node(),
                                           draft.attempt() + 1,
                                           List.of()));
    }

    private static ClusterModel rederiveByPair(ClusterModel m, Ctx ctx, TxDraft draft) {
        var limit = draft.kind() == SubmitKind.ROLLBACK
                    ? ROLLBACK_RESUBMISSIONS
                    : GOAL_RESUBMISSIONS;

        return draft.pairs()
                    .isEmpty()
               ? rederiveWhole(m, ctx, draft, limit)
               : rederiveSplit(m, ctx, draft, splitByMovement(m, draft, limit), limit);
    }

    /// The mutations of a refused transaction sorted by whether their pair's keys moved: the ones that did not (`stay`, with their
    /// guards and pairs) are resubmitted, the shared ones ride along, the moved ones are left to the next reconcile.
    record PairSplit(List<KVCommand.Mutation<AetherKey, AetherValue>> stay,
                     List<KVCommand.ReadWitness<AetherKey>> stayGuards,
                     Set<SlicePair> stayPairs,
                     List<KVCommand.Mutation<AetherKey, AetherValue>> shared,
                     boolean movedAny) {}

    private static PairSplit splitByMovement(ClusterModel m, TxDraft draft, int limit) {
        var stay = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();
        var stayGuards = new ArrayList<KVCommand.ReadWitness<AetherKey>>();
        var stayPairs = new HashSet<SlicePair>();
        var shared = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();
        var movedAny = false;

        for (var mutation : draft.mutations()) {
            var pairs = draft.pairs().stream().filter(pair -> pair.desiredKey()
                                                                  .equals(mutation.key())).toList();

            if (pairs.isEmpty()) {
                shared.add(mutation);
                continue;
            }

            var pair = pairs.getFirst();
            var guard = Option.from(draft.guards().stream().filter(g -> g.key()
                                                                         .equals(pair.observedKey())).findFirst());
            var pairMoved = pairMoved(m, mutation, guard);
            // FER: a pair past the resubmission bound is released and re-derived by the next reconcile (6.2); one that moved is re-derived by it.
            if (pairMoved || draft.attempt() >= limit) {
                Indexer.clearPending(m, pair);
                movedAny = movedAny || pairMoved;
                continue;
            }

            stay.add(mutation);
            stayPairs.add(pair);
            guard.onPresent(stayGuards::add);
        }

        return new PairSplit(stay, stayGuards, stayPairs, shared, movedAny);
    }

    private static boolean pairMoved(ClusterModel m,
                                     KVCommand.Mutation<AetherKey, AetherValue> mutation,
                                     Option<KVCommand.ReadWitness<AetherKey>> guard) {
        return ! mutation.expected()
                         .equals(mirrored(m,
                                          mutation.key())) || guard.map(g -> !g.expected()
                                                                               .equals(mirrored(m,
                                                                                                g.key()).map(v -> (Object) v)))
                                                                   .or(false);
    }

    private static ClusterModel rederiveSplit(ClusterModel m, Ctx ctx, TxDraft draft, PairSplit split, int limit) {
        if (split.shared().stream().anyMatch(mutation -> mutatedElsewhere(m, mutation))) {
            split.stayPairs().forEach(pair -> Indexer.clearPending(m, pair));

            return Reconcile.request(m, ctx);
        }

        resubmitRemainder(m, draft, split, limit);

        return split.movedAny()
               ? Reconcile.request(m, ctx)
               : m;
    }

    private static ClusterModel resubmitRemainder(ClusterModel m, TxDraft draft, PairSplit split, int limit) {
        return split.stayPairs()
                    .isEmpty()
               ? resubmitSharedOnly(m, draft, split, limit)
               : resubmitStaying(m, draft, split);
    }

    /// Every pair of the transaction moved but its shared mutations (the artifact's judgment) did not. No reconcile step derives an
    /// event-only operation again (a cleared exclusion, a reset retry count), so they are resubmitted on their own.
    private static ClusterModel resubmitSharedOnly(ClusterModel m, TxDraft draft, PairSplit split, int limit) {
        return split.shared()
                    .isEmpty() || draft.attempt() >= limit
               ? m
               : Outbox.direct(m,
                               new TxDraft(draft.kind(),
                                           split.shared().getFirst().key(),
                                           split.shared(),
                                           List.of(),
                                           Set.of(),
                                           draft.node(),
                                           draft.attempt() + 1,
                                           List.of()));
    }

    private static ClusterModel resubmitStaying(ClusterModel m, TxDraft draft, PairSplit split) {
        var rest = new ArrayList<>(split.stay());

        rest.addAll(split.shared());

        return Outbox.direct(m,
                             new TxDraft(draft.kind(),
                                         rest.getFirst().key(),
                                         rest,
                                         split.stayGuards(),
                                         split.stayPairs(),
                                         draft.node(),
                                         draft.attempt() + 1,
                                         stillStarted(draft, split.stayPairs())));
    }

    /// The events to route on acceptance that still concern a pair of the resubmitted transaction.
    private static List<RoutedEvent> stillStarted(TxDraft draft, Set<SlicePair> pairs) {
        return draft.onAccepted()
                    .stream()
                    .filter(event -> !(event instanceof RoutedEvent.DeploymentStarted started) || pairs.contains(SlicePair.slicePair(started.node(),
                                                                                                                                     started.artifact())))
                    .toList();
    }

    private static boolean mutatedElsewhere(ClusterModel m, KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        return ! mutation.expected()
                         .equals(mirrored(m,
                                          mutation.key()));
    }

    // FER (degrade forward): a single-purpose draft refused past its bound is dropped; the next tick's reconcile derives it again from the
    // mirror (spec 6.2 "then wait for the tick"). Guarantee: a bounded number of immediate retries, then one derivation per tick.
    private static ClusterModel rederiveWhole(ClusterModel m, Ctx ctx, TxDraft draft, int limit) {
        if (!moved(m, draft) && draft.attempt() < limit) {
            return Outbox.direct(m, draft.nextAttempt());
        }

        return moved(m, draft)
               ? Reconcile.request(m, ctx)
               : m;
    }

    /// Failed or unattributable (R-4.3.0, 6.2): a goal transaction that failed clears its pending entries (M-3) and the
    /// next reconcile re-derives; a departure stays in `departing` and is re-read by its timer.
    // FER: a failed write is not retried at once; its pending entries are released and the reconcile `retryLater` queues re-derives it.
    private static ClusterModel failed(ClusterModel m, Ctx ctx, Submitted submission, Settlement.Failed failed) {
        metric(ctx, submission, "failed");
        for (var draft : submission.drafts()) {
            dropPending(m, draft);
            DepartureFlow.failed(m, ctx, draft);
        }

        return retryLater(m, ctx);
    }

    /// A failed write is re-derived by a reconcile one `retryBase` later, never at once: a submission that fails fast would
    /// otherwise make the FSM re-derive and resubmit in a hot loop [guess: the spec says "the next reconcile re-derives"].
    private static ClusterModel retryLater(ClusterModel m, Ctx ctx) {
        var timer = TimerId.timerId(TimerId.Kind.RECONCILE, "write-failed");

        return m.timers.contains(timer)
               ? m
               : Lifecycle.schedule(m,
                                    ctx,
                                    timer,
                                    ctx.settings().timing().retryBase());
    }

    record unused() implements Outbox {}
}
