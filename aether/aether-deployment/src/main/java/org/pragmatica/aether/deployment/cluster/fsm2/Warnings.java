// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// #2834: the committed open set of the cluster FSM's leader-local warning families (D.6), one `FsmWarningKey(code, subject)` per open
/// warning. The family sets of the model (`invalidPlacementWarned` and its siblings) are the desired open set; `warningRows` mirrors the
/// committed one.
/// - Write: a step that raised a warning or a recovery of a family marks the open set dirty; the drain's flush drafts one WARNINGS
///   transaction, the difference between the two, in the same `apply` batch as the drain's other transactions (marked guess: "the same
///   commit" is the same drain). One WARNINGS write is in flight at a time; an accepted one updates the mirror from its mutations, a refused
///   or expired one marks the set dirty again, so the next flush drafts the difference anew. A failed one is re-drafted by the next
///   reconcile pass, which marks the set dirty level-triggered (never at once: a write that fails fast would loop).
/// - Fence: the transaction is a `LeaderTransaction` and `FsmWarningValue` is `LeaderAuthorized`, so a deposed leader's write is refused.
/// - Publish after commit (#3085): a family warning or recovery a step raises is held (`warningsHeld`) and reaches the operator-warning sink
///   only once the WARNINGS write reflecting it is accepted, or the committed mirror already equals the family sets (no row to wait for).
///   A warning `Rebuild` raises after `inherit` is held the same way (`Lifecycle.rebuild`). A leader deposed or crashed before that write
///   commits never publishes the warning: it is dropped with the model, never published without a committed row. The cost: an event-only
///   warning raised in that window is lost (the successor raises it only if the event recurs).
/// - Published marker (#3160): the write that carries a held warning also commits it as unpublished in the warning's row
///   (`FsmWarningValue.opener` / `recovery`, the latest message of each; a key the family closed stays as a tombstone row, `open` false).
///   Once the accepted write's warnings are published, the set is dirty again and the next WARNINGS write clears the markers and removes
///   the tombstones. So when the write commits but the leader processes `Deactivate` (or a reincarnation drops the settlement as stale)
///   before the settlement, the successor's `inherit` publishes what the row names as unpublished, opener or recovery, instead of
///   inheriting an opener nobody published or losing the recovery of a removed row (an opener open for ever). Only marked rows are
///   re-published, never every inherited row (the event aggregator dedups a warning only per node and within 60 s). The cost: a leader
///   lost after publishing and before its clearing write commits has its warnings published again by the successor (a duplicate opener or
///   recovery, once per activation that inherits the marked row, since `inherit` replays at every `Rebuild` and only a leader writes the
///   clear); and when one key changed several times while one write was in flight (stray openers of several nodes included), only the
///   latest opener and recovery messages are replayed. Two further orderings are handled: a settlement that arrives once this node mirrors
///   another leader publishes nothing (`publish`), and a marked row the deposed leader commits after this node's snapshot is replayed
///   like an inherited one (`onPut`). The recoveries `inherit` raises at `Rebuild` are not held: they close committed rows, and
///   a successor deposed before their removal commits leaves the row to the next one, which closes it again (a repeated recovery, never an
///   open warning).
/// - Inherit (`Rebuild`): the family sets are seeded from the committed open rows, so a successor never raises an inherited open again
///   (each family's raise site checks its set), and an inherited warning whose subject left the model between the leaders is closed with
///   [Recoveries#SUBJECT_GONE]. The families' level-triggered checks close what ended otherwise.
public sealed interface Warnings {
    String INVALID_PLACEMENT = "invalid-placement";
    String RETRY_EXHAUSTED = "retry-exhausted-unsettled";
    String SCHEMA_UNKNOWN = "schema-status-unknown";
    String SCHEMA_STALLED = "schema-migration-stalled";
    String STRAY = "stray-instance";
    String SCHEMA_HOLD = "schema-hold";
    String JOIN_UNMATCHED = "join-unmatched";
    String NO_POLICY = "worker-no-community-policy";
    String UNPLACED = Directory.ASSIGNMENT_UNPLACED;
    String UNJUDGEABLE = "worker-absence-unjudgeable";
    String SUPPRESSED = "departure-inferred-suppressed";
    String GROWTH_BLOCKED = Directory.GROWTH_BLOCKED;
    String APPLY_DEADLINE = Deadlines.EXCEEDED;

    /// The twelve leader-local families: each opener code with its recovery code.
    Map<String, String> FAMILIES = Map.ofEntries(Map.entry(INVALID_PLACEMENT, Recoveries.PLACEMENT_VALID),
                                                 Map.entry(RETRY_EXHAUSTED, Recoveries.RETRY_EXHAUSTED_SETTLED),
                                                 Map.entry(SCHEMA_UNKNOWN, Recoveries.SCHEMA_STATUS_READABLE),
                                                 Map.entry(SCHEMA_STALLED, Recoveries.SCHEMA_MIGRATION_PROGRESSED),
                                                 Map.entry(STRAY, Recoveries.STRAY_INSTANCE_REMOVED),
                                                 Map.entry(SCHEMA_HOLD, Recoveries.SCHEMA_HOLD_CLEARED),
                                                 Map.entry(JOIN_UNMATCHED, Recoveries.JOIN_MATCHED),
                                                 Map.entry(NO_POLICY, Recoveries.WORKER_COMMUNITY_POLICY_MATCHED),
                                                 Map.entry(UNPLACED, Directory.ASSIGNMENT_PLACED),
                                                 Map.entry(UNJUDGEABLE, Recoveries.WORKER_ABSENCE_JUDGEABLE),
                                                 Map.entry(SUPPRESSED, Recoveries.DEPARTURE_BREAKER_CLEARED),
                                                 Map.entry(GROWTH_BLOCKED, Directory.GROWTH_RESUMED),
                                                 Map.entry(APPLY_DEADLINE, Recoveries.APPLY_DEADLINE_CLEARED));

    /// #3160: each recovery code with its opener code (the code of the warning's key).
    Map<String, String> OPENERS = FAMILIES.entrySet()
                                          .stream()
                                          .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    /// Every code that opens or closes a warning of a family.
    Set<String> CODES = Stream.concat(FAMILIES.keySet().stream(),
                                      FAMILIES.values().stream())
                              .collect(Collectors.toUnmodifiableSet());

    FsmWarningValue NO_MEMBERS = FsmWarningValue.fsmWarningValue(Map.of());

    // -- the mirror ------------------------------------------------------------------------------------------------
    static ClusterModel indexRow(ClusterModel m, FsmWarningKey key, FsmWarningValue value) {
        m.warningRows = m.warningRows.put(key, value);

        return m;
    }

    static ClusterModel removeRow(ClusterModel m, FsmWarningKey key) {
        m.warningRows = m.warningRows.remove(key);

        return m;
    }

    // -- the write -------------------------------------------------------------------------------------------------
    /// The end of a step: a step that raised a warning or a recovery of a family marks the open set dirty. A dirty set whose difference is
    /// empty is clean again, so a step that changed nothing queues no flush.
    ///
    /// #3085, publish after commit: the family warnings the step's handlers raised (`from` on) leave the step and join `warningsHeld`; what
    /// committed is published now (`publish`). A warning reaches the operator-warning sink only once the open-set row reflecting it is
    /// committed, so a leader deposed (or crashed) before its WARNINGS write commits has published nothing the successor does not inherit.
    static ClusterModel noteRaised(ClusterModel m, Ctx ctx, int from) {
        var raised = ctx.withdraw(from, Warnings::isFamilyWarn);

        if (!raised.isEmpty()) {
            m.warningsDirty = true;
            m.warningsHeld = Stream.concat(m.warningsHeld.stream(), raised.stream()).toList();
        }

        return clean(publish(m, ctx));
    }

    private static ClusterModel clean(ClusterModel m) {
        if (m.warningsDirty && !inFlight(m) && difference(m).isEmpty()) {
            m.warningsDirty = false;
        }

        return m;
    }

    /// The held warnings that committed, in raise order: every one when nothing is in flight and the committed open rows equal the family
    /// sets (a write landed, or the step's changes cancelled out), else the prefix the accepted WARNINGS transaction carried. #3160: what
    /// is published leaves the desired markers, so the set is dirty: the next flush clears the markers its write committed.
    ///
    /// #3160 (late settle): nothing is published while the committed leader this node mirrors names another node. A deposed leader whose
    /// settlement arrives after the successor's `LeaderKey` committed leaves its held warnings to the successor, which replays them from the
    /// markers the accepted write committed (`inherit`, `onPut`). A settlement processed before this node sees the new `LeaderKey` still
    /// publishes, so the window is narrowed, not closed.
    private static ClusterModel publish(ClusterModel m, Ctx ctx) {
        if (m.warningsHeld.isEmpty() || ledByAnother(m, ctx)) {
            return m;
        }

        var count = !inFlight(m) && settled(m)
                    ? m.warningsHeld.size()
                    : m.warningsLanded;

        m.warningsHeld.subList(0, count).forEach(ctx::emit);
        m.warningsHeld = List.copyOf(m.warningsHeld.subList(count, m.warningsHeld.size()));
        m.warningsLanded = 0;
        m.warningsDirty = m.warningsDirty || count > 0;

        return m;
    }

    private static boolean ledByAnother(ClusterModel m, Ctx ctx) {
        return m.leader.map(value -> !value.leader()
                                           .equals(ctx.self()))
                       .or(false);
    }

    /// Whether the committed open rows, markers aside, are what the family sets describe: a held warning then has no row change to wait
    /// for, and publishing it at once leaves no window.
    private static boolean settled(ClusterModel m) {
        return opened(m).equals(committedOpen(m));
    }

    private static Map<FsmWarningKey, FsmWarningValue> committedOpen(ClusterModel m) {
        return m.warningRows.entries()
                            .stream()
                            .filter(entry -> entry.getValue()
                                                  .open())
                            .collect(Collectors.toMap(Map.Entry::getKey,
                                                      entry -> FsmWarningValue.fsmWarningValue(entry.getValue()
                                                                                                    .members())));
    }

    /// Whether the flush has a WARNINGS transaction to draft (`Outbox.hasIntents`).
    static boolean pending(ClusterModel m) {
        return m.warningsDirty && !inFlight(m);
    }

    /// The flush: one WARNINGS transaction with the difference between the family sets and the mirror, unless one is in flight.
    static ClusterModel draft(ClusterModel m) {
        if (!pending(m)) {
            return m;
        }

        var mutations = difference(m);

        m.warningsDirty = false;

        return mutations.isEmpty()
               ? m
               : covering(Outbox.direct(m,
                                        TxDraft.draft(SubmitKind.WARNINGS,
                                                      mutations.getFirst().key(),
                                                      mutations)));
    }

    /// #3085: the drafted transaction carries every warning held so far (the difference is taken from the family sets they changed).
    private static ClusterModel covering(ClusterModel m) {
        m.warningsCovered = m.warningsHeld.size();

        return m;
    }

    /// A flush while suspended submits nothing: the open set is clean until the resume's reconcile marks it dirty again (`reconcile`).
    static ClusterModel suspended(ClusterModel m) {
        m.warningsDirty = false;

        return m;
    }

    /// Every reconcile pass marks the open set dirty: the level-triggered backstop that re-drafts a failed WARNINGS write.
    static ClusterModel reconcile(ClusterModel m) {
        m.warningsDirty = true;

        return m;
    }

    /// An accepted WARNINGS transaction: the mirror takes its mutations now, before the applier's notifications arrive, and the warnings it
    /// carried are published at the end of the step (#3085).
    static ClusterModel accepted(ClusterModel m, TxDraft draft) {
        return draft.kind() == SubmitKind.WARNINGS
               ? draft.mutations()
                      .stream()
                      .reduce(committed(m),
                              Warnings::landed,
                              (left, right) -> right)
               : m;
    }

    private static ClusterModel committed(ClusterModel m) {
        m.warningsLanded = m.warningsCovered;
        m.warningsCovered = 0;

        return m;
    }

    /// A refused or expired WARNINGS transaction: the next flush drafts the difference again, from the mirror the refusal corrected.
    static ClusterModel unlanded(ClusterModel m, TxDraft draft) {
        if (draft.kind() == SubmitKind.WARNINGS) {
            m.warningsDirty = true;
        }

        return m;
    }

    private static boolean isFamilyWarn(ClusterEffect effect) {
        return effect instanceof ClusterEffect.Warn warn && CODES.contains(warn.code());
    }

    private static boolean inFlight(ClusterModel m) {
        return m.outbox.stream()
                       .anyMatch(draft -> draft.kind() == SubmitKind.WARNINGS) || m.submitted.values()
                                                                                             .stream()
                                                                                             .anyMatch(submitted -> submitted.kind() == SubmitKind.WARNINGS);
    }

    private static ClusterModel landed(ClusterModel m, KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        return switch (mutation.key()) {
            case FsmWarningKey key -> mutation.replacement().fold(() -> removeRow(m, key),
                                                                  value -> value instanceof FsmWarningValue row
                                                                           ? indexRow(m, key, row)
                                                                           : m);
            default -> m;
        };
    }

    /// The mutations that make the committed open set equal the family sets, in key order.
    static List<KVCommand.Mutation<AetherKey, AetherValue>> difference(ClusterModel m) {
        var desired = desired(m);
        var opened = desired.entrySet()
                            .stream()
                            .filter(entry -> !m.warningRows.get(entry.getKey())
                                                           .equals(Option.some(entry.getValue())))
                            .map(entry -> mutation(m,
                                                   entry.getKey(),
                                                   Option.some(entry.getValue())));
        var closed = m.warningRows.keys()
                                  .stream()
                                  .filter(key -> !desired.containsKey(key))
                                  .map(key -> mutation(m,
                                                       key,
                                                       Option.none()));

        return Stream.concat(opened, closed)
                     .sorted(Comparator.comparing(mutation -> mutation.key()
                                                                      .asString()))
                     .toList();
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> mutation(ClusterModel m,
                                                                       FsmWarningKey key,
                                                                       Option<AetherValue> replacement) {
        return new KVCommand.Mutation<>(key,
                                        m.warningRows.get(key).map(value -> (AetherValue) value),
                                        replacement);
    }

    /// The committed set the model describes: the open set of the family sets, each row marked with its unpublished held warnings, and a
    /// tombstone for each key the families closed with a held warning still unpublished (#3160).
    static Map<FsmWarningKey, FsmWarningValue> desired(ClusterModel m) {
        var open = opened(m);
        var held = unpublished(m);

        return Stream.concat(open.keySet().stream(),
                             held.keySet().stream())
                     .distinct()
                     .collect(Collectors.toMap(key -> key,
                                               key -> marked(Option.option(open.get(key)),
                                                             key,
                                                             held.getOrDefault(key,
                                                                               List.of()))));
    }

    /// The held warnings by the key of their warning, in raise order.
    private static Map<FsmWarningKey, List<ClusterEffect.Warn>> unpublished(ClusterModel m) {
        return m.warningsHeld.stream()
                             .collect(Collectors.groupingBy(Warnings::keyOf,
                                                            LinkedHashMap::new,
                                                            Collectors.toList()));
    }

    private static FsmWarningKey keyOf(ClusterEffect.Warn warn) {
        return FsmWarningKey.fsmWarningKey(OPENERS.getOrDefault(warn.code(), warn.code()),
                                           warn.subject());
    }

    private static FsmWarningValue marked(Option<FsmWarningValue> open,
                                          FsmWarningKey key,
                                          List<ClusterEffect.Warn> held) {
        return FsmWarningValue.fsmWarningValue(open.map(FsmWarningValue::members).or(Map.of()),
                                               open.isPresent(),
                                               latest(held, key.code()),
                                               latest(held,
                                                      FAMILIES.getOrDefault(key.code(), key.code())));
    }

    private static Option<String> latest(List<ClusterEffect.Warn> held, String code) {
        return Option.from(held.stream().filter(warn -> warn.code()
                                                            .equals(code)).reduce((earlier, later) -> later)).map(ClusterEffect.Warn::message);
    }

    /// The open set the family sets describe.
    private static Map<FsmWarningKey, FsmWarningValue> opened(ClusterModel m) {
        return Stream.of(single(INVALID_PLACEMENT,
                                m.invalidPlacementWarned.toList().stream().map(Artifact::asString)),
                         single(RETRY_EXHAUSTED,
                                m.retryExhaustedWarned.toList().stream().map(Artifact::asString)),
                         single(SCHEMA_UNKNOWN,
                                m.schemaUnknownWarned.toList().stream()),
                         single(SCHEMA_STALLED,
                                m.schemaStalledWarned.toList().stream()),
                         single(JOIN_UNMATCHED,
                                m.unmatchedWarned.toList().stream().map(NodeId::id)),
                         single(NO_POLICY,
                                m.noPolicyWarned.toList().stream().map(NodeId::id)),
                         single(UNPLACED,
                                m.unplacedWarned.toList().stream().map(NodeId::id)),
                         single(UNJUDGEABLE,
                                m.unjudgeableWarned.toList().stream()),
                         single(SUPPRESSED,
                                m.suppressedWarned.toList().stream()),
                         single(GROWTH_BLOCKED,
                                m.growthBlocked.toList().stream()),
                         single(APPLY_DEADLINE,
                                m.applyDeadlineWarned.keys().stream().map(BlueprintId::asString)),
                         strays(m),
                         holds(m))
                     .flatMap(entries -> entries)
                     .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Stream<Map.Entry<FsmWarningKey, FsmWarningValue>> single(String code, Stream<String> subjects) {
        return subjects.map(subject -> Map.entry(FsmWarningKey.fsmWarningKey(code, subject), NO_MEMBERS));
    }

    private static Stream<Map.Entry<FsmWarningKey, FsmWarningValue>> strays(ClusterModel m) {
        return m.strayWarned.entries()
                            .stream()
                            .map(entry -> Map.entry(FsmWarningKey.fsmWarningKey(STRAY,
                                                                                entry.getKey().asString()),
                                                    FsmWarningValue.fsmWarningValue(entry.getValue()
                                                                                         .toList()
                                                                                         .stream()
                                                                                         .collect(Collectors.toMap(NodeId::id,
                                                                                                                   _ -> "")))));
    }

    private static Stream<Map.Entry<FsmWarningKey, FsmWarningValue>> holds(ClusterModel m) {
        return m.heldByArtifact.entries()
                               .stream()
                               .map(entry -> Map.entry(FsmWarningKey.fsmWarningKey(SCHEMA_HOLD,
                                                                                   entry.getKey().asString()),
                                                       FsmWarningValue.fsmWarningValue(signatures(m,
                                                                                                  entry.getKey(),
                                                                                                  entry.getValue()))));
    }

    private static Map<String, String> signatures(ClusterModel m, Artifact artifact, PSet<NodeId> nodes) {
        return nodes.toList()
                    .stream()
                    .collect(Collectors.toMap(NodeId::id,
                                              node -> m.holdSignatures.get(SlicePair.slicePair(node, artifact))
                                                                      .or("")));
    }

    // -- the inheritance (Rebuild) ---------------------------------------------------------------------------------
    /// After the snapshot is indexed: every committed row first publishes what its writer committed but did not publish (#3160), then a
    /// committed open warning is adopted into its family set, or closed with the reason subject-gone when its subject left the model between
    /// the leaders. A tombstone and a row this leader cannot read (an unknown code, a subject that does not parse) join no set, so the next
    /// flush removes them. The set is dirty afterwards: the closes, and the cleared markers, are written by the first flush.
    static ClusterModel inherit(ClusterModel m, Ctx ctx) {
        m.warningRows.entries()
                     .stream()
                     .sorted(Comparator.comparing(entry -> entry.getKey()
                                                                .asString()))
                     .forEach(entry -> inheritRow(replay(m,
                                                         ctx,
                                                         entry.getKey(),
                                                         entry.getValue()),
                                                  ctx,
                                                  entry.getKey(),
                                                  entry.getValue()));
        m.warningsDirty = true;

        return m;
    }

    /// #3160 (a row committed after `Rebuild`): a row put that differs from the mirror while this node is suspended with no WARNINGS write
    /// of its own in flight was written by the deposed leader after this node's snapshot (its leader fence held until this node's
    /// `LeaderKey` committed). The row is treated as `inherit` treats a snapshot row: its markers are replayed and an open row is adopted.
    /// The replayed warnings are raised inside the step, so they are held (`noteRaised`): with this row committed they are settled, and
    /// they reach the sink at the resume (never while another node leads, `publish`); a node deposed before resuming leaves them to the
    /// next successor, which replays the same committed row. A tombstone over a key this node adopted open is not un-adopted: its recovery
    /// is published, and the family's own check closes the key in this node's set (a repeated recovery).
    static ClusterModel onPut(ClusterModel m, Ctx ctx, FsmWarningKey key, FsmWarningValue value) {
        var late = Lifecycle.isSuspended(m, ctx) && !inFlight(m) && !m.warningRows.get(key)
                                                                                 .equals(Option.some(value));

        indexRow(m, key, value);

        return late
               ? inheritRow(replay(m, ctx, key, value), ctx, key, value)
               : m;
    }

    private static ClusterModel inheritRow(ClusterModel m, Ctx ctx, FsmWarningKey key, FsmWarningValue value) {
        return value.open()
               ? adopt(m, ctx, key, value)
               : m;
    }

    /// #3160: publishes the row's unpublished warnings at once (they are committed), in the order that ends in the row's state: an open
    /// row's recovery before its opener, a tombstone's opener before its recovery. A row of an unknown family is not replayed.
    private static ClusterModel replay(ClusterModel m, Ctx ctx, FsmWarningKey key, FsmWarningValue value) {
        if (!FAMILIES.containsKey(key.code())) {
            return m;
        }

        var opener = Map.entry(key.code(), value.opener());
        var recovery = Map.entry(FAMILIES.get(key.code()),
                                 value.recovery());

        (value.open()
         ? Stream.of(recovery, opener)
         : Stream.of(opener, recovery)).forEach(marker -> marker.getValue()
                                                                .onPresent(message -> ctx.warn(marker.getKey(),
                                                                                               key.subject(),
                                                                                               message)));

        return m;
    }

    private static ClusterModel adopt(ClusterModel m, Ctx ctx, FsmWarningKey key, FsmWarningValue value) {
        return switch (key.code()) {
            case INVALID_PLACEMENT -> artifactOf(key).map(artifact -> adoptPlacement(m, ctx, artifact)).or(m);
            case RETRY_EXHAUSTED -> artifactOf(key).map(artifact -> adoptRetry(m, ctx, artifact)).or(m);
            case SCHEMA_UNKNOWN -> adoptSchema(m, ctx, key.subject());
            case SCHEMA_STALLED -> adoptStalled(m, ctx, key.subject());
            case STRAY -> artifactOf(key).map(artifact -> adoptStray(m, ctx, artifact, value)).or(m);
            case SCHEMA_HOLD -> artifactOf(key).map(artifact -> adoptHold(m, ctx, artifact, value)).or(m);
            case JOIN_UNMATCHED -> nodeOf(key).map(node -> adoptUnmatched(m, ctx, node)).or(m);
            case NO_POLICY -> nodeOf(key).map(node -> adoptNoPolicy(m, ctx, node)).or(m);
            case UNPLACED -> nodeOf(key).map(node -> adoptUnplaced(m, ctx, node)).or(m);
            case UNJUDGEABLE -> adoptUnjudgeable(m, key.subject());
            case SUPPRESSED -> adoptSuppressed(m, ctx, key.subject());
            case GROWTH_BLOCKED -> adoptGrowthBlocked(m, key.subject());
            case APPLY_DEADLINE -> BlueprintId.blueprintId(key.subject()).option().map(id -> adoptApplyDeadline(m,
                                                                                                                ctx,
                                                                                                                id)).or(m);
            default -> m;
        };
    }

    private static Option<Artifact> artifactOf(FsmWarningKey key) {
        return Artifact.artifact(key.subject()).option();
    }

    private static Option<NodeId> nodeOf(FsmWarningKey key) {
        return NodeId.nodeId(key.subject()).option();
    }

    /// Row 10: the artifact's target was removed between the leaders, else the placement pass closes it once it parses.
    private static ClusterModel adoptPlacement(ClusterModel m, Ctx ctx, Artifact artifact) {
        if (!m.targets.containsKey(artifact.base())) {
            return gone(m, ctx, INVALID_PLACEMENT, artifact.asString(), "its target was removed between leaders");
        }

        m.invalidPlacementWarned = m.invalidPlacementWarned.add(artifact);

        return m;
    }

    /// Row 14: the artifact's target was removed between the leaders, else the failure sweep closes it once it settles.
    private static ClusterModel adoptRetry(ClusterModel m, Ctx ctx, Artifact artifact) {
        if (!m.targets.containsKey(artifact.base())) {
            return gone(m, ctx, RETRY_EXHAUSTED, artifact.asString(), "its target was removed between leaders");
        }

        m.retryExhaustedWarned = m.retryExhaustedWarned.add(artifact);

        return m;
    }

    /// Row 17: the datasource's record was removed, or its status decodes now; else it stays open.
    private static ClusterModel adoptSchema(ClusterModel m, Ctx ctx, String datasource) {
        var status = m.schemas.get(datasource).map(AetherValue.SchemaVersionValue::status);

        if (status.isEmpty()) {
            return gone(m, ctx, SCHEMA_UNKNOWN, datasource, "its schema record was removed between leaders");
        }

        if (!status.equals(Option.some(SchemaStatus.UNKNOWN))) {
            return close(m,
                         ctx,
                         SCHEMA_UNKNOWN,
                         datasource,
                         "Schema record for datasource " + datasource + ": the status decodes");
        }

        m.schemaUnknownWarned = m.schemaUnknownWarned.add(datasource);

        return m;
    }

    /// #2773: the datasource's record was removed between the leaders (gone); else it stays open, and the successor's stall sweep closes it
    /// once the condition ended. Not raised again (the raise site checks the set).
    private static ClusterModel adoptStalled(ClusterModel m, Ctx ctx, String datasource) {
        if (m.schemas.get(datasource).isEmpty()) {
            return gone(m, ctx, SCHEMA_STALLED, datasource, "its schema record was removed between leaders");
        }

        m.schemaStalledWarned = m.schemaStalledWarned.add(datasource);

        return m;
    }

    /// Row 20: the condemned instances whose observed row is still committed stay open; none left closes it.
    private static ClusterModel adoptStray(ClusterModel m, Ctx ctx, Artifact artifact, FsmWarningValue value) {
        var present = present(m, artifact, value);

        if (present.isEmpty()) {
            return gone(m, ctx, STRAY, artifact.asString(), "the condemned stray instances were removed between leaders");
        }

        present.forEach(node -> m.strayWarned = Idx.add(m.strayWarned, artifact, node));

        return m;
    }

    /// Rows 15/16: the held nodes whose observed row is still committed keep their hold signature; none left closes it. The activation
    /// pass then releases a hold whose migrations completed.
    private static ClusterModel adoptHold(ClusterModel m, Ctx ctx, Artifact artifact, FsmWarningValue value) {
        var present = present(m, artifact, value);

        if (present.isEmpty()) {
            return gone(m, ctx, SCHEMA_HOLD, artifact.asString(), "the held instances were removed between leaders");
        }

        present.forEach(node -> hold(m,
                                     SlicePair.slicePair(node, artifact),
                                     value.members().get(node.id())));

        return m;
    }

    private static ClusterModel hold(ClusterModel m, SlicePair pair, String signature) {
        m.holdSignatures = m.holdSignatures.put(pair, signature);
        m.heldByArtifact = Idx.add(m.heldByArtifact, pair.artifact(), pair.node());

        return m;
    }

    private static List<NodeId> present(ClusterModel m, Artifact artifact, FsmWarningValue value) {
        return value.members()
                    .keySet()
                    .stream()
                    .sorted()
                    .flatMap(id -> NodeId.nodeId(id)
                                         .option()
                                         .stream())
                    .filter(node -> m.observed.containsKey(SlicePair.slicePair(node, artifact)))
                    .toList();
    }

    /// Row 13: the node departed (gone) or holds a committed community-worker directive (matched, marked guess); else it stays open until
    /// its next join evaluation (residual: a live node with no pending join).
    private static ClusterModel adoptUnmatched(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.departed.contains(node)) {
            return gone(m, ctx, JOIN_UNMATCHED, node.id(), "the node departed between leaders");
        }

        if (placedWorker(m, node)) {
            return close(m, ctx, JOIN_UNMATCHED, node.id(), "A join row matched " + node.id() + " between leaders");
        }

        m.unmatchedWarned = m.unmatchedWarned.add(node);

        return m;
    }

    /// Row 22: the worker departed (gone) or holds a committed community-worker directive (matched, marked guess).
    private static ClusterModel adoptNoPolicy(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.departed.contains(node)) {
            return gone(m, ctx, NO_POLICY, node.id(), "the worker departed between leaders");
        }

        if (placedWorker(m, node)) {
            return close(m,
                         ctx,
                         NO_POLICY,
                         node.id(),
                         "A community policy accepted worker " + node.id() + " between leaders");
        }

        m.noPolicyWarned = m.noPolicyWarned.add(node);

        return m;
    }

    /// R-CF.17: the worker departed (gone) or holds a committed community-worker directive (placed, marked guess).
    private static ClusterModel adoptUnplaced(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.departed.contains(node)) {
            return gone(m, ctx, UNPLACED, node.id(), "the worker departed between leaders");
        }

        if (placedWorker(m, node)) {
            return close(m,
                         ctx,
                         UNPLACED,
                         node.id(),
                         "Worker " + node.id() + " was assigned a community between leaders");
        }

        m.unplacedWarned = m.unplacedWarned.add(node);

        return m;
    }

    private static boolean placedWorker(ClusterModel m, NodeId node) {
        return m.directives.get(node)
                           .map(Rows::isCommunityWorker)
                           .or(false);
    }

    /// Row 21: a class label never leaves the model; it closes when a node of the class is judged again.
    private static ClusterModel adoptUnjudgeable(ClusterModel m, String label) {
        m.unjudgeableWarned = m.unjudgeableWarned.add(label);

        return m;
    }

    /// Row 8: the community's record was removed between the leaders, else the departure backstop closes it once the breaker clears.
    private static ClusterModel adoptSuppressed(ClusterModel m, Ctx ctx, String community) {
        if (!m.communities.containsKey(community)) {
            return gone(m, ctx, SUPPRESSED, community, "its community record was removed between leaders");
        }

        m.suppressedWarned = m.suppressedWarned.add(community);

        return m;
    }

    /// R-CF.17: a source label never leaves the model; it closes on the next admission for the source.
    private static ClusterModel adoptGrowthBlocked(ClusterModel m, String source) {
        m.growthBlocked = m.growthBlocked.add(source);

        return m;
    }

    /// R-4.1.7: the blueprint's outcome is still a deadline terminal, else the episode ended between the leaders (a republish, a removal, a
    /// terminal that was in flight when the leader changed) and is closed; a successor's own deadline then raises a new warning.
    private static ClusterModel adoptApplyDeadline(ClusterModel m, Ctx ctx, BlueprintId id) {
        return m.outcomes.get(id)
                         .filter(outcome -> Deadlines.EXCEEDED.equals(outcome.cause()))
                         .map(outcome -> openApplyDeadline(m,
                                                           id,
                                                           outcome.attemptId()))
                         .or(() -> gone(m,
                                        ctx,
                                        APPLY_DEADLINE,
                                        id.asString(),
                                        "its deadline terminal was replaced or removed between leaders"));
    }

    private static ClusterModel openApplyDeadline(ClusterModel m, BlueprintId id, String attempt) {
        m.applyDeadlineWarned = m.applyDeadlineWarned.put(id, attempt);

        return m;
    }

    private static ClusterModel gone(ClusterModel m, Ctx ctx, String code, String subject, String why) {
        return close(m, ctx, code, subject, Recoveries.gone(subject, why));
    }

    private static ClusterModel close(ClusterModel m, Ctx ctx, String code, String subject, String message) {
        ctx.warn(FAMILIES.get(code), subject, message);

        return m;
    }

    record unused() implements Warnings {}
}
