package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-ASYNC-01: a Promise on* callback that writes shared state (WARNING).
class CstPromiseCallbackWriteRuleTest {
    private static final String RULE_ID = "JBCT-ASYNC-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class WritePatterns {
        @Test
        void flags_fieldAssignment() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_compoundAssignmentAndIncrement() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private int total;
                        void run(Promise<Integer> p) {
                            p.onSuccess(n -> total += n);
                        }
                    }
                    """));
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private int failures;
                        void run(Promise<Integer> p) {
                            p.onFailure(_ -> failures++);
                        }
                    }
                    """));
        }

        @Test
        void flags_thisQualifiedAssignment() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private String last;
                        void run(Promise<String> p) {
                            p.onSuccess(v -> this.last = v);
                        }
                    }
                    """));
        }

        @Test
        void flags_mapPutOnField() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private final Map<String, String> index = new HashMap<>();
                        void run(Promise<String> p) {
                            p.onSuccess(v -> index.put(v, v));
                        }
                    }
                    """));
        }

        @Test
        void flags_collectionMutationsOnField() {
            for (var call : List.of("seen.add(v)", "seen.remove(v)", "seen.clear()", "seen.addAll(List.of(v))")) {
                assertTrue(flags("""
                        package org.example;
                        class Foo {
                            private final Set<String> seen = new HashSet<>();
                            void run(Promise<String> p) {
                                p.onSuccess(v -> %s);
                            }
                        }
                        """.formatted(call)), call);
            }
        }

        @Test
        void flags_atomicSetsAndUpdates() {
            for (var call : List.of("flag.set(false)",
                                    "counter.incrementAndGet()",
                                    "counter.compareAndSet(0, 1)",
                                    "counter.getAndIncrement()",
                                    "epoch.lazySet(2)")) {
                assertTrue(flags("""
                        package org.example;
                        class Foo {
                            private final AtomicBoolean flag = new AtomicBoolean();
                            private final AtomicInteger counter = new AtomicInteger();
                            private final AtomicInteger epoch = new AtomicInteger();
                            void run(Promise<String> p) {
                                p.onResultRun(() -> %s);
                            }
                        }
                        """.formatted(call)), call);
            }
        }

        @Test
        void flags_everyCallbackForm() {
            for (var form : List.of("onSuccess", "onFailure", "onResult", "onSuccessRun", "onFailureRun", "onResultRun",
                                    "onSuccessAsync", "onFailureAsync", "onResultAsync", "onSuccessRunAsync", "onFailureRunAsync",
                                    "onResultRunAsync")) {
                assertTrue(flags("""
                        package org.example;
                        class Foo {
                            private boolean done;
                            void run(Promise<String> p) {
                                p.%s(%s);
                            }
                        }
                        """.formatted(form, argumentFor(form))), form);
            }
        }

        @Test
        void flags_blockLambdaWrite() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private final Map<String, String> index = new HashMap<>();
                        void run(Promise<String> p) {
                            p.onSuccess(v -> {
                                log.info("got {}", v);
                                index.put(v, v);
                            });
                        }
                    }
                    """));
        }

        @Test
        void flags_setterCall() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<String> p) {
                            p.onSuccess(v -> setLast(v));
                        }
                    }
                    """));
        }

        @Test
        void reportsFirstLineAndColumnOfTheCallback() {
            var found = lint("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            p
                              .map(String::trim)
                              .onSuccess(_ -> done = true);
                        }
                    }
                    """);

            assertEquals(1, found.size());
            assertEquals(7, found.getFirst()
                                 .line());
            assertEquals(DiagnosticSeverity.WARNING,
                         found.getFirst()
                              .severity());
        }
    }

    @Nested
    class Delegation {
        @Test
        void flags_methodReferenceToWriter() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            p.onSuccess(this::land);
                        }
                        private void land(String v) {
                            done = true;
                        }
                    }
                    """));
        }

        @Test
        void flags_lambdaCallingWriter() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private final Map<String, String> index = new HashMap<>();
                        void run(Promise<String> p) {
                            p.onSuccess(v -> record(v));
                        }
                        private void record(String v) {
                            index.put(v, v);
                        }
                    }
                    """));
        }

        @Test
        void flags_writeTwoMethodsDeep() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> outer());
                        }
                        private void outer() {
                            inner();
                        }
                        private void inner() {
                            done = true;
                        }
                    }
                    """));
        }

        @Test
        void messageNamesTheCallPath() {
            var found = lint("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> outer());
                        }
                        private void outer() {
                            done = true;
                        }
                    }
                    """);

            assertEquals(1, found.size());
            assertTrue(found.getFirst()
                            .message()
                            .contains("via outer"),
                       found.getFirst()
                            .message());
        }

        @Test
        void clean_whenDelegateOnlyLogs() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<String> p) {
                            p.onSuccess(this::note);
                        }
                        private void note(String v) {
                            log.info("got {}", v);
                        }
                    }
                    """));
        }

        @Test
        void clean_whenDelegateWritesOnlyItsOwnLocals() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<String> p) {
                            p.onSuccess(this::format);
                        }
                        private void format(String v) {
                            var out = new StringBuilder();
                            int n = 0;
                            n += v.length();
                            out.append(v);
                            log.info(out.toString());
                        }
                    }
                    """));
        }

        @Test
        void clean_onRecursiveDelegates() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> ping());
                        }
                        private void ping() {
                            pong();
                        }
                        private void pong() {
                            ping();
                        }
                    }
                    """));
        }
    }

    @Nested
    class ReceiverProof {
        @Test
        void flags_promiseFactoryChain() {
            assertTrue(flags(chainIn("Promise.success(1).onSuccess(_ -> done = true);")));
        }

        @Test
        void flags_asyncChain() {
            assertTrue(flags(chainIn("parse(raw).async().onSuccess(_ -> done = true);")));
        }

        @Test
        void flags_timeoutChain() {
            assertTrue(flags(chainIn("lookup().timeout(span).onFailure(_ -> done = true);")));
        }

        @Test
        void flags_afterWithCombinator() {
            assertTrue(flags(chainIn("source.withSuccess(v -> seen(v)).onSuccess(_ -> done = true);")));
        }

        @Test
        void flags_fieldDeclaredPromise() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        private Promise<String> pending;
                        void run() {
                            pending.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_localInferredFromPromiseChain() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run() {
                            var p = Promise.success(1);
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_callToMethodDeclaredToReturnPromise() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> load() { return null; }
                        void run() {
                            load().onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_returnedChainInPromiseMethod() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> run() {
                            return backend.load().onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_chainAfterIfHeader() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(boolean go, Promise<String> p) {
                            if (go) p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_resultVariable() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Result<String> r) {
                            r.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_resultFactoryChain() {
            assertFalse(flags(chainIn("Result.success(1).onSuccess(_ -> done = true);")));
        }

        @Test
        void clean_resultAfterAwait() {
            assertFalse(flags(chainIn("Promise.success(1).await().onSuccess(_ -> done = true);")));
        }

        @Test
        void clean_resultAfterAwaitAndMap() {
            assertFalse(flags(chainIn("Promise.success(1).await().map(Foo::wrap).onFailure(_ -> done = true);")));
        }

        @Test
        void clean_returnedChainInResultMethod() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> other() { return null; }
                        Result<String> run() {
                            return backend.parse().onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_returnInsideLambdaBelongsToTheLambda() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> run() {
                            return source.flatMap(v -> {
                                return parse(v).onSuccess(_ -> done = true);
                            });
                        }
                    }
                    """));
        }

        @Test
        void clean_receiverTypedInAnotherFile() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> keep() { return null; }
                        void run() {
                            repository.find(id).onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_nameDeclaredBothPromiseAndResult() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void a(Promise<String> value) {}
                        void b(Result<String> value) {
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_fileWithoutPromise() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Optional<String> p) {
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }
    }

    @Nested
    class ExemptEffects {
        @Test
        void clean_loggingOnly() {
            assertFalse(flags(chainIn("Promise.success(1).onSuccess(v -> log.info(\"got {}\", v));")));
        }

        @Test
        void clean_loggingFailure() {
            assertFalse(flags(chainIn("Promise.success(1).onFailure(c -> LOG.error(\"failed: {}\", c.message()));")));
        }

        @Test
        void clean_schedulingOnly() {
            assertFalse(flags(chainIn("Promise.success(1).onFailure(_ -> scheduler.schedule(this::retry, delay));")));
        }

        @Test
        void clean_resolvingAnotherPromise() {
            assertFalse(flags(chainIn("Promise.success(1).onSuccess(v -> other.succeed(v));")));
            assertFalse(flags(chainIn("Promise.success(1).onFailure(c -> other.fail(c));")));
            assertFalse(flags(chainIn("Promise.success(1).onResult(other::resolve);")));
        }

        @Test
        void clean_lambdaParameterMutation() {
            assertFalse(flags(chainIn("Promise.success(list).onSuccess(l -> l.add(1));")));
        }

        @Test
        void clean_lambdaLocalWrites() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<String> p) {
                            p.onSuccess(v -> {
                                var out = new StringBuilder();
                                int n = 0;
                                n++;
                                out.append(v);
                                log.info(out.toString());
                            });
                        }
                    }
                    """));
        }

        @Test
        void clean_comparisonIsNotAssignment() {
            assertFalse(flags(chainIn("Promise.success(1).onSuccess(v -> log.info(\"{}\", v == 1 || v <= 2 || v >= 3 || v != 4));")));
        }

        @Test
        void clean_methodReferenceToLogger() {
            assertFalse(flags(chainIn("Promise.success(1).onSuccess(log::info);")));
        }

        @Test
        void clean_withCombinators() {
            assertFalse(flags(chainIn("Promise.success(1).withSuccess(_ -> done = true);")));
            assertFalse(flags(chainIn("Promise.success(1).withFailure(_ -> done = true);")));
            assertFalse(flags(chainIn("Promise.success(1).withResult(_ -> done = true);")));
        }
    }

    @Nested
    class Suppression {
        @Test
        void suppressed_byDetachedCommentWithReason() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            // detached: nothing reads done until the next tick
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void suppressed_byDetachedCommentInsideTheStatement() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            p.map(String::trim)
                             // detached: health-score heuristic, a millisecond of lag is benign
                             .onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void notSuppressed_byDetachedCommentWithoutReason() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            // detached
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void notSuppressed_byDetachedCommentOnAnEarlierStatement() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            // detached: first one only
                            p.onFailure(_ -> log.warn("x"));

                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void suppressed_bySuppressWarnings() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        @SuppressWarnings("JBCT-ASYNC-01")
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }
    }

    private static String chainIn(String statement) {
        return """
                package org.example;
                class Foo {
                    private boolean done;
                    void run() {
                        %s
                    }
                }
                """.formatted(statement);
    }

    private static String argumentFor(String form) {
        return form.contains("Run")
               ? "() -> done = true"
               : form.contains("Result")
                 ? "r -> done = true"
                 : "v -> done = true";
    }

    private boolean flags(String source) {
        return lint(source).stream()
                           .anyMatch(diagnostic -> diagnostic.ruleId()
                                                             .equals(RULE_ID));
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of())
                     .stream()
                     .filter(diagnostic -> diagnostic.ruleId()
                                                     .equals(RULE_ID))
                     .toList();
    }
}
