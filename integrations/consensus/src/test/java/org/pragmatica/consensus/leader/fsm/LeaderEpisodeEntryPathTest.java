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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;


/// #3846 failure input 2: a timer or a promise callback armed through the old unfenced path. The enforcer is the
/// compiler, not a census a new arm site can bypass: `LeaderElectionContext` offers no way to hand a task to the
/// serial executor except through an episode identity, so an arm site written without one does not compile.
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
                             + "  static Runnable arm(LeaderElectionContext ctx, LeaderElectionState owner, Runnable task) {\n"
                             + "    return ctx.serializedFor(owner, task);\n"
                             + "  }\n}\n");

        assertThat(errors).as("instrument: the probe class path compiles an arm site through the fenced path")
                  .isEmpty();
    }

    @Test
    void controlProbe_armingACallbackThroughAnEpisode_compiles() throws Exception {
        var errors = compile(HEAD
                             + "  static Consumer<String> callback(LeaderElectionContext ctx, LeaderElectionState owner) {\n"
                             + "    return ctx.serializedFor(owner, (String value) -> {});\n"
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

    // ---- #3846 round 1 (F1): the two arm paths that still opt out of the fence ----
    private static final String SERIALLY_ARM_SITE = "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return () -> ctx.serially(task); }";
    private static final String LAMBDA_IDENTITY_ARM_SITE = "static Runnable arm(LeaderElectionContext ctx, Runnable task) { return ctx.serializedFor(() -> true, task); }";

    /// TRIPWIRE: asserts today's behaviour, that both arm sites COMPILE. It goes red the moment the fence becomes a
    /// compile-time rule. Lane B: that red is the goal. Flip this test to "must not compile" by deleting it and enabling
    /// `armSite_throughARemainingUnfencedPath_doesNotCompile` below.
    @ParameterizedTest
    @ValueSource(strings = {SERIALLY_ARM_SITE, LAMBDA_IDENTITY_ARM_SITE})
    void tripwire_armSite_throughARemainingUnfencedPath_stillCompiles(String armSite) throws Exception {
        var errors = compile(HEAD + "  " + armSite + "\n}\n");

        assertThat(errors).as("TRIPWIRE: this arm site no longer compiles, which is what #3846 asks for. Flip this test to "
                              + "\"must not compile\": delete it and enable armSite_throughARemainingUnfencedPath_doesNotCompile")
                  .isEmpty();
    }

    /// The real assertion (ruling 2026-11-27): an arm site that reaches the serial executor without an episode identity
    /// of the entry it arms from does not compile. `@Disabled` until lane B's enforcer lands.
    @Disabled("lane B: enable together with the compile-time enforcer and delete the tripwire above")
    @ParameterizedTest
    @ValueSource(strings = {SERIALLY_ARM_SITE, LAMBDA_IDENTITY_ARM_SITE})
    void armSite_throughARemainingUnfencedPath_doesNotCompile(String armSite) throws Exception {
        var errors = compile(HEAD + "  " + armSite + "\n}\n");

        assertThat(errors).as("an arm site that opts out of the episode fence compiled").isNotEmpty();
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
