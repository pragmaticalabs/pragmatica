package org.pragmatica.jbct.async;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// JBCT-ASYNC-01 against real javac attribution: sources are compiled in memory with the processor installed and the real
/// `Promise`/`Result` on the classpath, and the reported warnings are read back.
class AsyncCallbackCheckTest {
    private static final String HEADER = """
            package org.example;
            import java.util.*;
            import java.util.concurrent.*;
            import java.util.concurrent.atomic.*;
            import org.pragmatica.lang.*;
            """;

    @Nested
    class Receiver {
        @Test
        void clean_chainOrderCombinatorsAndMaps() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.withSuccess(_ -> done = true);
                        p.withFailure(_ -> done = true);
                        p.withResult(_ -> done = true);
                        p.map(v -> { done = true; return v; });
                        p.flatMap(v -> { done = true; return Promise.success(v); });
                    }
                    """))).isEmpty();
        }

        @Test
        void flags_promiseParameter() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> done = true);
                    }
                    """))).containsExactly(9);
        }

        @Test
        void flags_promiseReturnedByAnotherClass() {
            var found = compile(Map.of("org.example.Repo", HEADER + """
                    public class Repo {
                        public Promise<String> find(String id) { return Promise.success(id); }
                    }
                    """,
                                       "org.example.Foo", HEADER + """
                    class Foo {
                        boolean done;
                        void run(Repo repo) {
                            repo.find("x").onSuccess(_ -> done = true);
                        }
                    }
                    """), "warning");

            assertThat(found.lines()).containsExactly(9);
        }

        @Test
        void clean_resultWithTheSameCallback() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Result<String> r) {
                        r.onSuccess(_ -> done = true);
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_resultReturnedByAnotherClassAfterAwait() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.await().onSuccess(_ -> done = true);
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_classNamedPromiseInAnotherPackage() {
            var found = compile(Map.of("other.Promise", """
                    package other;
                    public class Promise<T> {
                        public Promise<T> onSuccess(java.util.function.Consumer<T> c) { return this; }
                    }
                    """,
                                       "org.example.Foo", """
                    package org.example;
                    class Foo {
                        boolean done;
                        void run(other.Promise<String> p) {
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """), "warning");

            assertThat(found.lines()).isEmpty();
        }

        @Test
        void flags_everyCallbackForm() {
            for (var form : List.of("onSuccess(_ -> done = true)", "onFailure(_ -> done = true)", "onResult(_ -> done = true)",
                                    "onSuccessRun(() -> done = true)", "onFailureRun(() -> done = true)",
                                    "onResultRun(() -> done = true)", "onSuccessAsync(_ -> done = true)",
                                    "onFailureAsync(_ -> done = true)", "onResultAsync(_ -> done = true)",
                                    "onSuccessRunAsync(() -> done = true)", "onFailureRunAsync(() -> done = true)",
                                    "onResultRunAsync(() -> done = true)")) {
                assertThat(lines(body("boolean done;\nvoid run(Promise<String> p) {\n    p." + form + ";\n}\n")))
                    .as(form).containsExactly(9);
            }
        }
    }

    @Nested
    class Effects {
        @Test
        void clean_executorAndSchedulingByNameAreAllowed() {
            assertThat(lines(body("""
                    Executor executor;
                    Scheduler scheduler = new Scheduler();
                    static class Scheduler { void scheduleNext(String key) { } }
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> executor.execute(() -> {}));
                        p.onFailure(_ -> scheduler.scheduleNext("k"));
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_effectsInsideAScheduledTaskAndInsideLoggingArguments() {
            assertThat(lines(body("""
                    ScheduledExecutorService scheduler;
                    boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> scheduler.schedule(() -> done = true, 1, TimeUnit.SECONDS));
                        p.onFailure(c -> System.out.println(done = false));
                    }
                    """))).isEmpty();
        }

        @Test
        void flags_resolvingMethodsOfOtherTypes() {
            assertThat(lines(body("""
                    Latch latch = new Latch();
                    static class Latch { void succeed(String v) { } }
                    void run(Promise<String> p) {
                        p.onSuccess(v -> latch.succeed(v));
                    }
                    """))).hasSize(1);
        }

        @Test
        void flags_discardedValueCalls() {
            var gate = "static class Gate { String read() { return \"x\"; } Unit act(String v) { return Unit.unit(); } }\n"
                       + "Gate gate = new Gate();\n";

            assertThat(lines(body(gate + "void run(Promise<String> p) { p.onSuccess(_ -> gate.read()); }\n"))).hasSize(1);
            assertThat(lines(body(gate + "void run(Promise<String> p) { p.onSuccess(_ -> { gate.read(); }); }\n"))).hasSize(1);
            assertThat(lines(body(gate + "void run(Promise<String> p, Option<String> o) { p.onSuccess(_ -> o.onPresent(v -> gate.act(v))); }\n")))
                .hasSize(1);
        }

        @Test
        void clean_valueUsedAndCombinatorWithCleanBody() {
            assertThat(lines(body("""
                    void run(Promise<String> p, Option<String> o) {
                        p.onSuccess(_ -> o.onPresent(v -> System.out.println(v)));
                    }
                    """))).isEmpty();
        }

        @Test
        void flags_valueReturningMutationOfSharedState() {
            assertThat(lines(body("""
                    Map<String, String> map = new HashMap<>();
                    void run(Promise<String> p) {
                        p.onSuccess(v -> { var previous = map.put(v, v); System.out.println(previous); });
                    }
                    """))).hasSize(1);
        }

        @Test
        void flags_voidCallOnTheCallbacksOwnValue() {
            var found = compile(Map.of("org.example.Gate", HEADER + "public class Gate { public void release(String k) { } }\n",
                                       "org.example.Foo", HEADER + """
                    class Foo {
                        void run(Promise<Gate> p) {
                            p.onSuccess(g -> g.release("k"));
                        }
                    }
                    """), "warning");

            assertThat(found.lines()).hasSize(1);
        }

        @Test
        void clean_fieldWriteOnALocalObject() {
            assertThat(lines(body("""
                    static class Holder { int value; }
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> { var h = new Holder(); h.value = 3; System.out.println(h.value); });
                    }
                    """))).isEmpty();
        }

        @Test
        void flags_sharedRootsThroughThisStaticAndCalls() {
            for (var call : List.of("this.map.put(v, v)", "Foo.MAP.put(v, v)", "map().put(v, v)", "((Map<String, String>) map).put(v, v)")) {
                assertThat(lines(body("""
                        Map<String, String> map = new HashMap<>();
                        static final Map<String, String> MAP = new HashMap<>();
                        Map<String, String> map() { return map; }
                        void run(Promise<String> p) {
                            p.onSuccess(v -> %s);
                        }
                        """.formatted(call)))).as(call).hasSize(1);
            }
        }

        @Test
        void flags_referenceHandedToOptionalOrCombinator() {
            assertThat(lines(body("""
                    Gate gate = new Gate();
                    static class Gate { void release(String key) { } }
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> { var slot = Optional.of("x"); slot.ifPresent(gate::release); });
                    }
                    """))).hasSize(1);
        }

        @Test
        void flags_fieldAssignments() {
            for (var write : List.of("done = true", "total += 1", "total++", "++total", "this.last = \"x\"", "Foo.counter++",
                                     "slots[0] = 1", "holder.value = 2")) {
                assertThat(lines(body("""
                        boolean done; int total; String last; static int counter; int[] slots = new int[1];
                        Holder holder = new Holder();
                        static class Holder { int value; }
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> %s);
                        }
                        """.formatted(write)))).as(write).hasSize(1);
            }
        }

        @Test
        void flags_mutationsOfFieldCollectionsAndAtomics() {
            for (var call : List.of("map.put(v, v)", "list.add(v)", "set.remove(v)", "queue.offer(v)", "map.clear()",
                                    "flag.set(true)", "count.incrementAndGet()", "count.compareAndSet(0, 1)",
                                    "ref.getAndSet(v)", "map.computeIfAbsent(v, k -> k)", "builder.append(v)")) {
                assertThat(lines(body("""
                        Map<String, String> map = new HashMap<>(); List<String> list = new ArrayList<>();
                        Set<String> set = new HashSet<>(); Queue<String> queue = new ArrayDeque<>();
                        AtomicBoolean flag = new AtomicBoolean(); AtomicInteger count = new AtomicInteger();
                        AtomicReference<String> ref = new AtomicReference<>(); StringBuilder builder = new StringBuilder();
                        void run(Promise<String> p) {
                            p.onSuccess(v -> %s);
                        }
                        """.formatted(call)))).as(call).hasSize(1);
            }
        }

        @Test
        void flags_mutationOfACapturedLocal() {
            assertThat(lines(body("""
                    void run(Promise<String> p) {
                        var seen = new ArrayList<String>();
                        p.onSuccess(v -> seen.add(v));
                    }
                    """))).hasSize(1);
        }

        @Test
        void flags_blockLambda() {
            assertThat(lines(body("""
                    Map<String, String> index = new HashMap<>();
                    void run(Promise<String> p) {
                        p.onSuccess(v -> {
                            System.out.println(v);
                            index.put(v, v);
                        });
                    }
                    """))).hasSize(1);
        }

        @Test
        void clean_localsAndParametersOfTheCallback() {
            assertThat(lines(body("""
                    void run(Promise<List<String>> p) {
                        p.onSuccess(values -> {
                            var out = new ArrayList<String>();
                            int n = 0;
                            n++;
                            out.add("x");
                            values.add("y");
                            for (var item : values) { out.add(item); }
                            values.forEach(item -> out.add(item));
                        });
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_loggingSchedulingAndResolvingAnotherPromise() {
            assertThat(lines(body("""
                    Promise<String> other = Promise.promise();
                    ScheduledExecutorService scheduler;
                    void run(Promise<String> p) {
                        p.onSuccess(v -> System.out.println(v));
                        p.onSuccess(v -> other.succeed(v));
                        p.onFailure(c -> other.fail(c));
                        p.onResult(other::resolve);
                        p.onFailureRun(() -> scheduler.schedule(() -> {}, 1, TimeUnit.SECONDS));
                    }
                    """))).isEmpty();
        }

        @Test
        void flags_voidCallOnACollaboratorField() {
            var found = compile(Map.of("org.example.Gate", HEADER + """
                    class Gate {
                        void release(String key) { }
                    }
                    """,
                                       "org.example.Foo", HEADER + """
                    class Foo {
                        Gate gate = new Gate();
                        void run(Promise<String> p) {
                            p.onResultRun(() -> gate.release("k"));
                        }
                    }
                    """), "warning");

            assertThat(found.lines()).hasSize(1);
        }

        @Test
        void flags_referenceHandedToACombinatorInsideTheCallback() {
            assertThat(lines(body("""
                    Queue<String> slots = new ArrayDeque<>();
                    Gate gate = new Gate();
                    static class Gate { void release(String key) { } }
                    void run(Promise<String> p, Option<String> slot) {
                        p.onSuccess(_ -> slot.onPresent(gate::release));
                    }
                    """))).hasSize(1);
        }

        @Test
        void clean_valueReadsAndLocalDeclarations() {
            assertThat(lines(body("""
                    Map<String, String> map = new HashMap<>();
                    void run(Promise<String> p) {
                        p.onSuccess(v -> {
                            var size = map.size();
                            var upper = v.toUpperCase();
                            System.out.println(size + upper + Math.max(1, 2));
                        });
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_readsOnly() {
            assertThat(lines(body("""
                    Map<String, String> map = new HashMap<>();
                    void run(Promise<String> p) {
                        p.onSuccess(v -> System.out.println(map.get(v) + map.size() + map.containsKey(v)));
                    }
                    """))).isEmpty();
        }
    }

    @Nested
    class Delegation {
        @Test
        void flags_selfRecursionWithoutOverflow() {
            assertThat(lines(body("""
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> loop());
                    }
                    void loop() { loop(); }
                    """))).hasSize(1);
        }

        @Test
        void flags_chainLongerThanTheFollowLimitAsUnknownEffect() {
            assertThat(lines(body("""
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> m1());
                    }
                    void m1() { m2(); }
                    void m2() { m3(); }
                    void m3() { m4(); }
                    void m4() { System.out.println("x"); }
                    """))).hasSize(1);
        }

        @Test
        void message_namesTheDelegate() {
            var found = compile(Map.of("org.example.Foo", HEADER + """
                    class Foo {
                        boolean done;
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> mark());
                        }
                        void mark() { done = true; }
                    }
                    """), "warning");

            assertThat(found.messages().getFirst()).contains("(via mark)");
        }

        @Test
        void flags_methodReferenceToAWriter() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(this::land);
                    }
                    void land(String v) { done = true; }
                    """))).hasSize(1);
        }

        @Test
        void flags_lambdaCallingAWriter() {
            var found = compile(Map.of("org.example.Foo", HEADER + """
                    class Foo {
                        final Map<String, String> index = new HashMap<>();
                        void run(Promise<String> p) {
                            p.onSuccess(v -> record(v));
                        }
                        void record(String v) { index.put(v, v); }
                    }
                    """), "warning");

            assertThat(found.lines()).hasSize(1);
            assertThat(found.messages().getFirst()).contains("(via record)");
        }

        @Test
        void flags_staticMethodReferenceAndSharedArgument() {
            assertThat(lines(body("""
                    final AtomicInteger remaining = new AtomicInteger(2);
                    void run(Promise<String> p) {
                        p.onResultRun(() -> release(remaining));
                    }
                    static void release(AtomicInteger counter) { counter.decrementAndGet(); }
                    """))).hasSize(1);
        }

        @Test
        void clean_whenTheCallbacksOwnValueIsHandedToTheDelegate() {
            assertThat(lines(body("""
                    void run(Promise<List<String>> p) {
                        p.onSuccess(values -> fill(values));
                    }
                    static void fill(List<String> target) { target.add("x"); }
                    """))).isEmpty();
        }

        @Test
        void flags_writeThreeMethodsDeep() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> m1());
                    }
                    void m1() { m2(); }
                    void m2() { m3(); }
                    void m3() { done = true; }
                    """))).hasSize(1);
        }

        @Test
        void clean_delegateThatOnlyLogsAndSchedules() {
            assertThat(lines(body("""
                    ScheduledExecutorService scheduler;
                    void run(Promise<String> p) {
                        p.onSuccess(this::note);
                        p.onFailure(_ -> retry());
                    }
                    void note(String v) { System.out.println(v); }
                    void retry() { note("x"); scheduler.schedule(() -> {}, 1, TimeUnit.SECONDS); }
                    """))).isEmpty();
        }

        @Test
        void flags_voidMethodOfAnotherClass() {
            var found = compile(Map.of("org.example.Other", HEADER + """
                    class Other {
                        void land(String v) { }
                    }
                    """,
                                       "org.example.Foo", HEADER + """
                    class Foo {
                        void run(Promise<String> p, Other other) {
                            p.onSuccess(other::land);
                            p.onSuccess(v -> other.land(v));
                        }
                    }
                    """), "warning");

            assertThat(found.lines()).hasSize(2);
        }

        @Test
        void flags_callbackThatIsNotALambdaOrReference() {
            var found = compile(Map.of("org.example.Foo", HEADER + """
                    class Foo {
                        java.util.function.Consumer<String> handler;
                        void run(Promise<String> p) {
                            p.onSuccess(handler);
                        }
                    }
                    """), "warning");

            assertThat(found.lines()).hasSize(1);
            assertThat(found.messages().getFirst()).contains("effect is unknown");
        }

        @Test
        void clean_callbackForwardedFromAParameter() {
            assertThat(lines(body("""
                    Promise<String> watch(Promise<String> p, java.util.function.Consumer<String> handler) {
                        return p.onSuccess(handler).onResultRun(() -> System.out.println("done"));
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_constructorOfAClassThatAssignsItsFields() {
            assertThat(lines(body("""
                    String value;
                    void run(Promise<String> p) {
                        p.onSuccess(v -> new Foo(v));
                    }
                    Foo(String value) { this.value = value; }
                    """))).isEmpty();
        }
    }

    @Nested
    class Siblings {
        @Test
        void flags_allowlistedCallbackWhoseSiblingHasAnEffect() {
            var found = compile(Map.of("org.example.Foo", HEADER + """
                    class Foo {
                        boolean done;
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> done = true)
                             .onFailure(c -> System.out.println(c));
                        }
                    }
                    """), "warning");

            assertThat(found.lines()).hasSize(2);
            assertThat(found.messages()).anyMatch(message -> message.contains("sibling") || message.contains("same chain"));
        }

        @Test
        void flags_siblingAcrossDerivedPromises() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> done = true)
                         .timeout(org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds())
                         .onSuccess(v -> System.out.println(v));
                    }
                    """))).hasSize(2);
        }

        @Test
        void flags_siblingAbove() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(v -> System.out.println(v))
                         .onFailure(_ -> done = true);
                    }
                    """))).hasSize(2);
        }

        @Test
        void flags_resolveOnlyCallbackNextToAnEffect() {
            assertThat(lines(body("""
                    boolean done;
                    Promise<String> other = Promise.promise();
                    void run(Promise<String> p) {
                        p.onFailure(_ -> done = true)
                         .onFailure(other::fail);
                    }
                    """))).hasSize(2);
        }

        @Test
        void clean_siblingsThatAreAllAllowlisted() {
            assertThat(lines(body("""
                    Promise<String> other = Promise.promise();
                    void run(Promise<String> p) {
                        p.onSuccess(v -> System.out.println(v))
                         .onFailure(other::fail);
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_otherChainStepsAreNotSiblings() {
            assertThat(lines(body("""
                    void run(Promise<String> p) {
                        p.onSuccess(v -> System.out.println(v))
                         .timeout(org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds())
                         .map(String::trim)
                         .onFailure(c -> System.out.println(c));
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_siblingsInSeparateStatements() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        // detached: one flag, nothing reads it
                        p.onSuccess(_ -> done = true);
                        p.onFailure(c -> System.out.println(c));
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_oneJustificationCoversTheChain() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        // detached: the flag is only read on the next tick
                        p.onSuccess(_ -> done = true)
                         .onFailure(c -> System.out.println(c));
                    }
                    """))).isEmpty();
        }
    }

    @Nested
    class Suppression {
        @Test
        void clean_detachedCommentAboveAStatementWhoseCallIsOnALaterLine() {
            assertThat(lines(body("""
                    boolean done;
                    void register(String name, Promise<String> p) { }
                    void run(Promise<String> p) {
                        // detached: registration is advisory
                        register("x",
                                 p.onSuccess(_ -> done = true));
                    }
                    """))).isEmpty();
        }

        @Test
        void flags_detachedCommentWithEmptyReason() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        // detached:
                        p.onSuccess(_ -> done = true);
                    }
                    """))).hasSize(1);
        }

        @Test
        void clean_suppressWarningsAll() {
            assertThat(lines(body("""
                    boolean done;
                    @SuppressWarnings("all")
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> done = true);
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_suppressWarningsOnALocalVariable() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        @SuppressWarnings("JBCT-ASYNC-01")
                        var chained = p.onSuccess(_ -> done = true);
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_detachedCommentWithReasonAboveTheStatement() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        // detached: nothing reads done until the next tick
                        p.onSuccess(_ -> done = true);
                    }
                    """))).isEmpty();
        }

        @Test
        void clean_detachedCommentInsideTheStatement() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        p.map(String::trim)
                         // detached: health heuristic, a millisecond of lag is benign
                         .onSuccess(_ -> done = true);
                    }
                    """))).isEmpty();
        }

        @Test
        void flags_detachedCommentWithoutReason() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        // detached
                        p.onSuccess(_ -> done = true);
                    }
                    """))).hasSize(1);
        }

        @Test
        void flags_detachedCommentOfAnEarlierStatement() {
            assertThat(lines(body("""
                    boolean done;
                    void run(Promise<String> p) {
                        // detached: first one only
                        p.onFailure(_ -> System.out.println("x"));

                        p.onSuccess(_ -> done = true);
                    }
                    """))).hasSize(1);
        }

        @Test
        void clean_suppressWarningsOnTheMethodClassOrField() {
            assertThat(lines(body("""
                    boolean done;
                    @SuppressWarnings("JBCT-ASYNC-01")
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> done = true);
                    }
                    """))).isEmpty();
            assertThat(lines(HEADER + """
                    @SuppressWarnings("JBCT-ASYNC-01")
                    class Foo {
                        boolean done;
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> done = true);
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        void flags_contractMethod() {
            assertThat(lines(body("""
                    boolean done;
                    @Contract
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> done = true);
                    }
                    """))).hasSize(1);
        }
    }

    @Nested
    class Severity {
        private static final String SOURCE = HEADER + """
                class Foo {
                    boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> done = true);
                    }
                }
                """;

        @Test
        void warning_isTheDefault() {
            var found = compile(Map.of("org.example.Foo", SOURCE), null);

            assertThat(found.kinds()).containsExactly(Diagnostic.Kind.WARNING);
        }

        @Test
        void error_whenRequested() {
            var found = compile(Map.of("org.example.Foo", SOURCE), "error");

            assertThat(found.kinds()).containsExactly(Diagnostic.Kind.ERROR);
        }

        @Test
        void message_namesTheWriteTheFixAndTheEscapeHatch() {
            var message = compile(Map.of("org.example.Foo", SOURCE), "warning").messages().getFirst();

            assertThat(message).contains("JBCT-ASYNC-01").contains("assigns done")
                               .contains("withSuccess/withFailure/withResult").contains("// detached: <why it may run late>");
        }
    }

    private static String body(String members) {
        return HEADER + "class Foo {\n" + members + "}\n";
    }

    private static List<Integer> lines(String source) {
        return compile(Map.of("org.example.Foo", source), "warning").lines();
    }

    private record Found(List<Integer> lines, List<String> messages, List<Diagnostic.Kind> kinds) {}

    private static Found compile(Map<String, String> sources, String severity) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var files = sources.entrySet().stream().map(entry -> (JavaFileObject) new Source(entry.getKey(), entry.getValue())).toList();
        var options = new ArrayList<>(List.of("-proc:full", "-classpath", System.getProperty("java.class.path"), "-d", tempDir()));

        if (severity != null) {
            options.add("-Ajbct.async.severity=" + severity);
        }

        var task = compiler.getTask(null, null, diagnostics, options, null, files);

        task.setProcessors(List.of(new AsyncCallbackProcessor()));
        task.call();

        var mine = diagnostics.getDiagnostics().stream()
                              .filter(d -> d.getMessage(null).startsWith("JBCT-ASYNC-01"))
                              .toList();
        var errors = diagnostics.getDiagnostics().stream()
                                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR && !d.getMessage(null).startsWith("JBCT-ASYNC-01"))
                                .map(d -> d.getMessage(null))
                                .collect(Collectors.toList());

        assertThat(errors).as("the fixture itself must compile").isEmpty();
        return new Found(mine.stream().map(d -> (int) d.getLineNumber()).toList(),
                         mine.stream().map(d -> d.getMessage(null)).toList(),
                         mine.stream().map(Diagnostic::getKind).toList());
    }

    private static String tempDir() {
        try {
            return Files.createTempDirectory("async-check").toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
