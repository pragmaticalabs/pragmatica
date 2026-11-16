// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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


/// #2834, #3274: the committed open set of the cluster FSM's leader-local warning families (D.6), one `FsmWarningKey(code, subject)` row
/// per key. The operator's open set is the set of committed rows with `open` true; events are notifications, and no reader derives the set
/// from them. The family state of the model (`invalidPlacementWarned` and its siblings) is this leader's intent; `warningRows` mirrors
/// the committed rows.
/// - Evidence (`raise`): the one function that changes a family's state. Each family site calls it unconditionally at its event; it
///   records the evidence (`fresh`), runs the set check (an opener passes iff the (member, value) is not held, a recovery iff the member
///   is held), changes the state and records the text when it passed, and marks the open set dirty.
/// - Rows (rule O, `onPut`, `inherit`): a row with this incarnation's stamp is this node's own: only compared with intent (dirty). Any
///   other row, for each member: with no evidence of this incarnation the row sets the member's state; an earlier self's row (same node,
///   another stamp) is older than the evidence and ignored; another node's row is unordered against it, and the member is held iff the
///   own evidence is open or the row holds it (the open-biased join). A hold a row would cause first runs the family's hold test.
/// - Write (`draft`): one WARNINGS transaction, the difference between intent and the mirror, in flight at a time. A close writes
///   `open` false, no members and the recovery text; no row is ever removed, and every row carries the key's next `revision` and this
///   incarnation's stamp. The transaction is a `LeaderTransaction` and `FsmWarningValue` is `LeaderAuthorized`: a deposed leader's write
///   is refused.
/// - Notification (rule E, `accepted`): a function of one accepted mutation's committed pair `expected -> replacement` alone: the opener
///   with the replacement's message when the replacement holds a (member, value) the expected does not, the recovery when the expected is
///   open and the replacement closed, nothing when a member leaves while another stays.
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

    /// The 13 leader-local families: each opener code with its recovery code.
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

    /// The one member of a single-subject key, and the value of a member that carries none.
    String SINGLE = "";

    /// #3274: one (key, member) of this incarnation's evidence (`ClusterModel.fresh`).
    record Member(FsmWarningKey key, String member) {
        static Member member(FsmWarningKey key, String member) {
            return new Member(key, member);
        }
    }

    /// The held members of a subject in the family state, each with its value (a single-subject family: [#SINGLE] when held).
    @FunctionalInterface
    interface State {
        Map<String, String> held(ClusterModel m, String subject);
    }

    /// Makes a member held with a value in the family state.
    @FunctionalInterface
    interface Hold {
        ClusterModel hold(ClusterModel m, String subject, String member, String value);
    }

    /// Makes a member not held in the family state.
    @FunctionalInterface
    interface Release {
        ClusterModel release(ClusterModel m, String subject, String member);
    }

    /// The family's hold test (today's adoption checks): run when a row would make a member held, against this node's model. Admitted
    /// carries the value the hold takes; refused carries the recovery text the close writes.
    @FunctionalInterface
    interface Test {
        Verdict test(ClusterModel m, String subject, String member, String rowValue);
    }

    sealed interface Verdict {
        record Admitted(String value) implements Verdict {
            static Admitted admitted(String value) {
                return new Admitted(value);
            }
        }

        record Refused(String text) implements Verdict {
            static Refused refused(String text) {
                return new Refused(text);
            }
        }
    }

    /// One family: whether its key has one member per node (`membered`: STRAY, SCHEMA_HOLD), and its state, hold, release and hold test.
    record Family(boolean membered, State state, Hold hold, Release release, Test test) {
        static Family family(boolean membered, State state, Hold hold, Release release, Test test) {
            return new Family(membered, state, hold, release, test);
        }
    }

    /// The one family table, by opener code (the shape of the adoption switch it replaces).
    Map<String, Family> TABLE = Map.ofEntries(Map.entry(INVALID_PLACEMENT,
                                                        set(m -> m.invalidPlacementWarned,
                                                            (m, set) -> m.invalidPlacementWarned = set,
                                                            Warnings::artifactOf,
                                                            (m, subject, _, _) -> targetTest(m, subject))),
                                              Map.entry(RETRY_EXHAUSTED,
                                                        set(m -> m.retryExhaustedWarned,
                                                            (m, set) -> m.retryExhaustedWarned = set,
                                                            Warnings::artifactOf,
                                                            (m, subject, _, _) -> targetTest(m, subject))),
                                              Map.entry(SCHEMA_UNKNOWN,
                                                        set(m -> m.schemaUnknownWarned,
                                                            (m, set) -> m.schemaUnknownWarned = set,
                                                            Option::some,
                                                            (m, subject, _, _) -> schemaTest(m, subject))),
                                              Map.entry(SCHEMA_STALLED,
                                                        set(m -> m.schemaStalledWarned,
                                                            (m, set) -> m.schemaStalledWarned = set,
                                                            Option::some,
                                                            (m, subject, _, _) -> stalledTest(m, subject))),
                                              Map.entry(STRAY,
                                                        Family.family(true,
                                                                      Warnings::strays,
                                                                      Warnings::holdStray,
                                                                      Warnings::releaseStray,
                                                                      (m, subject, member, _) -> observedTest(m,
                                                                                                              subject,
                                                                                                              member,
                                                                                                              SINGLE,
                                                                                                              "the condemned stray instances were removed between leaders"))),
                                              Map.entry(SCHEMA_HOLD,
                                                        Family.family(true,
                                                                      Warnings::holds,
                                                                      Warnings::holdSchema,
                                                                      Warnings::releaseSchema,
                                                                      (m, subject, member, value) -> observedTest(m,
                                                                                                                  subject,
                                                                                                                  member,
                                                                                                                  value,
                                                                                                                  "the held instances were removed between leaders"))),
                                              Map.entry(JOIN_UNMATCHED,
                                                        set(m -> m.unmatchedWarned,
                                                            (m, set) -> m.unmatchedWarned = set,
                                                            Warnings::nodeOf,
                                                            (m, subject, _, _) -> workerTest(m,
                                                                                             subject,
                                                                                             "the node departed between leaders",
                                                                                             "A join row matched " + subject
                                                                                            + " between leaders"))),
                                              Map.entry(NO_POLICY,
                                                        set(m -> m.noPolicyWarned,
                                                            (m, set) -> m.noPolicyWarned = set,
                                                            Warnings::nodeOf,
                                                            (m, subject, _, _) -> workerTest(m,
                                                                                             subject,
                                                                                             "the worker departed between leaders",
                                                                                             "A community policy accepted worker " + subject
                                                                                            + " between leaders"))),
                                              Map.entry(UNPLACED,
                                                        set(m -> m.unplacedWarned,
                                                            (m, set) -> m.unplacedWarned = set,
                                                            Warnings::nodeOf,
                                                            (m, subject, _, _) -> workerTest(m,
                                                                                             subject,
                                                                                             "the worker departed between leaders",
                                                                                             "Worker " + subject
                                                                                            + " was assigned a community between leaders"))),
                                              Map.entry(UNJUDGEABLE,
                                                        set(m -> m.unjudgeableWarned,
                                                            (m, set) -> m.unjudgeableWarned = set,
                                                            Option::some,
                                                            (_, _, _, _) -> Verdict.Admitted.admitted(SINGLE))),
                                              Map.entry(SUPPRESSED,
                                                        set(m -> m.suppressedWarned,
                                                            (m, set) -> m.suppressedWarned = set,
                                                            Option::some,
                                                            (m, subject, _, _) -> suppressedTest(m, subject))),
                                              Map.entry(GROWTH_BLOCKED,
                                                        set(m -> m.growthBlocked,
                                                            (m, set) -> m.growthBlocked = set,
                                                            Option::some,
                                                            (_, _, _, _) -> Verdict.Admitted.admitted(SINGLE))),
                                              Map.entry(APPLY_DEADLINE,
                                                        Family.family(false,
                                                                      Warnings::deadlines,
                                                                      Warnings::holdDeadline,
                                                                      Warnings::releaseDeadline,
                                                                      (m, subject, _, _) -> deadlineTest(m, subject))));

    // -- the evidence ----------------------------------------------------------------------------------------------
    /// #3274: the one function that changes a family's state. `code` is the family's opener or recovery code; `member` is the node of a
    /// membered family (STRAY, SCHEMA_HOLD) and [#SINGLE] otherwise; `value` is the hold signature of SCHEMA_HOLD, the attempt of
    /// APPLY_DEADLINE (the state's value, not compared by the opener check), [#SINGLE] otherwise. In order: record the evidence, run the
    /// set check, change the state and record the text when it passed, mark dirty. A recurrence on a held member and a recovery on an
    /// unheld one are evidence too.
    static ClusterModel raise(ClusterModel m, String code, String subject, String member, String value, String text) {
        var opening = FAMILIES.containsKey(code);
        var key = FsmWarningKey.fsmWarningKey(opening
                                              ? code
                                              : OPENERS.getOrDefault(code, code),
                                              subject);

        return Option.option(TABLE.get(key.code()))
                     .map(family -> raised(m, family, key, member, value, text, opening))
                     .or(m);
    }

    private static ClusterModel raised(ClusterModel m,
                                       Family family,
                                       FsmWarningKey key,
                                       String member,
                                       String value,
                                       String text,
                                       boolean opening) {
        var current = Option.option(family.state().held(m, key.subject()).get(member));

        m.fresh = m.fresh.put(Member.member(key, member), opening);
        m.warningsDirty = true;
        if (opening && !holdsValue(family, current, value)) {
            return opened(family.hold().hold(m, key.subject(), member, value),
                          key,
                          text);
        }

        return ! opening && current.isPresent()
               ? closed(family.release().release(m, key.subject(), member),
                        family,
                        key,
                        text)
               : m;
    }

    /// The opener check: a membered family compares the value too (a new hold signature is a new opener); a single-subject one only
    /// whether it is held.
    private static boolean holdsValue(Family family, Option<String> current, String value) {
        return family.membered()
               ? current.equals(Option.some(value))
               : current.isPresent();
    }

    private static ClusterModel opened(ClusterModel m, FsmWarningKey key, String text) {
        m.openText = m.openText.put(key, text);
        m.closeText = m.closeText.remove(key);

        return m;
    }

    private static ClusterModel closed(ClusterModel m, Family family, FsmWarningKey key, String text) {
        m.closeText = m.closeText.put(key, text);
        if (!keyHeld(m, family, key)) {
            m.openText = m.openText.remove(key);
        }

        return m;
    }

    private static boolean keyHeld(ClusterModel m, Family family, FsmWarningKey key) {
        return ! family.state()
                       .held(m,
                             key.subject())
                       .isEmpty();
    }

    // -- the mirror and rule O -------------------------------------------------------------------------------------
    /// The mirror takes a committed row. A closed row of any writer drops the key's recovery text: the close it was waiting for is written.
    static ClusterModel indexRow(ClusterModel m, FsmWarningKey key, FsmWarningValue value) {
        m.warningRows = m.warningRows.put(key, value);
        if (!value.open()) {
            m.closeText = m.closeText.remove(key);
        }

        return m;
    }

    /// The FSM never removes a row; a removal another writer committed leaves the mirror only.
    static ClusterModel removeRow(ClusterModel m, FsmWarningKey key) {
        m.warningRows = m.warningRows.remove(key);

        return m;
    }

    /// #3274: a committed row put: `indexRow`, then rule O per member. Every row indexed while Active marks the set dirty, own or not.
    static ClusterModel onPut(ClusterModel m, Ctx ctx, FsmWarningKey key, FsmWarningValue value) {
        indexRow(m, key, value);
        m.warningsDirty = true;

        return own(m, ctx, value)
               ? m
               : ruleO(m, ctx, key, value);
    }

    /// #3274: `Rebuild` emptied the evidence; rule O over the snapshot seeds the family state from the committed rows (a refused hold
    /// closes its key with the test's text). Dirty afterwards: the first flush writes what the state says.
    static ClusterModel inherit(ClusterModel m, Ctx ctx) {
        m.warningRows.entries()
                     .stream()
                     .sorted(Comparator.comparing(entry -> entry.getKey()
                                                                .asString()))
                     .forEach(entry -> ruleO(m,
                                             ctx,
                                             entry.getKey(),
                                             entry.getValue()));
        m.warningsDirty = true;

        return m;
    }

    /// A row with this incarnation's stamp: the node's own row.
    private static boolean own(ClusterModel m, Ctx ctx, FsmWarningValue row) {
        return row.writerNode()
                  .equals(ctx.self())
               && row.writerProcess()
                     .equals(ctx.settings().process())
               && row.writerIncarnation() == m.incarnation;
    }

    /// Rule O, one comparison per member: the row's members and the members the state holds. Then the texts: an open row that leaves the
    /// key held (applied or joined) gives `openText`; a key left with no held member drops it, and gets the last refusal's text.
    private static ClusterModel ruleO(ClusterModel m, Ctx ctx, FsmWarningKey key, FsmWarningValue row) {
        return Option.option(TABLE.get(key.code()))
                     .map(family -> applied(m, ctx, family, key, row))
                     .or(m);
    }

    private static ClusterModel applied(ClusterModel m,
                                        Ctx ctx,
                                        Family family,
                                        FsmWarningKey key,
                                        FsmWarningValue row) {
        var rowHeld = rowHeld(family, row);
        var members = new TreeSet<>(rowHeld.keySet());
        var earlierSelf = row.writerNode().equals(ctx.self());
        var refusals = new ArrayList<String>();

        members.addAll(family.state().held(m, key.subject()).keySet());
        members.forEach(member -> member(m,
                                         family,
                                         key,
                                         member,
                                         Option.option(rowHeld.get(member)),
                                         earlierSelf,
                                         refusals));

        return texts(m, family, key, row, used(m, key, members, earlierSelf), refusals);
    }

    /// The members a row holds, each with its value: none on a closed row; [#SINGLE] on an open single-subject row.
    private static Map<String, String> rowHeld(Family family, FsmWarningValue row) {
        if (!row.open()) {
            return Map.of();
        }

        return family.membered()
               ? row.members()
               : Map.of(SINGLE, SINGLE);
    }

    /// The item 1 table of the design: no evidence, the row's state; an earlier self's row, ignored; another node's row, the open-biased
    /// join (held iff the own evidence is open or the row holds the member).
    ///
    /// Owner question (unordered evidence, R3): this is the one place where "open wins" is decided. Under "own evidence wins" the
    /// another-node branch below would return `m` for a closed own evidence instead of joining an open row.
    private static ClusterModel member(ClusterModel m,
                                       Family family,
                                       FsmWarningKey key,
                                       String member,
                                       Option<String> rowValue,
                                       boolean earlierSelf,
                                       List<String> refusals) {
        var evidence = m.fresh.get(Member.member(key, member));

        if (evidence.isEmpty()) {
            return rowState(m, family, key, member, rowValue, refusals);
        }

        if (earlierSelf || evidence.equals(Option.some(true))) {
            return m;
        }

        return rowState(m, family, key, member, rowValue, refusals);
    }

    /// The member takes the row's state: held (through the hold test) iff the row holds it.
    private static ClusterModel rowState(ClusterModel m,
                                         Family family,
                                         FsmWarningKey key,
                                         String member,
                                         Option<String> rowValue,
                                         List<String> refusals) {
        return rowValue.map(value -> admit(m, family, key, member, value, refusals))
                       .or(() -> family.release()
                                       .release(m,
                                                key.subject(),
                                                member));
    }

    /// The hold test: an admitted hold takes the row's value (APPLY_DEADLINE: the outcome's attempt); a refused one leaves the member not
    /// held and records the test's text.
    private static ClusterModel admit(ClusterModel m,
                                      Family family,
                                      FsmWarningKey key,
                                      String member,
                                      String rowValue,
                                      List<String> refusals) {
        return switch (family.test()
                             .test(m,
                                   key.subject(),
                                   member,
                                   rowValue)) {
            case Verdict.Admitted admitted -> family.hold().hold(m, key.subject(), member, admitted.value());
            case Verdict.Refused refused -> refused(family.release().release(m, key.subject(), member),
                                                    refused.text(),
                                                    refusals);
        };
    }

    private static ClusterModel refused(ClusterModel m, String text, List<String> refusals) {
        refusals.add(text);

        return m;
    }

    /// Whether the row was applied or joined on some member (not ignored on every member as an earlier self's).
    private static boolean used(ClusterModel m, FsmWarningKey key, Set<String> members, boolean earlierSelf) {
        return ! earlierSelf || members.stream()
                                       .anyMatch(member -> m.fresh.get(Member.member(key, member))
                                                                  .isEmpty());
    }

    private static ClusterModel texts(ClusterModel m,
                                      Family family,
                                      FsmWarningKey key,
                                      FsmWarningValue row,
                                      boolean used,
                                      List<String> refusals) {
        if (keyHeld(m, family, key)) {
            return row.open() && used
                   ? opened(m, key, row.message())
                   : m;
        }

        m.openText = m.openText.remove(key);
        if (!refusals.isEmpty()) {
            m.closeText = m.closeText.put(key, refusals.getLast());
        }

        return m;
    }

    // -- the write -------------------------------------------------------------------------------------------------
    /// The end of a step: a dirty set whose difference is empty with nothing in flight is clean again, so a step that changed nothing
    /// queues no flush.
    static ClusterModel clean(ClusterModel m, Ctx ctx) {
        if (m.warningsDirty && !inFlight(m) && difference(m, ctx).isEmpty()) {
            m.warningsDirty = false;
        }

        return m;
    }

    /// Whether the flush has a WARNINGS transaction to draft (`Outbox.hasIntents`).
    static boolean pending(ClusterModel m) {
        return m.warningsDirty && !inFlight(m);
    }

    /// The flush: one WARNINGS transaction with the difference between the family state and the mirror, unless one is in flight.
    static ClusterModel draft(ClusterModel m, Ctx ctx) {
        if (!pending(m)) {
            return m;
        }

        var mutations = difference(m, ctx);

        m.warningsDirty = false;

        return mutations.isEmpty()
               ? m
               : Outbox.direct(m,
                               TxDraft.draft(SubmitKind.WARNINGS,
                                             mutations.getFirst().key(),
                                             mutations));
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

    /// An accepted WARNINGS transaction: each mutation's committed pair gives its notification (rule E), and the mirror takes the
    /// mutations now, before the applier's notifications arrive.
    static ClusterModel accepted(ClusterModel m, Ctx ctx, TxDraft draft) {
        if (draft.kind() != SubmitKind.WARNINGS) {
            return m;
        }

        draft.mutations().forEach(mutation -> landed(notified(m, ctx, mutation), mutation));

        return m;
    }

    /// A refused or expired WARNINGS transaction: the next flush drafts the difference again, from the mirror the refusal corrected.
    static ClusterModel unlanded(ClusterModel m, TxDraft draft) {
        if (draft.kind() == SubmitKind.WARNINGS) {
            m.warningsDirty = true;
        }

        return m;
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

    // -- rule E ----------------------------------------------------------------------------------------------------
    /// Rule E: the opener with the replacement's message when the replacement holds a (member, value) the expected does not; the recovery
    /// when the expected is open and the replacement closed; nothing otherwise. A code with no family notifies nothing.
    private static ClusterModel notified(ClusterModel m, Ctx ctx, KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        if (! (mutation.key() instanceof FsmWarningKey key) || !TABLE.containsKey(key.code())) {
            return m;
        }

        var family = TABLE.get(key.code());
        var expected = rowOf(mutation.expected());
        var replacement = rowOf(mutation.replacement());
        var before = expected.map(row -> rowHeld(family, row)).or(Map.of());
        var after = replacement.map(row -> rowHeld(family, row)).or(Map.of());

        if (after.entrySet()
                 .stream()
                 .anyMatch(entry -> !Option.option(before.get(entry.getKey())).equals(Option.some(entry.getValue())))) {
            replacement.onPresent(row -> ctx.warn(key.code(), key.subject(), row.message()));
        } else if (!before.isEmpty() && after.isEmpty()) {
            replacement.onPresent(row -> ctx.warn(FAMILIES.get(key.code()),
                                                  key.subject(),
                                                  row.message()));
        }

        return m;
    }

    private static Option<FsmWarningValue> rowOf(Option<AetherValue> value) {
        return value.filter(FsmWarningValue.class::isInstance)
                    .map(FsmWarningValue.class::cast);
    }

    /// The mutations that make the committed rows say what the family state says, in key order. Rows compare by content, never by
    /// revision or writer. A mirrored open row the state does not hold is closed (a code with no family included); no key is removed.
    static List<KVCommand.Mutation<AetherKey, AetherValue>> difference(ClusterModel m, Ctx ctx) {
        var desired = desired(m, ctx);
        var opened = desired.entrySet()
                            .stream()
                            .filter(entry -> !same(m.warningRows.get(entry.getKey()),
                                                   entry.getValue()))
                            .map(entry -> put(m,
                                              entry.getKey(),
                                              entry.getValue()));
        var closed = m.warningRows.entries()
                                  .stream()
                                  .filter(entry -> !desired.containsKey(entry.getKey()) && entry.getValue()
                                                                                                .open())
                                  .map(entry -> put(m,
                                                    entry.getKey(),
                                                    closeRow(m,
                                                             ctx,
                                                             entry.getKey())));

        return Stream.concat(opened, closed)
                     .sorted(Comparator.comparing(mutation -> mutation.key()
                                                                      .asString()))
                     .toList();
    }

    private static boolean same(Option<FsmWarningValue> mirrored, FsmWarningValue wanted) {
        return mirrored.map(row -> row.sameContent(wanted))
                       .or(false);
    }

    /// A close: `open` false, no members, the recovery text (`closeText`; else the recovery code and the subject, a marked guess: the
    /// design names no fallback for a close with no recorded text, e.g. a code with no family).
    private static FsmWarningValue closeRow(ClusterModel m, Ctx ctx, FsmWarningKey key) {
        return stamped(m,
                       ctx,
                       Map.of(),
                       false,
                       m.closeText.get(key)
                                  .or(() -> FAMILIES.getOrDefault(key.code(),
                                                                  key.code()) + ": " + key.subject()));
    }

    private static FsmWarningValue stamped(ClusterModel m,
                                           Ctx ctx,
                                           Map<String, String> members,
                                           boolean open,
                                           String message) {
        return FsmWarningValue.fsmWarningValue(members,
                                               open,
                                               message,
                                               0L,
                                               ctx.self(),
                                               ctx.settings().process(),
                                               m.incarnation);
    }

    /// Every row written carries the next revision of the key, so it never equals a value the key held before.
    private static KVCommand.Mutation<AetherKey, AetherValue> put(ClusterModel m,
                                                                  FsmWarningKey key,
                                                                  FsmWarningValue row) {
        var mirrored = m.warningRows.get(key);

        return new KVCommand.Mutation<>(key,
                                        mirrored.map(value -> (AetherValue) value),
                                        Option.some(FsmWarningValue.fsmWarningValue(row.members(),
                                                                                    row.open(),
                                                                                    row.message(),
                                                                                    mirrored.map(FsmWarningValue::revision)
                                                                                            .or(0L) + 1,
                                                                                    row.writerNode(),
                                                                                    row.writerProcess(),
                                                                                    row.writerIncarnation())));
    }

    /// The open rows the family state describes, each with its opener text (`openText`; the code and the subject for a key opened with
    /// no raise and no row).
    static Map<FsmWarningKey, FsmWarningValue> desired(ClusterModel m, Ctx ctx) {
        return TABLE.entrySet()
                    .stream()
                    .flatMap(entry -> openKeys(m,
                                               entry.getKey(),
                                               entry.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey,
                                              entry -> stamped(m,
                                                               ctx,
                                                               entry.getValue(),
                                                               true,
                                                               m.openText.get(entry.getKey())
                                                                         .or(() -> entry.getKey()
                                                                                        .code()
                                                                                  + ": " + entry.getKey()
                                                                                                .subject()))));
    }

    /// The held keys of one family with their row members (a single-subject key: none).
    private static Stream<Map.Entry<FsmWarningKey, Map<String, String>>> openKeys(ClusterModel m,
                                                                                  String code,
                                                                                  Family family) {
        return subjects(m, code).stream()
                       .map(subject -> Map.entry(FsmWarningKey.fsmWarningKey(code, subject),
                                                 family.membered()
                                                 ? family.state()
                                                         .held(m, subject)
                                                 : Map.<String, String> of()));
    }

    private static List<String> subjects(ClusterModel m, String code) {
        return switch (code) {
            case INVALID_PLACEMENT -> names(m.invalidPlacementWarned.toList(), Artifact::asString);
            case RETRY_EXHAUSTED -> names(m.retryExhaustedWarned.toList(), Artifact::asString);
            case SCHEMA_UNKNOWN -> m.schemaUnknownWarned.toList();
            case SCHEMA_STALLED -> m.schemaStalledWarned.toList();
            case STRAY -> names(m.strayWarned.keys(), Artifact::asString);
            case SCHEMA_HOLD -> names(m.heldByArtifact.keys(), Artifact::asString);
            case JOIN_UNMATCHED -> names(m.unmatchedWarned.toList(), NodeId::id);
            case NO_POLICY -> names(m.noPolicyWarned.toList(), NodeId::id);
            case UNPLACED -> names(m.unplacedWarned.toList(), NodeId::id);
            case UNJUDGEABLE -> m.unjudgeableWarned.toList();
            case SUPPRESSED -> m.suppressedWarned.toList();
            case GROWTH_BLOCKED -> m.growthBlocked.toList();
            case APPLY_DEADLINE -> names(m.applyDeadlineWarned.keys(), BlueprintId::asString);
            default -> List.of();
        };
    }

    private static <T> List<String> names(List<T> subjects, Function<T, String> name) {
        return subjects.stream()
                       .map(name)
                       .toList();
    }

    // -- the family table's parts ----------------------------------------------------------------------------------
    /// A single-subject family whose state is a set of parsed subjects.
    private static <T> Family set(Function<ClusterModel, PSet<T>> read,
                                  BiConsumer<ClusterModel, PSet<T>> write,
                                  Function<String, Option<T>> parse,
                                  Test test) {
        return Family.family(false,
                             (m, subject) -> parse.apply(subject)
                                                  .filter(read.apply(m)::contains)
                                                  .map(_ -> Map.of(SINGLE, SINGLE))
                                                  .or(Map.of()),
                             (m, subject, _, _) -> changed(m,
                                                           parse.apply(subject),
                                                           parsed -> write.accept(m,
                                                                                  read.apply(m).add(parsed))),
                             (m, subject, _) -> changed(m,
                                                        parse.apply(subject),
                                                        parsed -> write.accept(m,
                                                                               read.apply(m).remove(parsed))),
                             (m, subject, member, value) -> parse.apply(subject)
                                                                 .map(_ -> test.test(m, subject, member, value))
                                                                 .or(() -> unparsed(subject)));
    }

    private static <T> ClusterModel changed(ClusterModel m, Option<T> parsed, Consumer<T> change) {
        parsed.onPresent(change);

        return m;
    }

    /// A subject the family cannot parse: no site can raise or close it, so holding it would be an open no family ever closes.
    private static Verdict unparsed(String subject) {
        return Verdict.Refused.refused(Recoveries.gone(subject, "the subject does not parse"));
    }

    private static Option<Artifact> artifactOf(String subject) {
        return Artifact.artifact(subject).option();
    }

    private static Option<NodeId> nodeOf(String subject) {
        return NodeId.nodeId(subject).option();
    }

    private static Option<SlicePair> pairOf(String subject, String member) {
        return Option.all(artifactOf(subject), nodeOf(member)).map((artifact, node) -> SlicePair.slicePair(node,
                                                                                                           artifact));
    }

    private static Map<String, String> strays(ClusterModel m, String subject) {
        return artifactOf(subject).map(artifact -> Idx.members(m.strayWarned, artifact)
                                                      .toList()
                                                      .stream()
                                                      .collect(Collectors.toMap(NodeId::id,
                                                                                _ -> SINGLE,
                                                                                (left, _) -> left,
                                                                                TreeMap::new)))
                         .map(members -> (Map<String, String>) members)
                         .or(Map.of());
    }

    private static ClusterModel holdStray(ClusterModel m, String subject, String member, String value) {
        return changed(m,
                       pairOf(subject, member),
                       pair -> m.strayWarned = Idx.add(m.strayWarned, pair.artifact(), pair.node()));
    }

    private static ClusterModel releaseStray(ClusterModel m, String subject, String member) {
        return changed(m,
                       pairOf(subject, member),
                       pair -> m.strayWarned = Idx.remove(m.strayWarned, pair.artifact(), pair.node()));
    }

    private static Map<String, String> holds(ClusterModel m, String subject) {
        return artifactOf(subject).map(artifact -> signatures(m, artifact))
                         .or(Map.of());
    }

    private static Map<String, String> signatures(ClusterModel m, Artifact artifact) {
        return Idx.members(m.heldByArtifact, artifact)
                  .toList()
                  .stream()
                  .collect(Collectors.toMap(NodeId::id,
                                            node -> m.holdSignatures.get(SlicePair.slicePair(node, artifact))
                                                                    .or(SINGLE),
                                            (left, _) -> left,
                                            TreeMap::new));
    }

    private static ClusterModel holdSchema(ClusterModel m, String subject, String member, String value) {
        return changed(m, pairOf(subject, member), pair -> heldPair(m, pair, value));
    }

    private static ClusterModel heldPair(ClusterModel m, SlicePair pair, String signature) {
        m.holdSignatures = m.holdSignatures.put(pair, signature);
        m.heldByArtifact = Idx.add(m.heldByArtifact, pair.artifact(), pair.node());

        return m;
    }

    private static ClusterModel releaseSchema(ClusterModel m, String subject, String member) {
        return changed(m, pairOf(subject, member), pair -> releasedPair(m, pair));
    }

    private static ClusterModel releasedPair(ClusterModel m, SlicePair pair) {
        m.holdSignatures = m.holdSignatures.remove(pair);
        m.heldByArtifact = Idx.remove(m.heldByArtifact, pair.artifact(), pair.node());

        return m;
    }

    private static Map<String, String> deadlines(ClusterModel m, String subject) {
        return blueprintOf(subject).flatMap(m.applyDeadlineWarned::get)
                          .map(attempt -> Map.of(SINGLE, attempt))
                          .or(Map.of());
    }

    private static ClusterModel holdDeadline(ClusterModel m, String subject, String member, String attempt) {
        return changed(m, blueprintOf(subject), id -> m.applyDeadlineWarned = m.applyDeadlineWarned.put(id, attempt));
    }

    private static ClusterModel releaseDeadline(ClusterModel m, String subject, String member) {
        return changed(m, blueprintOf(subject), id -> m.applyDeadlineWarned = m.applyDeadlineWarned.remove(id));
    }

    private static Option<BlueprintId> blueprintOf(String subject) {
        return BlueprintId.blueprintId(subject).option();
    }

    // -- the hold tests (today's adoption checks) ------------------------------------------------------------------
    /// Rows 10 and 14: the artifact's target was removed between the leaders.
    private static Verdict targetTest(ClusterModel m, String subject) {
        return artifactOf(subject).filter(artifact -> m.targets.containsKey(artifact.base()))
                         .map(_ -> admitted())
                         .or(() -> gone(subject, "its target was removed between leaders"));
    }

    /// Row 17: the datasource's record was removed, or its status decodes now.
    private static Verdict schemaTest(ClusterModel m, String datasource) {
        var status = m.schemas.get(datasource).map(AetherValue.SchemaVersionValue::status);

        if (status.isEmpty()) {
            return gone(datasource, "its schema record was removed between leaders");
        }

        return status.equals(Option.some(SchemaStatus.UNKNOWN))
               ? admitted()
               : Verdict.Refused.refused("Schema record for datasource " + datasource + ": the status decodes");
    }

    /// #2773: the datasource's record was removed between the leaders.
    private static Verdict stalledTest(ClusterModel m, String datasource) {
        return m.schemas.containsKey(datasource)
               ? admitted()
               : gone(datasource, "its schema record was removed between leaders");
    }

    /// Rows 15/16 and 20, per member: the pair's observed row is still committed. A SCHEMA_HOLD hold keeps the row's signature.
    private static Verdict observedTest(ClusterModel m, String subject, String member, String value, String why) {
        return pairOf(subject, member).map(pair -> m.observed.containsKey(pair)
                                                   ? Verdict.Admitted.admitted(value)
                                                   : gone(subject, why))
                     .or(() -> unparsed(subject));
    }

    /// Rows 13, 22 and R-CF.17: the node departed (gone) or holds a committed community-worker directive (closed, marked guess).
    private static Verdict workerTest(ClusterModel m, String subject, String departed, String placed) {
        return nodeOf(subject).map(node -> workerVerdict(m, node, subject, departed, placed))
                     .or(() -> unparsed(subject));
    }

    private static Verdict workerVerdict(ClusterModel m, NodeId node, String subject, String departed, String placed) {
        if (m.departed.contains(node)) {
            return gone(subject, departed);
        }

        return placedWorker(m, node)
               ? Verdict.Refused.refused(placed)
               : admitted();
    }

    private static boolean placedWorker(ClusterModel m, NodeId node) {
        return m.directives.get(node)
                           .map(Rows::isCommunityWorker)
                           .or(false);
    }

    /// Row 8: the community's record was removed between the leaders.
    private static Verdict suppressedTest(ClusterModel m, String community) {
        return m.communities.containsKey(community)
               ? admitted()
               : gone(community, "its community record was removed between leaders");
    }

    /// R-4.1.7: the blueprint's outcome is still a deadline terminal; the hold takes its attempt.
    private static Verdict deadlineTest(ClusterModel m, String subject) {
        return blueprintOf(subject).flatMap(m.outcomes::get)
                          .filter(outcome -> Deadlines.EXCEEDED.equals(outcome.cause()))
                          .map(outcome -> (Verdict) Verdict.Admitted.admitted(outcome.attemptId()))
                          .or(() -> gone(subject, "its deadline terminal was replaced or removed between leaders"));
    }

    private static Verdict admitted() {
        return Verdict.Admitted.admitted(SINGLE);
    }

    private static Verdict gone(String subject, String why) {
        return Verdict.Refused.refused(Recoveries.gone(subject, why));
    }

    record unused() implements Warnings {}
}
