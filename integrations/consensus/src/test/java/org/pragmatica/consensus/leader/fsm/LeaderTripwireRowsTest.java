/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.leader.fsm.LeaderEpisodeEntryPathTest.CONTROL_CURRENT;
import static org.pragmatica.consensus.leader.fsm.LeaderEpisodeEntryPathTest.EVENT;
import static org.pragmatica.consensus.leader.fsm.LeaderEpisodeEntryPathTest.HEAD;
import static org.pragmatica.consensus.leader.fsm.LeaderEpisodeEntryPathTest.compile;

/// #3846 round 3, lane A: every "unwritable under INV-E" row of memo r3 section 4 as a tripwire pair, on the in-process javac
/// of [LeaderEpisodeEntryPathTest] (its `compile`, `HEAD` and controls). The probes are string literals quoting the members
/// the lanes remove, so the inventory script treats this file as a scan source (it quotes the patterns it scans).
class LeaderTripwireRowsTest {

    private static final String LEADER_HEAD = "package org.pragmatica.consensus.leader;\n"
                                              + "import org.pragmatica.consensus.leader.fsm.*;\n"
                                              + "import org.pragmatica.consensus.fsm.ClusterFsmEvent;\n"
                                              + "import org.pragmatica.lang.utils.OwnerLifetime;\n"
                                              + "final class Probe {\n";
    private static final String CONTROL_MANAGER_CONTEXT = "static LeaderElectionContext arm(LeaderManager.FsmBackedLeaderManager manager) { return manager.context(); }";
    private static final String CONTROL_OUTSIDE_PKG = "static Object arm(LeaderElectionContext ctx) { return ctx.isLeader(); }";
    private static final String ATOMIC = "java.util.concurrent.atomic.AtomicReference";
    private static final String STATE = "LeaderElectionState";

    /// One tripwire row of memo r3 section 4. `code` compiles at the head (ENABLED tripwire) and must not compile once the
    /// flipping `lane` has landed (the `@Disabled` twin): the refusal must carry every one of `fragments` in one message, so a
    /// red that comes from a broken probe does not pass as the rule. `control` is the same shape through a member that
    /// stays, compiled in the same package, so a refusal comes from the removed member and not from the probe's class path.
    /// `requiredLineAbsent`, when set, is text the javac `required:` line of a constructor refusal must not mention.
    private record TripwireRow(String id, String lane, String head, String code, String control,
                               List<String> fragments, String requiredLineAbsent) {
        static TripwireRow row(String id, String lane, String head, String code, String control, String... fragments) {
            return new TripwireRow(id, lane, head, code, control, List.of(fragments), "");
        }

        TripwireRow requiredLineMustNotMention(String text) {
            return new TripwireRow(id, lane, head, code, control, fragments, text);
        }
    }

    private static final List<TripwireRow> TRIPWIRES = List.of(
        TripwireRow.row("K07", "St", HEAD,
                        "static LeaderElectionContext.EpisodeHandle arm(LeaderElectionContext ctx) { return ((" + STATE + ".Electing) ctx.current()).episodeHandle().get(); }",
                        CONTROL_CURRENT, "method episodeHandle()"),
        TripwireRow.row("K08", "St", HEAD,
                        "static void arm(" + STATE + ".Electing electing, LeaderElectionContext.EpisodeHandle handle) { electing.episodeHandle().set(handle); }",
                        CONTROL_CURRENT, "method episodeHandle()"),
        TripwireRow.row("K17", "St", HEAD,
                        "static " + STATE + " arm(LeaderElectionContext ctx, LeaderElectionContext.EpisodeHandle handle) { return new " + STATE
                        + ".Electing(ctx, 0L, new " + ATOMIC + "<>(), new " + ATOMIC + "<>(), new " + ATOMIC + "<>(), new " + ATOMIC + "<>(handle)); }",
                        CONTROL_CURRENT, "constructor Electing in record Electing cannot be applied to given types")
                  .requiredLineMustNotMention("EpisodeHandle"),
        TripwireRow.row("O01", "St", HEAD,
                        "static Object arm(" + STATE + ".AwaitingKvSync a, " + STATE + ".Electing e, " + STATE + ".ReElecting r) { return java.util.List.of(a.episodeHandle(), e.episodeHandle(), r.episodeHandle()); }",
                        CONTROL_CURRENT, "method episodeHandle()"),
        TripwireRow.row("O08", "C", LEADER_HEAD,
                        "static Object arm(LeaderElectionContext ctx) { return ctx.claimInbound(); }",
                        CONTROL_OUTSIDE_PKG, "claimInbound() is not public in"),
        TripwireRow.row("K16", "M", HEAD,
                        "static void arm(LeaderElectionFsm.FsmWithContext built) { built.fsm().current().onEntry(built.fsm().entered().lifetime()); }",
                        CONTROL_CURRENT, "method fsm()"),
        TripwireRow.row("O07", "M", HEAD,
                        "static void arm(LeaderElectionFsm.FsmWithContext built, " + EVENT + " event) { built.fsm().dispatch(event); }",
                        CONTROL_CURRENT, "method fsm()"),
        TripwireRow.row("K19", "M", LEADER_HEAD,
                        "static void arm(LeaderManager.FsmBackedLeaderManager manager) { manager.fsm().current().onEntry(manager.fsm().entered().lifetime()); }",
                        CONTROL_MANAGER_CONTEXT, "method fsm()"),
        TripwireRow.row("O02", "M", LEADER_HEAD,
                        "static void arm(LeaderManager.FsmBackedLeaderManager manager, ClusterFsmEvent event) { manager.fsm().dispatch(event); }",
                        CONTROL_MANAGER_CONTEXT, "method fsm()"),
        TripwireRow.row("O03", "M", LEADER_HEAD,
                        "static void arm(LeaderManager.FsmBackedLeaderManager manager, Runnable task) { manager.inbound().serially(task); }",
                        CONTROL_MANAGER_CONTEXT, "method inbound()"),
        TripwireRow.row("O06", "M", LEADER_HEAD,
                        "static void arm(LeaderManager.FsmBackedLeaderManager manager) { manager.fsm().current().onEntry(OwnerLifetime.ownerLifetime()); }",
                        CONTROL_MANAGER_CONTEXT, "method fsm()"),
        TripwireRow.row("K15a", "F", HEAD,
                        "static void arm(LeaderElectionContext ctx, " + EVENT + " event) { ctx.dispatchConfined(event); }",
                        CONTROL_CURRENT, "dispatchConfined", "has private access in"),
        TripwireRow.row("K21", "F", HEAD,
                        "static LeaderElectionContext.EpisodeHandle arm(LeaderElectionContext ctx) { return ctx.unentered(); }",
                        CONTROL_CURRENT, "method unentered()"),
        TripwireRow.row("K23a", "F", HEAD,
                        "static Runnable arm(LeaderElectionContext ctx, " + EVENT + " event) { return () -> ctx.dispatchConfined(event); }",
                        CONTROL_CURRENT, "dispatchConfined", "has private access in"));

    private static Stream<Named<TripwireRow>> laneRows(String lane) {
        return TRIPWIRES.stream()
                        .filter(row -> row.lane().equals(lane))
                        .map(row -> Named.of(row.id(), row));
    }

    static Stream<Named<TripwireRow>> laneStRows() {
        return laneRows("St");
    }

    static Stream<Named<TripwireRow>> laneCRows() {
        return laneRows("C");
    }

    static Stream<Named<TripwireRow>> laneMRows() {
        return laneRows("M");
    }

    static Stream<Named<TripwireRow>> laneFRows() {
        return laneRows("F");
    }

    /// The instrument of every tripwire row: each control compiles in the row's own package, so a refusal of the row comes
    /// from the removed member. ENABLED at every head.
    @Test
    void tripwireControls_compile() throws Exception {
        for (var row : TRIPWIRES) {
            assertThat(compile(row.head() + "  " + row.control() + "\n}\n"))
                .as("instrument: the control of tripwire " + row.id() + " must compile at every head")
                .isEmpty();
        }
    }

    /// Every lane has a row: a lane filter that matches nothing would leave its tripwire and its twin vacuous.
    @Test
    void tripwireLanes_everyLaneHasRows() {
        assertThat(TRIPWIRES.stream().map(TripwireRow::lane).distinct().toList()).containsExactlyInAnyOrder("St", "C", "M", "F");
        assertThat(TRIPWIRES).as("the 14 rows of the lane A tripwire list").hasSize(14);
    }

    // ENABLED tripwires: the head's behaviour. A flipping lane deletes its method, enables its twin, drops nothing else.

    @ParameterizedTest
    @MethodSource("laneStRows")
    void tripwireLaneSt_atHead_compiles(TripwireRow row) throws Exception {
        assertCompilesAtHead(row, "tripwireLaneSt_underInvE_doesNotCompile");
    }

    @ParameterizedTest
    @MethodSource("laneFRows")
    void tripwireLaneF_atHead_compiles(TripwireRow row) throws Exception {
        assertCompilesAtHead(row, "tripwireLaneF_underInvE_doesNotCompile");
    }

    // The real assertions, @Disabled until the named lane lands the change.

    @Disabled("lane St: enable when the three handle cells are deleted (K07, K08, K17, O01)")
    @ParameterizedTest
    @MethodSource("laneStRows")
    void tripwireLaneSt_underInvE_doesNotCompile(TripwireRow row) throws Exception {
        assertRefusedUnderInvE(row);
    }

    @ParameterizedTest
    @MethodSource("laneCRows")
    void tripwireLaneC_underInvE_doesNotCompile(TripwireRow row) throws Exception {
        assertRefusedUnderInvE(row);
    }

    @ParameterizedTest
    @MethodSource("laneMRows")
    void tripwireLaneM_underInvE_doesNotCompile(TripwireRow row) throws Exception {
        assertRefusedUnderInvE(row);
    }

    @Disabled("lane F: enable when dispatchConfined is private and unentered() is deleted (K15a, K21, K23a)")
    @ParameterizedTest
    @MethodSource("laneFRows")
    void tripwireLaneF_underInvE_doesNotCompile(TripwireRow row) throws Exception {
        assertRefusedUnderInvE(row);
    }

    private static void assertCompilesAtHead(TripwireRow row, String twin) throws Exception {
        var errors = compile(row.head() + "  " + row.code() + "\n}\n");

        assertThat(errors).as("tripwire " + row.id() + " no longer compiles: lane " + row.lane() + " has landed its change. Enable " + twin
                              + ", delete this lane's atHead_compiles method, and leave the other lanes' rows alone. javac said: " + errors)
                  .isEmpty();
    }

    private static void assertRefusedUnderInvE(TripwireRow row) throws Exception {
        var errors = compile(row.head() + "  " + row.code() + "\n}\n");

        assertThat(errors).as("row " + row.id() + " compiled: the member an off-frame path needs still exists (lane " + row.lane() + ")")
                  .isNotEmpty();
        assertThat(errors).as("the refusal of " + row.id() + " carries " + row.fragments() + ", not another defect of the probe")
                  .anyMatch(message -> row.fragments().stream().allMatch(message::contains));

        if (!row.requiredLineAbsent().isEmpty()) {
            assertThat(errors).as("row " + row.id() + ": the 'required:' line of the refusal still mentions " + row.requiredLineAbsent())
                      .anyMatch(message -> requiredLine(message).startsWith("required:") && !requiredLine(message).contains(row.requiredLineAbsent()));
        }
    }

    private static String requiredLine(String message) {
        return message.lines()
                      .map(String::strip)
                      .filter(line -> line.startsWith("required:"))
                      .findFirst()
                      .orElse("");
    }
}
