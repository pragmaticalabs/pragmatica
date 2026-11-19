// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaReach;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.Test;
import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #3274: the committed rows as the open set, written from design `warnpub-redesign.md` §0 (rule O, the item 1 table, the hold-test
/// table, the value rule and rule E), not from the code. Each test names the table row it pins. The model is driven through the
/// `Warnings` entry points the FSM calls: `raise` (evidence), `onPut` (a committed row put while Active), `inherit` (rule O over the
/// snapshot), `difference` (the flush) and `accepted` (the settlement of a WARNINGS write).
///
/// Stamps: this node is `FsmHarness.SELF` at incarnation 2; an earlier self is `SELF` at incarnation 1; another node is `CORE_2`. Both
/// nodes run with default `Settings`, whose process identity is the same `UNKNOWN_PROCESS`, so the stamps differ by node only.
class WarningsCommittedRowsTest {
    private static final long INCARNATION = 2L;
    private static final String SUBJECT = "c1";
    private static final FsmWarningKey KEY = FsmWarningKey.fsmWarningKey(Warnings.UNJUDGEABLE, SUBJECT);
    private static final String RECOVERY = Warnings.FAMILIES.get(Warnings.UNJUDGEABLE);
    private static final FsmWarningKey STRAY_KEY = FsmWarningKey.fsmWarningKey(Warnings.STRAY, ORDERS.asString());
    private static final FsmWarningKey HOLD_KEY = FsmWarningKey.fsmWarningKey(Warnings.SCHEMA_HOLD, ORDERS.asString());
    private static final String APP = "org.example:app:1.0.0";
    private static final BlueprintId BLUEPRINT = blueprintId(APP);

    // -- fixtures ----------------------------------------------------------------------------------------------------------------------------
    private static ClusterModel model() {
        var m = new ClusterModel();

        m.incarnation = INCARNATION;

        return m;
    }

    private static Ctx ctx() {
        return SurvivorPinSupport.ctx(1_000L, 0L);
    }

    private static Ctx ctxOf(NodeId node) {
        return new Ctx(Settings.settings(node, DeploymentAtomicity.ALL_OR_NOTHING, Set.of(), Fixtures.bounds()), 1_000L, 0L);
    }

    private static FsmWarningValue row(NodeId writer, long incarnation, Map<String, String> members, boolean open, String message) {
        return FsmWarningValue.fsmWarningValue(members, open, message, 1L, writer, Settings.UNKNOWN_PROCESS, incarnation);
    }

    private static FsmWarningValue anotherNodes(boolean open, String message) {
        return row(CORE_2, INCARNATION, Map.of(), open, message);
    }

    private static FsmWarningValue earlierSelfs(boolean open, String message) {
        return row(FsmHarness.SELF, INCARNATION - 1, Map.of(), open, message);
    }

    private static FsmWarningValue ownStamp(boolean open, String message) {
        return row(FsmHarness.SELF, INCARNATION, Map.of(), open, message);
    }

    private static ClusterModel opener(ClusterModel m, String text) {
        return Warnings.raise(m, Warnings.UNJUDGEABLE, SUBJECT, Warnings.SINGLE, Warnings.SINGLE, text);
    }

    private static ClusterModel recovery(ClusterModel m, String text) {
        return Warnings.raise(m, RECOVERY, SUBJECT, Warnings.SINGLE, Warnings.SINGLE, text);
    }

    private static boolean held(ClusterModel m) {
        return m.unjudgeableWarned.contains(SUBJECT);
    }

    private static Map<String, String> members(ClusterModel m, String code, String subject) {
        return Warnings.TABLE.get(code)
                             .state()
                             .held(m, subject);
    }

    private static ClusterModel observe(ClusterModel m, NodeId node, Artifact artifact) {
        m.observed = m.observed.put(SlicePair.slicePair(node, artifact),
                                    Fixtures.row(SliceObservedState.ACTIVE, 1L, gen(1), process(1L)));

        return m;
    }

    private static Option<FsmWarningValue> drafted(ClusterModel m, Ctx ctx, FsmWarningKey key) {
        return Option.from(Warnings.difference(m, ctx)
                                   .stream()
                                   .filter(mutation -> mutation.key()
                                                               .equals(key))
                                   .findFirst())
                     .flatMap(KVCommand.Mutation::replacement)
                     .map(FsmWarningValue.class::cast);
    }

    // -- item 1 table, row 1: a row before the event is applied, then the event decides ------------------------------------------------------
    /// Row 1, another node's row, both polarities: applied with no evidence, then the event decides. Red when the row is not applied, or
    /// when the row's state outlives a later event of the opposite polarity.
    @Test
    void onPut_anotherNodesRowBeforeTheEvent_isAppliedThenTheEventDecides() {
        var m = model();
        var ctx = ctx();

        Warnings.onPut(m, ctx, KEY, anotherNodes(true, "row open"));
        assertThat(held(m)).as("applied: no evidence yet").isTrue();
        recovery(m, "recovered");
        assertThat(held(m)).as("the later recovery decides").isFalse();

        var n = model();

        Warnings.onPut(n, ctx, KEY, anotherNodes(false, "row closed"));
        assertThat(held(n)).as("applied closed").isFalse();
        opener(n, "raised");
        assertThat(held(n)).as("the later opener decides").isTrue();
    }

    /// Row 1, an earlier self's row: the same as another node's when it arrives before the event. Red when an earlier self's row is
    /// ignored although this incarnation has no evidence for the member.
    @Test
    void onPut_earlierSelfsRowBeforeTheEvent_isAppliedThenTheEventDecides() {
        var m = model();
        var ctx = ctx();

        Warnings.onPut(m, ctx, KEY, earlierSelfs(true, "earlier open"));
        assertThat(held(m)).as("applied: no evidence yet").isTrue();
        recovery(m, "recovered");
        assertThat(held(m)).as("the later recovery decides").isFalse();
    }

    // -- item 1 table, row 2: own open evidence, then a row (P1) ------------------------------------------------------------------------------
    /// Row 2, another node's row (R3 polarity P1): own open evidence, then another node's closed row: the join holds. Red when the closed
    /// row erases this incarnation's own open evidence.
    @Test
    void onPut_ownOpenThenAnotherNodesClosedRow_staysHeld() {
        var m = model();
        var ctx = ctx();

        opener(m, "own open");
        Warnings.onPut(m, ctx, KEY, anotherNodes(false, "their close"));

        assertThat(held(m)).as("P1: held").isTrue();
        assertThat(drafted(m, ctx, KEY).map(FsmWarningValue::open)).as("the flush writes the join: open")
                                                               .isEqualTo(Option.some(true));
        assertThat(m.openText.get(KEY)).as("the own opener's text stays").isEqualTo(Option.some("own open"));
    }

    /// Row 2, an earlier self's row: own open evidence, then an earlier self's closed row: ignored. Red when the older row is applied.
    @Test
    void onPut_ownOpenThenEarlierSelfsClosedRow_isIgnored() {
        var m = model();

        opener(m, "own open");
        Warnings.onPut(m, ctx(), KEY, earlierSelfs(false, "earlier close"));

        assertThat(held(m)).isTrue();
    }

    /// Row 2: where the own evidence is open the hold test does not run (a row never erases own open evidence). RETRY_EXHAUSTED with no
    /// target would be refused by its test. Red when the join runs the hold test and drops the own open.
    @Test
    void onPut_ownOpenThenAnotherNodesOpenRow_runsNoHoldTest() {
        var m = model();
        var key = FsmWarningKey.fsmWarningKey(Warnings.RETRY_EXHAUSTED, ORDERS.asString());

        Warnings.raise(m, Warnings.RETRY_EXHAUSTED, ORDERS.asString(), Warnings.SINGLE, Warnings.SINGLE, "own open");
        Warnings.onPut(m, ctx(), key, anotherNodes(true, "their open"));

        assertThat(m.retryExhaustedWarned.contains(ORDERS)).isTrue();
        assertThat(m.closeText.get(key).isEmpty()).as("no refusal text").isTrue();
    }

    // -- item 1 table, row 3: own closed evidence, then a row (P2) ----------------------------------------------------------------------------
    /// Row 3, another node's row (R3 polarity P2): own closed evidence (a suppressed recovery: an event site raises it unconditionally),
    /// then another node's open row: the join holds, with the row's message as the open text. Red when the own close erases the open row.
    @Test
    void onPut_ownClosedThenAnotherNodesOpenRow_isHeldWithTheRowsText() {
        var m = model();
        var ctx = ctx();

        recovery(m, "suppressed recovery");
        Warnings.onPut(m, ctx, KEY, anotherNodes(true, "their open"));

        assertThat(held(m)).as("P2: held").isTrue();
        assertThat(m.openText.get(KEY)).as("a joined row sets the open text").isEqualTo(Option.some("their open"));
        assertThat(drafted(m, ctx, KEY).isEmpty()).as("the flush writes nothing: the state says what the row says, the stamp aside").isTrue();
    }

    /// Row 3, another node's closed row: the join is the row's state, not held. Red when the join holds without an open side.
    @Test
    void onPut_ownClosedThenAnotherNodesClosedRow_isNotHeld() {
        var m = model();

        opener(m, "o");
        recovery(m, "own close");
        Warnings.onPut(m, ctx(), KEY, anotherNodes(false, "their close"));

        assertThat(held(m)).isFalse();
    }

    /// Row 3, an earlier self's row: own closed evidence, then an earlier self's open row: ignored. Red when the older row re-opens it.
    @Test
    void onPut_ownClosedThenEarlierSelfsOpenRow_isIgnored() {
        var m = model();

        recovery(m, "own close");
        Warnings.onPut(m, ctx(), KEY, earlierSelfs(true, "earlier open"));

        assertThat(held(m)).isFalse();
        assertThat(m.openText.get(KEY).isEmpty()).as("an ignored row sets no open text").isTrue();
    }

    // -- item 1 table, row 4: no evidence, any row: the row's state ---------------------------------------------------------------------------
    /// Row 4: with no evidence each row sets the state, a later row replacing an earlier one, both writers. Red when a closed row does
    /// not close a key an earlier row opened.
    @Test
    void onPut_noEvidence_eachRowSetsTheState() {
        var m = model();
        var ctx = ctx();

        Warnings.onPut(m, ctx, KEY, anotherNodes(true, "open"));
        Warnings.onPut(m, ctx, KEY, earlierSelfs(false, "close"));
        assertThat(held(m)).as("an earlier self's closed row over no evidence").isFalse();
        Warnings.onPut(m, ctx, KEY, earlierSelfs(true, "open again"));
        Warnings.onPut(m, ctx, KEY, anotherNodes(false, "close again"));
        assertThat(held(m)).as("another node's closed row over no evidence").isFalse();
    }

    /// Row 4 on a membered family (R-B: the row's state, never a union): a deposed leader's row that drops a member un-holds it. Red when
    /// the adopted members are the union of the rows.
    @Test
    void onPut_deposedRowDroppingAMember_unholdsTheMember() {
        var m = model();
        var ctx = ctx();

        observe(m, CORE_1, ORDERS);
        observe(m, CORE_3, ORDERS);
        Warnings.onPut(m, ctx, STRAY_KEY, row(CORE_2, 1L, Map.of(CORE_1.id(), Warnings.SINGLE, CORE_3.id(), Warnings.SINGLE), true, "two"));
        assertThat(members(m, Warnings.STRAY, ORDERS.asString())).as("precondition").containsOnlyKeys(CORE_1.id(), CORE_3.id());
        Warnings.onPut(m, ctx, STRAY_KEY, row(CORE_2, 1L, Map.of(CORE_1.id(), Warnings.SINGLE), true, "one"));

        assertThat(members(m, Warnings.STRAY, ORDERS.asString())).containsOnlyKeys(CORE_1.id());
    }

    /// Rule O is per member: own open evidence on one member, none on another; another node's row holding only the second keeps the first
    /// (P1) and applies the second. Red when the comparison is per key.
    @Test
    void onPut_memberedRow_isComparedPerMember() {
        var m = model();

        observe(m, CORE_1, ORDERS);
        observe(m, CORE_3, ORDERS);
        Warnings.raise(m, Warnings.STRAY, ORDERS.asString(), CORE_1.id(), Warnings.SINGLE, "own");
        Warnings.onPut(m, ctx(), STRAY_KEY, row(CORE_2, 1L, Map.of(CORE_3.id(), Warnings.SINGLE), true, "theirs"));

        assertThat(members(m, Warnings.STRAY, ORDERS.asString())).containsOnlyKeys(CORE_1.id(), CORE_3.id());
    }

    // -- item 1 table, row 5 and the own row ----------------------------------------------------------------------------------------------------
    /// Row 5, an earlier self's row after the resume: ignored like rows 2 and 3 (rule O reads no suspension; another node's row there is
    /// unreachable, note (1)). A row with the current stamp is the node's own: compared with intent only, never changes the state, and
    /// marks dirty. Red when an own row is applied as another's.
    @Test
    void onPut_ownStampRow_changesNoStateAndMarksDirty() {
        var m = model();
        var ctx = ctx();

        Warnings.onPut(m, ctx, KEY, ownStamp(true, "own row"));
        assertThat(held(m)).as("an own row over no evidence is not applied").isFalse();
        opener(m, "o");
        m.warningsDirty = false;
        Warnings.onPut(m, ctx, KEY, ownStamp(false, "own close"));

        assertThat(held(m)).as("nor over open evidence").isTrue();
        assertThat(m.warningsDirty).as("every indexed row marks dirty").isTrue();
        assertThat(drafted(m, ctx, KEY).map(FsmWarningValue::open)).as("the flush rewrites intent").isEqualTo(Option.some(true));
    }

    /// Distinct node stamps: two FSMs from default `Settings` share the process identity and may share the incarnation; they differ by
    /// node. A row another FSM drafts is another node's row here (joined, P2), and the same FSM's own draft is its own row. Red when the
    /// own-row test ignores the node.
    @Test
    void onPut_rowOfAnotherFsmWithDefaultSettings_isAnotherNodesRow() {
        var theirs = model();
        var theirCtx = ctxOf(CORE_2);
        var mine = model();
        var myCtx = ctxOf(FsmHarness.SELF);

        opener(theirs, "theirs");
        var row = Warnings.desired(theirs, theirCtx)
                          .get(KEY);

        assertThat(row.writerProcess()).as("precondition: same process identity").isEqualTo(myCtx.settings().process());
        assertThat(row.writerIncarnation()).as("precondition: same incarnation").isEqualTo(mine.incarnation);
        recovery(mine, "my close");
        Warnings.onPut(mine, myCtx, KEY, row);

        assertThat(held(mine)).as("joined as another node's open row").isTrue();
    }

    // -- the open-biased join, per class of the design's §0 table ---------------------------------------------------------------------------
    /// The join of the item 1 table is one rule for every family: own closed evidence, then another node's open row, holds the member with
    /// the row's text. Pinned once per class of the §0 per-class table, because a bias changed for one family only is otherwise invisible.
    private static void assertOpenRowWinsOverOwnClose(ClusterModel m, String code, String subject, String member, Map<String, String> rowMembers) {
        var key = FsmWarningKey.fsmWarningKey(code, subject);

        Warnings.raise(m, Warnings.FAMILIES.get(code), subject, member, Warnings.SINGLE, "own close");
        assertThat(members(m, code, subject)).as(code + ": own close of a member never held").isEmpty();
        Warnings.onPut(m, ctx(), key, row(CORE_2, INCARNATION, rowMembers, true, "their open"));

        assertThat(members(m, code, subject)).as(code + ": the open row wins over own closed evidence").isNotEmpty();
        assertThat(m.openText.get(key)).as(code + ": the row's text").isEqualTo(Option.some("their open"));
    }

    private static ClusterModel withTarget() {
        var m = model();

        m.targets = m.targets.put(ORDERS.base(), target(ORDERS, 1, 1, "CORE_ONLY"));

        return m;
    }

    /// Class E-open, L-close (RETRY_EXHAUSTED). Red when this family's join lets own closed evidence win over another node's open row.
    @Test
    void join_eOpenLClose_retryExhausted_openRowWinsOverOwnClose() {
        assertOpenRowWinsOverOwnClose(withTarget(), Warnings.RETRY_EXHAUSTED, ORDERS.asString(), Warnings.SINGLE, Map.of());
    }

    /// Class L-open, L-close (INVALID_PLACEMENT). Red when this family's join lets own closed evidence win over another node's open row.
    @Test
    void join_lOpenLClose_invalidPlacement_openRowWinsOverOwnClose() {
        assertOpenRowWinsOverOwnClose(withTarget(), Warnings.INVALID_PLACEMENT, ORDERS.asString(), Warnings.SINGLE, Map.of());
    }

    /// Class L-open, E-close (STRAY, per member). Red when this family's join lets own closed evidence win over another node's open row.
    @Test
    void join_lOpenEClose_stray_openRowWinsOverOwnClose() {
        var m = observe(model(), CORE_1, ORDERS);

        assertOpenRowWinsOverOwnClose(m, Warnings.STRAY, ORDERS.asString(), CORE_1.id(), Map.of(CORE_1.id(), Warnings.SINGLE));
    }

    /// Class E-open, E-close (NO_POLICY and GROWTH_BLOCKED). Red when either family's join lets own closed evidence win over another
    /// node's open row.
    @Test
    void join_eOpenEClose_noPolicyAndGrowthBlocked_openRowWinsOverOwnClose() {
        assertOpenRowWinsOverOwnClose(model(), Warnings.NO_POLICY, CORE_3.id(), Warnings.SINGLE, Map.of());
        assertOpenRowWinsOverOwnClose(model(), Warnings.GROWTH_BLOCKED, SUBJECT, Warnings.SINGLE, Map.of());
    }

    // -- the hold test (table: Code / Adoption test / Refused when) -------------------------------------------------------------------------
    private static ClusterModel adopt(ClusterModel m, FsmWarningKey key, Map<String, String> members) {
        return Warnings.onPut(m, ctx(), key, row(CORE_2, 1L, members, true, "their open"));
    }

    private static void assertRefused(ClusterModel m, FsmWarningKey key, String reason) {
        assertThat(members(m, key.code(), key.subject())).as("refused: not held").isEmpty();
        assertThat(m.closeText.get(key).or("")).as("the refusal text").contains(reason);
        assertThat(m.fresh.get(Warnings.Member.member(key, Warnings.SINGLE)).isEmpty()).as("a refusal records no evidence").isTrue();
        assertThat(drafted(m, ctx(), key).map(FsmWarningValue::message)).as("the next flush writes the close with that text")
                                                                     .isEqualTo(m.closeText.get(key));
    }

    /// Hold-test rows INVALID_PLACEMENT and RETRY_EXHAUSTED: admitted while `targets` has the base, refused (gone) without it. Red when a
    /// row is adopted for a removed target, or refused for a present one.
    @Test
    void holdTest_targetFamilies_needTheTarget() {
        for (var code : List.of(Warnings.INVALID_PLACEMENT, Warnings.RETRY_EXHAUSTED)) {
            var key = FsmWarningKey.fsmWarningKey(code, ORDERS.asString());

            assertRefused(adopt(model(), key, Map.of()), key, "its target was removed between leaders");

            var m = model();

            m.targets = m.targets.put(ORDERS.base(), target(ORDERS, 1, 1, "CORE_ONLY"));
            assertThat(members(adopt(m, key, Map.of()), code, ORDERS.asString())).as(code + " admitted").isNotEmpty();
        }
    }

    private static SchemaVersionValue schema(SchemaStatus status) {
        return new SchemaVersionValue("ds", 1, "m1", status, "g:a:1", BLUEPRINT, 2, FsmHarness.START_WALL, SchemaReach.MIGRATED);
    }

    /// Hold-test row SCHEMA_UNKNOWN: admitted while the status is UNKNOWN; refused when the record is gone, and closed with "the status
    /// decodes" when it is not UNKNOWN. Red when either refusal is lost.
    @Test
    void holdTest_schemaUnknown_needsAnUnknownStatus() {
        var key = FsmWarningKey.fsmWarningKey(Warnings.SCHEMA_UNKNOWN, "ds");

        assertRefused(adopt(model(), key, Map.of()), key, "its schema record was removed between leaders");

        var decodes = model();

        decodes.schemas = decodes.schemas.put("ds", schema(SchemaStatus.COMPLETED));
        assertRefused(adopt(decodes, key, Map.of()), key, "the status decodes");

        var unknown = model();

        unknown.schemas = unknown.schemas.put("ds", schema(SchemaStatus.UNKNOWN));
        assertThat(adopt(unknown, key, Map.of()).schemaUnknownWarned.contains("ds")).isTrue();
    }

    /// Hold-test row SCHEMA_STALLED: admitted while `schemas` has the datasource, refused (gone) without it.
    @Test
    void holdTest_schemaStalled_needsTheRecord() {
        var key = FsmWarningKey.fsmWarningKey(Warnings.SCHEMA_STALLED, "ds");

        assertRefused(adopt(model(), key, Map.of()), key, "its schema record was removed between leaders");

        var m = model();

        m.schemas = m.schemas.put("ds", schema(SchemaStatus.PENDING));
        assertThat(adopt(m, key, Map.of()).schemaStalledWarned.contains("ds")).isTrue();
    }

    /// Hold-test row STRAY (per member): a member is admitted while its observed row is committed; a refused member while another keeps
    /// the key held sets no text; the key closes (gone) when no member is held. Red when a refused member closes a key another holds, or
    /// when an all-refused key keeps no text.
    @Test
    void holdTest_stray_isPerMember() {
        var m = model();

        observe(m, CORE_1, ORDERS);
        adopt(m, STRAY_KEY, Map.of(CORE_1.id(), Warnings.SINGLE, CORE_3.id(), Warnings.SINGLE));
        assertThat(members(m, Warnings.STRAY, ORDERS.asString())).containsOnlyKeys(CORE_1.id());
        assertThat(m.closeText.get(STRAY_KEY).isEmpty()).as("a refused member while another holds: no text").isTrue();

        var none = model();

        adopt(none, STRAY_KEY, Map.of(CORE_3.id(), Warnings.SINGLE));
        assertThat(members(none, Warnings.STRAY, ORDERS.asString())).isEmpty();
        assertThat(none.closeText.get(STRAY_KEY).or("")).contains("the condemned stray instances were removed between leaders");
    }

    /// Hold-test row SCHEMA_HOLD (per member, as STRAY; the value is the row's when only the row holds the member). Red when the row's
    /// signature is not adopted, or a member without its observed row is held.
    @Test
    void holdTest_schemaHold_isPerMemberAndKeepsTheRowsSignature() {
        var m = model();

        observe(m, CORE_1, ORDERS);
        adopt(m, HOLD_KEY, Map.of(CORE_1.id(), "sig-row", CORE_3.id(), "sig-3"));

        assertThat(members(m, Warnings.SCHEMA_HOLD, ORDERS.asString())).containsExactly(Map.entry(CORE_1.id(), "sig-row"));

        var none = model();

        adopt(none, HOLD_KEY, Map.of(CORE_3.id(), "sig-3"));
        assertThat(none.closeText.get(HOLD_KEY).or("")).contains("the held instances were removed between leaders");
    }

    /// Hold-test rows JOIN_UNMATCHED, NO_POLICY, UNPLACED: refused (gone) when the node departed, closed when a community-worker
    /// directive is committed for it, admitted otherwise.
    @Test
    void holdTest_workerFamilies_needANodeNeitherDepartedNorPlaced() {
        for (var code : List.of(Warnings.JOIN_UNMATCHED, Warnings.NO_POLICY, Warnings.UNPLACED)) {
            var key = FsmWarningKey.fsmWarningKey(code, CORE_3.id());
            var departed = model();

            departed.departed = departed.departed.add(CORE_3);
            assertRefused(adopt(departed, key, Map.of()), key, "departed between leaders");

            var placed = model();

            placed.directives = placed.directives.put(CORE_3, worker("east"));
            assertRefused(adopt(placed, key, Map.of()), key, CORE_3.id());

            assertThat(members(adopt(model(), key, Map.of()), code, CORE_3.id())).as(code + " admitted").isNotEmpty();
        }
    }

    /// Hold-test rows UNJUDGEABLE and GROWTH_BLOCKED: never refused, even on an empty model (a label never leaves the model).
    @Test
    void holdTest_labelFamilies_areNeverRefused() {
        for (var code : List.of(Warnings.UNJUDGEABLE, Warnings.GROWTH_BLOCKED)) {
            var key = FsmWarningKey.fsmWarningKey(code, SUBJECT);
            var m = adopt(model(), key, Map.of());

            assertThat(members(m, code, SUBJECT)).as(code).isNotEmpty();
            assertThat(m.closeText.get(key).isEmpty()).as(code).isTrue();
        }
    }

    /// Hold-test row SUPPRESSED: admitted while `communities` has it, refused (gone) without it.
    @Test
    void holdTest_suppressed_needsTheCommunity() {
        var key = FsmWarningKey.fsmWarningKey(Warnings.SUPPRESSED, "east");

        assertRefused(adopt(model(), key, Map.of()), key, "its community record was removed between leaders");

        var m = model();

        m.communities = m.communities.put("east", community("east", 3, CommunityState.ACTIVE));
        assertThat(adopt(m, key, Map.of()).suppressedWarned.contains("east")).isTrue();
    }

    private static DeploymentOutcomeValue outcome(String cause) {
        return new DeploymentOutcomeValue(DeploymentOutcomeStatus.FAILED, List.of(), cause, FsmHarness.START_WALL, 2L, "attempt-7");
    }

    /// Hold-test row APPLY_DEADLINE: admitted while the outcome's cause is the deadline, the value being its attempt; refused (gone) when
    /// the outcome is replaced or removed.
    @Test
    void holdTest_applyDeadline_needsTheDeadlineOutcomeAndTakesItsAttempt() {
        var key = FsmWarningKey.fsmWarningKey(Warnings.APPLY_DEADLINE, BLUEPRINT.asString());

        assertRefused(adopt(model(), key, Map.of()), key, "its deadline terminal was replaced or removed between leaders");

        var replaced = model();

        replaced.outcomes = replaced.outcomes.put(BLUEPRINT, outcome("slice-failed"));
        assertRefused(adopt(replaced, key, Map.of()), key, "its deadline terminal was replaced or removed between leaders");

        var m = model();

        m.outcomes = m.outcomes.put(BLUEPRINT, outcome(Deadlines.EXCEEDED));
        assertThat(adopt(m, key, Map.of()).applyDeadlineWarned.get(BLUEPRINT)).isEqualTo(Option.some("attempt-7"));
    }

    /// A subject the family cannot parse is a refused hold ("the subject does not parse"): neither held nor left without a close.
    @Test
    void holdTest_unparsableSubject_isRefused() {
        var key = FsmWarningKey.fsmWarningKey(Warnings.STRAY, "not an artifact");
        var m = adopt(model(), key, Map.of(CORE_1.id(), Warnings.SINGLE));

        assertThat(m.closeText.get(key).or("")).contains("the subject does not parse");
    }

    /// The hold test runs where rule O runs, `inherit` included (rule O over the snapshot): a snapshot row for a removed target is refused.
    @Test
    void inherit_snapshotRowRefusedByItsHoldTest_isClosedWithTheReason() {
        var m = model();
        var key = FsmWarningKey.fsmWarningKey(Warnings.RETRY_EXHAUSTED, ORDERS.asString());

        Warnings.indexRow(m, key, anotherNodes(true, "snapshot open"));
        Warnings.inherit(m, ctx());

        assertRefused(m, key, "its target was removed between leaders");
    }

    // -- the value (SCHEMA_HOLD's signature) -----------------------------------------------------------------------------------------------
    /// Both sides hold the member: the own value stays, and the resume write replaces the row's value with it, which rule E announces as
    /// a new signature's opener. The joined open row does not replace the own opener text (#3274 design erratum 3), so the opener carries
    /// the own message. Red when the row's value or message replaces the own one, or the change is not announced.
    @Test
    void onPut_bothHoldDifferentSignatures_keepsTheOwnAndAnnouncesItWithTheOwnText() {
        var m = model();
        var ctx = ctx();

        observe(m, CORE_1, ORDERS);
        Warnings.raise(m, Warnings.SCHEMA_HOLD, ORDERS.asString(), CORE_1.id(), "sig-own", "own hold");
        var theirs = row(CORE_2, 1L, Map.of(CORE_1.id(), "sig-row"), true, "their hold");

        Warnings.onPut(m, ctx, HOLD_KEY, theirs);
        assertThat(members(m, Warnings.SCHEMA_HOLD, ORDERS.asString())).containsExactly(Map.entry(CORE_1.id(), "sig-own"));

        var write = Warnings.difference(m, ctx);

        assertThat(write).hasSize(1);
        Warnings.accepted(m, ctx, TxDraft.draft(WARNINGS, HOLD_KEY, write));
        assertThat(write.getFirst()
                        .replacement()
                        .map(FsmWarningValue.class::cast)
                        .map(FsmWarningValue::members)).isEqualTo(Option.some(Map.of(CORE_1.id(), "sig-own")));
        assertThat(ctx.effects()).containsExactly(new ClusterEffect.Warn(Warnings.SCHEMA_HOLD, ORDERS.asString(), "own hold"));
    }

    /// A new SCHEMA_HOLD signature on a held member is a new opener: the raise passes and takes the text. Red when the opener check
    /// compares presence only on a membered family.
    @Test
    void raise_newSchemaHoldSignature_passesAsAnOpener() {
        var m = model();

        Warnings.raise(m, Warnings.SCHEMA_HOLD, ORDERS.asString(), CORE_1.id(), "sig-1", "first");
        Warnings.raise(m, Warnings.SCHEMA_HOLD, ORDERS.asString(), CORE_1.id(), "sig-1", "recurrence");
        assertThat(m.openText.get(HOLD_KEY)).as("a recurrence does not pass").isEqualTo(Option.some("first"));
        Warnings.raise(m, Warnings.SCHEMA_HOLD, ORDERS.asString(), CORE_1.id(), "sig-2", "second");

        assertThat(members(m, Warnings.SCHEMA_HOLD, ORDERS.asString())).containsExactly(Map.entry(CORE_1.id(), "sig-2"));
        assertThat(m.openText.get(HOLD_KEY)).isEqualTo(Option.some("second"));
    }

    /// A recovery for a member that is not held is evidence only: no state change, no text, nothing drafted; a second recovery of a closed
    /// key keeps the first text. Red when the raise passes a recovery whose member is not held (the set check, rule C).
    @Test
    void raise_recoveryForAMemberNotHeld_changesNothingAndRecordsNoText() {
        var m = model();
        var ctx = ctx();

        recovery(m, "never held");

        assertThat(held(m)).isFalse();
        assertThat(m.closeText.get(KEY).isEmpty()).as("no recovery text for a key never opened").isTrue();
        assertThat(m.openText.get(KEY).isEmpty()).as("no open text").isTrue();
        assertThat(drafted(m, ctx, KEY).isEmpty()).as("nothing drafted").isTrue();

        opener(m, "o");
        recovery(m, "first close");
        recovery(m, "second close");
        assertThat(m.closeText.get(KEY)).as("a recovery of a closed key keeps the text of the close that closed it").isEqualTo(Option.some("first close"));

        var stray = observe(model(), CORE_1, ORDERS);

        Warnings.raise(stray, Warnings.STRAY, ORDERS.asString(), CORE_1.id(), Warnings.SINGLE, "o");
        Warnings.raise(stray, Warnings.FAMILIES.get(Warnings.STRAY), ORDERS.asString(), CORE_3.id(), Warnings.SINGLE, "other member closes");
        assertThat(members(stray, Warnings.STRAY, ORDERS.asString())).as("the held member stays").containsOnlyKeys(CORE_1.id());
        assertThat(stray.closeText.get(STRAY_KEY).isEmpty()).as("a recovery of a member not held records no text").isTrue();
    }

    /// A re-open drops the recovery text of the earlier close: a later close must not reuse it. Red when the opener keeps the old
    /// `closeText`.
    @Test
    void raise_reopen_dropsTheEarlierRecoveryText() {
        var m = model();

        opener(m, "o1");
        recovery(m, "close 1");
        assertThat(m.closeText.get(KEY)).as("the close recorded its text").isEqualTo(Option.some("close 1"));
        opener(m, "o2");

        assertThat(m.closeText.get(KEY).isEmpty()).as("the re-open drops the earlier recovery text").isTrue();
        assertThat(m.openText.get(KEY)).isEqualTo(Option.some("o2"));
    }

    // -- rule E: a function of one accepted committed pair --------------------------------------------------------------------------------
    private static List<ClusterEffect> notified(Option<FsmWarningValue> expected, FsmWarningValue replacement, FsmWarningKey key) {
        var ctx = ctx();
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      expected.map(AetherValue.class::cast),
                                                                      Option.some(replacement));

        Warnings.accepted(model(), ctx, TxDraft.draft(WARNINGS, key, List.of(mutation)));

        return ctx.effects();
    }

    private static FsmWarningValue strays(boolean open, String message, NodeId... nodes) {
        var members = new TreeMap<String, String>();

        for (var node : nodes) {
            members.put(node.id(), Warnings.SINGLE);
        }

        return row(FsmHarness.SELF, INCARNATION, members, open, message);
    }

    /// Rule E, the opener: absent (no members) to open, closed to open, a new STRAY node, a new SCHEMA_HOLD signature; each with the
    /// replacement's message. Red when any of the four is not announced.
    @Test
    void accepted_replacementHoldingANewMemberOrValue_emitsTheOpener() {
        var opener = new ClusterEffect.Warn(Warnings.STRAY, ORDERS.asString(), "new");

        assertThat(notified(Option.none(), strays(true, "new", CORE_1), STRAY_KEY)).containsExactly(opener);
        assertThat(notified(Option.some(strays(false, "closed")), strays(true, "new", CORE_1), STRAY_KEY)).containsExactly(opener);
        assertThat(notified(Option.some(strays(true, "one", CORE_1)), strays(true, "new", CORE_1, CORE_3), STRAY_KEY)).containsExactly(opener);
        assertThat(notified(Option.some(row(FsmHarness.SELF, INCARNATION, Map.of(CORE_1.id(), "s1"), true, "h1")),
                            row(FsmHarness.SELF, INCARNATION, Map.of(CORE_1.id(), "s2"), true, "h2"),
                            HOLD_KEY)).containsExactly(new ClusterEffect.Warn(Warnings.SCHEMA_HOLD, ORDERS.asString(), "h2"));
    }

    /// Rule E, the recovery: the expected open and the replacement closed, with the replacement's message. Red when the close is silent.
    @Test
    void accepted_openToClosed_emitsTheRecovery() {
        assertThat(notified(Option.some(strays(true, "open", CORE_1)), strays(false, "gone"), STRAY_KEY))
                  .containsExactly(new ClusterEffect.Warn(Warnings.FAMILIES.get(Warnings.STRAY), ORDERS.asString(), "gone"));
    }

    /// Rule E, nothing: a member leaves while another stays, and a rewrite of the same content. Red when either is announced.
    @Test
    void accepted_memberLeavesWhileAnotherStays_emitsNothing() {
        assertThat(notified(Option.some(strays(true, "two", CORE_1, CORE_3)), strays(true, "one", CORE_1), STRAY_KEY)).isEmpty();
        assertThat(notified(Option.some(strays(true, "same", CORE_1)), strays(true, "same", CORE_1), STRAY_KEY)).isEmpty();
    }

    /// Rule E, nothing: a write of a closed row over a closed row, or over no row, is a no-op close and is silent. Red when a recovery is
    /// announced although the expected was not open.
    @Test
    void accepted_closedToClosedOrAbsentToClosed_emitsNothing() {
        assertThat(notified(Option.some(strays(false, "closed")), strays(false, "closed again"), STRAY_KEY)).isEmpty();
        assertThat(notified(Option.none(), strays(false, "closed"), STRAY_KEY)).isEmpty();
    }
}
