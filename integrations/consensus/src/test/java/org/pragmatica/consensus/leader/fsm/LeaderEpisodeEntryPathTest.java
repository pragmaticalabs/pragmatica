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
