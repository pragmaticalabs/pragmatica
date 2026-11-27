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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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

    private static List<String> compile(String code) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var out = Files.createTempDirectory("episode-entry-path");

        try {
            compiler.getTask(null,
                             null,
                             diagnostics,
                             List.of("-proc:none", "-d", out.toString(), "-cp", System.getProperty("java.class.path")),
                             null,
                             List.of(new Source(code).file()))
                    .call();

            return diagnostics.getDiagnostics()
                              .stream()
                              .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                              .map(diagnostic -> diagnostic.getMessage(null))
                              .toList();
        } finally {
            deleteRecursively(out);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());
        }
    }
}
