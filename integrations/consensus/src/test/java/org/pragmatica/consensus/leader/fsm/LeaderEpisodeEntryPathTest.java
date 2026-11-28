/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;


/// #3846 failure input 2: a timer or a promise callback armed through the old unfenced path. The enforcer is the
/// compiler, not a census a new arm site can bypass: `LeaderElectionContext` offers no way to hand a task to the
/// serial executor, to dispatch, or to follow up from outside the dispatch frame except by calling a method ON an
/// `EpisodeHandle` that the state received at entry, so an arm site written without one does not compile.
///
/// The probes are compiled in the context's own package (package-private access is the widest a new arm site in
/// `leader/fsm` could have) against this test's class path. The control probe is the same arm site through the
/// fenced path: it must compile, so a red on the unfenced probes comes from the missing method and not from a probe
/// or a class path that cannot compile anything.
class LeaderEpisodeEntryPathTest {
    private static final String HEAD = "package org.pragmatica.consensus.leader.fsm;\n"
                                       + "import java.util.function.Consumer;\n"
                                       + "final class Probe {\n";

    private record Source(String code) {
        JavaFileObject file() {
            return new SimpleJavaFileObject(URI.create("string:///org/pragmatica/consensus/leader/fsm/Probe.java"),
                                            JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return code;
                }
            };
        }
    }

    @Test
    void controlProbe_armingATimerThroughAnEpisode_compiles() throws Exception {
        var errors = compile(HEAD
                             + "  static Runnable arm(LeaderElectionContext.EpisodeHandle episode, Runnable task) {\n"
                             + "    return episode.serialized(task);\n"
                             + "  }\n}\n");

        assertThat(errors).as("instrument: the probe class path compiles an arm site through the fenced path")
                  .isEmpty();
    }

    @Test
    void controlProbe_armingACallbackThroughAnEpisode_compiles() throws Exception {
        var errors = compile(HEAD
                             + "  static Consumer<String> callback(LeaderElectionContext.EpisodeHandle episode) {\n"
                             + "    return episode.serialized((String value) -> {});\n"
                             + "  }\n}\n");

        assertThat(errors).as("instrument: the probe class path compiles a callback arm site through the fenced path")
                  .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return ctx.serialized(task); }",
        "static Consumer<String> callback(LeaderElectionContext ctx) { return ctx.serialized((String value) -> {}); }"
    })
    void armSite_withoutAnEpisode_doesNotCompile(String armSite) throws Exception {
        var errors = compile(HEAD + "  " + armSite + "\n}\n");

        assertThat(errors).as("an arm site that hands a task to the serial executor without an episode identity compiled")
                  .isNotEmpty();
        assertThat(errors).as("the refusal names the removed entry, not some other defect of the probe")
                  .anyMatch(message -> message.contains("serialized"));
    }

    // ---- #3846 round 1 (F1): the paths that used to opt out of the fence are closed by the type system ----
    private static final String SERIALLY_ARM_SITE = "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return () -> ctx.serially(task); }";
    private static final String LAMBDA_IDENTITY_ARM_SITE = "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return ctx.serializedFor(() -> true, task); }";
    private static final String SUBMIT_ARM_SITE = "static Runnable arm(LeaderElectionContext ctx, org.pragmatica.consensus.fsm.ClusterFsmEvent event) { return () -> ctx.submit(event); }";
    private static final String STOP_ARM_SITE = "static Runnable arm(LeaderElectionContext ctx) { return () -> ctx.stop(); }";
    private static final String FORGED_INBOUND_SITE = "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return () -> ctx.new Inbound(true).serially(task); }";
    private static final String FORGED_HANDLE_SITE = "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return ctx.new EpisodeHandle(null).serialized(task); }";
    private static final String FSM_DISPATCH_ARM_SITE = "static Runnable arm(LeaderElectionContext ctx, org.pragmatica.consensus.fsm.ClusterFsmEvent event) { return () -> ctx.fsm().dispatch(event); }";
    private static final String FSM_ACCESSOR_SITE = "static Runnable arm(LeaderElectionContext ctx) { return () -> ctx.fsm(); }";
    private static final String FORGED_IDENTITY_SITE = "static final class Forged implements LeaderElectionContext.EpisodeIdentity { public boolean isLive() { return true; } }";

    /// The ruling (2026-11-27): an arm site that reaches the serial executor without an episode identity of the entry it
    /// arms from does not compile. The rows are the two failure inputs (`() -> ctx.serially(task)` from a state and
    /// `ctx.serializedFor(() -> true, task)`), the other inbound doors a state could arm through (`submit`, `stop`), and
    /// the three ways to forge what those doors now require (the inbound receiver's constructor, an episode handle's
    /// constructor, an identity), and
    /// `ctx.fsm().dispatch` (the FSM is not exposed: a dispatch from an async callback would skip the fence). Each refusal names the member it
    /// refuses, so a red that comes from a probe that cannot compile at all does not pass as the rule.
    @ParameterizedTest
    @MethodSource("unfencedArmSites")
    void armSite_throughAnUnfencedPath_doesNotCompile(String armSite, String refusedMember) throws Exception {
        var errors = compile(HEAD + "  " + armSite + "\n}\n");

        assertThat(errors).as("an arm site that opts out of the episode fence compiled").isNotEmpty();
        assertThat(errors).as("the refusal names the member it refuses (" + refusedMember + "), not some other defect of the probe")
                  .anyMatch(message -> message.contains(refusedMember));
    }

    static Stream<Arguments> unfencedArmSites() {
        return Stream.of(Arguments.of(SERIALLY_ARM_SITE, "serially"),
                         Arguments.of(LAMBDA_IDENTITY_ARM_SITE, "serializedFor"),
                         Arguments.of(SUBMIT_ARM_SITE, "submit"),
                         Arguments.of(STOP_ARM_SITE, "stop"),
                         Arguments.of(FORGED_INBOUND_SITE, "Inbound"),
                         Arguments.of(FORGED_HANDLE_SITE, "EpisodeHandle"),
                         Arguments.of(FORGED_IDENTITY_SITE, "EpisodeIdentity"),
                         Arguments.of(FSM_DISPATCH_ARM_SITE, "fsm"),
                         Arguments.of(FSM_ACCESSOR_SITE, "fsm"));
    }

    // ---- #3846 round 2 (v3855c C1-C4): the rows the round-1 enforcer left compiling ----
    private static final String EVENT = "org.pragmatica.consensus.fsm.ClusterFsmEvent";
    private static final String CONTROL_FENCED = "static Runnable arm(LeaderElectionContext.EpisodeHandle episode, Runnable task) { return episode.serialized(task); }";
    private static final String CONTROL_CURRENT = "static LeaderElectionState arm(LeaderElectionContext ctx) { return ctx.current(); }";

    /// One round-2 row: the `code` that must not compile, and the `control` next to it that must compile at every head.
    /// `refusedMember` is what the refusal must name.
    private record Round2Row(String name, String code, String control, String refusedMember) {}

    private static final List<Round2Row> ROUND_2_ROWS = List.of(
        new Round2Row("followUp from an off-frame lambda",
                      "static Runnable arm(LeaderElectionContext ctx, " + EVENT + " event) { return () -> ctx.followUp(event); }",
                      "static void onFrame(LeaderElectionContext.EpisodeHandle episode, " + EVENT + " event) { episode.followUp(event); }",
                      "followUp"),
        new Round2Row("serially(null, task)",
                      "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return () -> ctx.serially(null, task); }",
                      CONTROL_FENCED,
                      "serially"),
        new Round2Row("serializedFor(ctx.current(), task)",
                      "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return () -> ctx.serializedFor(ctx.current(), task).run(); }",
                      CONTROL_FENCED,
                      "serializedFor"),
        new Round2Row("serializedFor(null, task)",
                      "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return ctx.serializedFor(null, task); }",
                      CONTROL_FENCED,
                      "serializedFor"),
        new Round2Row("serializedFor((EpisodeIdentity) null, task)",
                      "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return ctx.serializedFor((LeaderElectionContext.EpisodeIdentity) null, task); }",
                      CONTROL_FENCED,
                      "serializedFor"),
        new Round2Row("ctx.fsm()",
                      "static Runnable arm(LeaderElectionContext ctx) { return () -> ctx.fsm(); }",
                      CONTROL_CURRENT,
                      "fsm"));

    /// The instrument of every round-2 row: each control compiles, so a refusal of its row comes from the removed member
    /// and not from a probe or a class path that cannot compile that shape. ENABLED at every head.
    @Test
    void round2Controls_compile() throws Exception {
        for (var row : ROUND_2_ROWS) {
            assertThat(compile(HEAD + "  " + row.control() + "\n}\n"))
                .as("instrument: the control of row '" + row.name() + "' must compile at every head")
                .isEmpty();
        }
    }

    /// Control of the `ctx.fsm()` row: reading the current state compiles, so the refusal of `ctx.fsm()` is about that member.
    @Test
    void controlProbe_readingTheCurrentState_compiles() throws Exception {
        assertThat(compile(HEAD + "  " + CONTROL_CURRENT + "\n}\n"))
            .as("instrument: the probe class path compiles a read of the current state").isEmpty();
    }

    @ParameterizedTest
    @MethodSource("round2Rows")
    void round2Row_doesNotCompile(String name, String code, String refusedMember) throws Exception {
        var errors = compile(HEAD + "  " + code + "\n}\n");

        assertThat(errors).as("round-2 row '" + name + "' compiled: an off-frame path to the executor, the FSM or followUp exists")
                  .isNotEmpty();
        assertThat(errors).as("the refusal names the member it refuses (" + refusedMember + "), not another defect of the probe")
                  .anyMatch(message -> message.contains(refusedMember));
    }

    static Stream<Arguments> round2Rows() {
        return ROUND_2_ROWS.stream()
                           .map(row -> Arguments.of(row.name(), row.code(), row.refusedMember()));
    }

    // ---- #3846 round 3, lane A tripwires: every "unwritable under INV-E" row of memo r3 section 4 ----
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
    @MethodSource("laneCRows")
    void tripwireLaneC_atHead_compiles(TripwireRow row) throws Exception {
        assertCompilesAtHead(row, "tripwireLaneC_underInvE_doesNotCompile");
    }

    @ParameterizedTest
    @MethodSource("laneMRows")
    void tripwireLaneM_atHead_compiles(TripwireRow row) throws Exception {
        assertCompilesAtHead(row, "tripwireLaneM_underInvE_doesNotCompile");
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

    @Disabled("lane C: enable when claimInbound is package-private (O08)")
    @ParameterizedTest
    @MethodSource("laneCRows")
    void tripwireLaneC_underInvE_doesNotCompile(TripwireRow row) throws Exception {
        assertRefusedUnderInvE(row);
    }

    @Disabled("lane M: enable when FsmWithContext and FsmBackedLeaderManager lose fsm()/inbound() (K16, K19, O02, O03, O06, O07)")
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

    /// Attribution only (`JavacTask#analyze`): the errors are the compiler's, and no class file is written.
    private static List<String> compile(String code) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) compiler.getTask(null,
                                                null,
                                                diagnostics,
                                                List.of("-proc:none", "-cp", System.getProperty("java.class.path")),
                                                null,
                                                List.of(new Source(code).file()));

        task.analyze();

        return diagnostics.getDiagnostics()
                          .stream()
                          .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                          .map(diagnostic -> diagnostic.getMessage(null))
                          .toList();
    }
}
