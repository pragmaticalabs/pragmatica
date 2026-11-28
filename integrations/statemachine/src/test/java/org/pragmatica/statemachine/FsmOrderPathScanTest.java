/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.statemachine;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// #3846 scan S2 (memo r3 §2.4): the internal order paths of the statemachine. Every call of `dispatch`, `followUp`
/// and `handleInFrame` in Fsm.java and TransitionRequest.java, keyed by its ENCLOSING METHOD (never by a line), is an
/// EXACT committed set. A new path into the dispatch frame (a new caller of `handleInFrame`, a second
/// `fsm.dispatch` in TransitionRequest) turns this red; lane S may move code, and a new key needs a new row in the
/// memo's paths table (§1).
///
/// Positive control: a fixture source with one planted `fsm.dispatch(event)` inside `ignore`, scanned through the
/// same entry point, must be reported. A scan without its control is not evidence.
class FsmOrderPathScanTest {
    private static final Path MAIN = Path.of("src/main/java/org/pragmatica/statemachine");
    private static final Set<String> NAMES = Set.of("dispatch", "followUp", "handleInFrame");

    /// Head set at lane S (#3846 frame check): the frame check adds no path (`requireFrame` calls none of the three names),
    /// so the set equals the one before it. Entries move only with a change of the order paths.
    private static final Map<String, Integer> HEAD_SET = Map.of("Fsm:Fsm.dispatch > handleInFrame", 1,
                                                                "Fsm:Fsm.followUp > dispatch", 1,
                                                                "Fsm:Fsm.dispatchRecordingFailure > dispatch", 1,
                                                                "TransitionRequest:TransitionRequest.transitionTo > dispatch", 1);

    @Test
    void orderPaths_internalCallsOfDispatchFollowUpAndHandleInFrame_equalTheCommittedSet() throws IOException {
        var sources = Map.of("Fsm", Files.readString(MAIN.resolve("Fsm.java")),
                             "TransitionRequest", Files.readString(MAIN.resolve("TransitionRequest.java")));

        assertThat(scan(sources)).containsExactlyInAnyOrderEntriesOf(HEAD_SET);
    }

    @Test
    void positiveControl_aPlantedDispatchInIgnore_isReportedByTheSameEntryPoint() throws IOException {
        var request = Files.readString(MAIN.resolve("TransitionRequest.java"));
        var planted = request.substring(0, request.lastIndexOf('}'))
                      + "\n    void plantedIgnore() { fsm.dispatch(event); }\n}\n";
        var sources = Map.of("Fsm", Files.readString(MAIN.resolve("Fsm.java")), "TransitionRequest", planted);
        var actual = scan(sources);

        assertThat(actual).containsEntry("TransitionRequest:TransitionRequest.plantedIgnore > dispatch", 1);
        assertThat(actual).hasSize(HEAD_SET.size() + 1);
        assertThat(catchThrowable(() -> assertThat(actual).containsExactlyInAnyOrderEntriesOf(HEAD_SET)))
            .as("the exact-set check of the committed set goes RED on the planted internal dispatch")
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void positiveControl_aPlantedDispatchInTheFrameCheckedFromState_goesRedAgainstTheCommittedSet() throws IOException {
        var request = Files.readString(MAIN.resolve("TransitionRequest.java"));
        var planted = request.replace("fsm.requireFrame(frame);\n\n        return entered.state();",
                                      "fsm.requireFrame(frame);\n        fsm.dispatch(event);\n\n        return entered.state();");
        var sources = Map.of("Fsm", Files.readString(MAIN.resolve("Fsm.java")), "TransitionRequest", planted);
        var actual = scan(sources);

        assertThat(planted).as("the plant landed").isNotEqualTo(request);
        assertThat(actual).containsEntry("TransitionRequest:TransitionRequest.fromState > dispatch", 1);
        assertThat(catchThrowable(() -> assertThat(actual).containsExactlyInAnyOrderEntriesOf(HEAD_SET)))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void positiveControl_aPlantedHandleInFrameCaller_isReported() throws IOException {
        var fsm = Files.readString(MAIN.resolve("Fsm.java"));
        var planted = fsm.substring(0, fsm.lastIndexOf('}'))
                      + "\n    void plantedFrame(E event) { handleInFrame(event, new java.util.ArrayDeque<>()); }\n}\n";
        var sources = Map.of("Fsm", planted, "TransitionRequest", Files.readString(MAIN.resolve("TransitionRequest.java")));

        assertThat(scan(sources)).containsEntry("Fsm:Fsm.plantedFrame > handleInFrame", 1);
    }

    private static Map<String, Integer> scan(Map<String, String> sources) {
        var hits = new TreeMap<String, Integer>();

        sources.forEach((file, source) -> new CallScanner(file, hits).scan(parse(source), null));
        return hits;
    }

    private static com.sun.source.tree.CompilationUnitTree parse(String source) {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Scanned.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try {
            var task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                                               .getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(file));
            var units = task.parse();

            assertThat(diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList())
                .as("scan input parses").isEmpty();
            return units.iterator().next();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CallScanner extends TreeScanner<Void, Void> {
        private final String file;
        private final Map<String, Integer> hits;
        private final Deque<String> classes = new ArrayDeque<>();
        private String method = "";

        CallScanner(String file, Map<String, Integer> hits) {
            this.file = file;
            this.hits = hits;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            classes.addLast(node.getSimpleName().isEmpty() ? "$anon" : node.getSimpleName().toString());
            super.visitClass(node, unused);
            classes.removeLast();
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            var outer = method;

            method = node.getName().toString();
            super.visitMethod(node, unused);
            method = outer;
            return null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            var outer = method;

            method = method.isEmpty() ? "<field:" + node.getName() + ">" : method;
            super.visitVariable(node, unused);
            method = outer;
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            record(nameOf(node.getMethodSelect()));
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            record(node.getName().toString());
            return super.visitMemberReference(node, unused);
        }

        private void record(String name) {
            if (NAMES.contains(name)) {
                hits.merge(file + ":" + String.join(".", classes) + "." + method + " > " + name, 1, Integer::sum);
            }
        }

        private static String nameOf(ExpressionTree select) {
            return switch (select) {
                case IdentifierTree id -> id.getName().toString();
                case MemberSelectTree member -> member.getIdentifier().toString();
                default -> select.toString();
            };
        }
    }
}
