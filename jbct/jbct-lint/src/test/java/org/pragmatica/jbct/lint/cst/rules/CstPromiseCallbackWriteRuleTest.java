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
            for (var call : List.of("seen.add(v)",
                                    "seen.remove(v)",
                                    "seen.clear()",
                                    "seen.addAll(List.of(v))")) {
                assertTrue(flags("""
                        package org.example;
                        class Foo {
                            private final Set<String> seen = new HashSet<>();
                            void run(Promise<String> p) {
                                p.onSuccess(v -> %s);
                            }
                        }
                        """.formatted(call)),
                           call);
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
                        """.formatted(call)),
                           call);
            }
        }

        @Test
        void flags_everyCallbackForm() {
            for (var form : List.of("onSuccess",
                                    "onFailure",
                                    "onResult",
                                    "onSuccessRun",
                                    "onFailureRun",
                                    "onResultRun",
                                    "onSuccessAsync",
                                    "onFailureAsync",
                                    "onResultAsync",
                                    "onSuccessRunAsync",
                                    "onFailureRunAsync",
                                    "onResultRunAsync")) {
                assertTrue(flags("""
                        package org.example;
                        class Foo {
                            private boolean done;
                            void run(Promise<String> p) {
                                p.%s(%s);
                            }
                        }
                        """.formatted(form, argumentFor(form))),
                           form);
            }
        }

        @Test
        void flags_prefixIncrement() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private int failures;
                        void run(Promise<Integer> p) {
                            p.onFailure(_ -> ++failures);
                        }
                    }
                    """));
        }

        @Test
        void message_namesTheUpdatedField_forPrefixIncrement() {
            var found = lint("""
                    package org.example;
                    class Foo {
                        private int failures;
                        void run(Promise<Integer> p) {
                            p.onFailure(_ -> ++failures);
                        }
                    }
                    """);

            assertEquals(1, found.size());
            assertTrue(found.getFirst().message().contains("updates failures"),
                       found.getFirst().message());
        }

        @Test
        void flags_thisQualifiedAssignment_evenWhenAParameterHasTheSameName() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private String last;
                        void run(Promise<String> p) {
                            p.onSuccess(last -> this.last = last);
                        }
                    }
                    """));
        }

        @Test
        void flags_assignmentAfterReturnInsideBlock() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> p) {
                            p.onResult(_ -> {
                                if (done) {
                                    return;
                                }
                                done = true;
                            });
                        }
                    }
                    """));
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
        void diagnostic_reportsCallbackLine_forMultiLineChain() {
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
            assertEquals(7,
                         found.getFirst().line());
            assertEquals(12,
                         found.getFirst().column());
            assertEquals(DiagnosticSeverity.WARNING,
                         found.getFirst().severity());
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
        void flags_writeThreeMethodsDeep() {
            assertTrue(flags(chainOfMethods(3)));
        }

        @Test
        void clean_writeFourMethodsDeep() {
            assertFalse(flags(chainOfMethods(4)));
        }

        @Test
        void message_namesCallPath_forDelegatedWrite() {
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
            assertTrue(found.getFirst().message().contains("via outer"),
                       found.getFirst().message());
        }

        @Test
        void flags_whenCallBindsSharedStateToTheDelegatesParameter() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private final AtomicInteger remaining = new AtomicInteger(2);
                        void run(Promise<String> p) {
                            p.onResultRun(() -> release(remaining));
                        }
                        private static void release(AtomicInteger counter) {
                            counter.decrementAndGet();
                        }
                    }
                    """));
        }

        @Test
        void clean_whenCallBindsOnlyTheCallbacksOwnValueToTheDelegatesParameter() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<List<String>> p) {
                            p.onSuccess(values -> fill(values));
                        }
                        private static void fill(List<String> target) {
                            target.add("x");
                        }
                    }
                    """));
        }

        @Test
        void clean_whenCallPassesAFreshObjectToTheDelegate() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<String> p) {
                            p.onSuccess(_ -> fill(new ArrayList<String>(), true, null));
                        }
                        private static void fill(List<String> target, boolean flag, Object extra) {
                            target.add("x");
                        }
                    }
                    """));
        }

        @Test
        void clean_whenCallbackConstructsAnObjectWhoseConstructorAssignsFields() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private String value;
                        void run(Promise<String> p) {
                            p.onSuccess(v -> new Foo(v));
                        }
                        public Foo(String value) {
                            this.value = value;
                        }
                    }
                    """));
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
        void flags_supplierOfPromiseRead() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        private Supplier<Promise<String>> operation;
                        void run() {
                            operation.get().onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_mapOfPromisesRead() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        private final Map<String, Promise<String>> inFlight = new ConcurrentHashMap<>();
                        void run(String id) {
                            inFlight.computeIfAbsent(id, this::load).onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_recordComponentAccessor() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        record Attempt(Promise<String> promise) {}
                        void run(Attempt attempt) {
                            attempt.promise().onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_parameterOfPromiseFactoryLambda() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> run() {
                            return Promise.promise(promise -> promise.onSuccess(_ -> done = true));
                        }
                    }
                    """));
        }

        @Test
        void flags_qualifiedCallToMethodOfTheFileDeclaredToReturnPromise() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> load() { return null; }
                        void run(Foo other) {
                            other.load().onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_nameDeclaredDifferentlyInAnotherMethod() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void first(Promise<String> value) {
                            log.info("{}", value);
                        }
                        void second() {
                            var value = repository.find();
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_nameDeclaredPromiseInTheSameMethod() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void first() {
                            var value = repository.find();
                            log.info("{}", value);
                        }
                        void second(Promise<String> value) {
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_staticCallOnAnotherType() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<Unit> delete(Path path) { return null; }
                        void run(Path path) {
                            FileOps.delete(path).onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_commonNameDeclaredPromiseInTheFile() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> get() { return null; }
                        void run(Cache cache) {
                            cache.get().onSuccess(_ -> done = true);
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
        void clean_resultVariableInFileThatAlsoHoldsPromises() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> load() { return null; }
                        void run(Result<String> parsed) {
                            parsed.onSuccess(_ -> done = true);
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
        void clean_unknownReceiverInPromiseMethodWithoutReturn() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> run() {
                            repository.find().onSuccess(_ -> done = true);
                            return other();
                        }
                    }
                    """));
        }

        @Test
        void clean_unknownReceiverWhenTheChainContinuesAfterTheCallback() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> run() {
                            return repository.find().onSuccess(_ -> done = true).map(String::trim);
                        }
                    }
                    """));
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
    class DeclarationScope {
        @Test
        void flags_parameterEvenWhenALaterLambdaParameterReusesTheName() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> value, List<String> items) {
                            value.onSuccess(_ -> done = true);
                            items.forEach(value -> log.info(value));
                        }
                    }
                    """));
        }

        @Test
        void flags_localPromiseDeclaredAfterALambdaParameterOfTheSameName() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(List<String> items) {
                            items.forEach(value -> log.info(value));
                            Promise<String> value = load();
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_varChainedThroughTwoLocals() {
            assertTrue(flags(chainIn("""
                    var first = Promise.success(1);
                            var second = first;
                            second.onSuccess(_ -> done = true);
                    """)));
        }

        @Test
        void flags_outerParameterDespiteInnerMethodParameterOfTheSameName() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> value) {
                            record Local() {
                                void use(String value) {}
                            }
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_fieldNameDeclaredWithDifferentTypesInTwoClasses() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        class A {
                            Promise<String> value;
                        }
                        class B {
                            String value;
                        }
                        void run() {
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_recordComponentIsNotInScopeOutsideItsRecord() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        record Attempt(Promise<String> value) {}
                        void run() {
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_recordComponentInsideItsRecordBody() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        record Attempt(Promise<String> value) {
                            void watch() {
                                value.onSuccess(_ -> done = true);
                            }
                        }
                    }
                    """));
        }

        @Test
        void flags_fieldWhenAnAbstractMethodParameterReusesTheName() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        private Promise<String> value;
                        interface Loader {
                            Promise<String> load(String value);
                        }
                        void run() {
                            value.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_functionReturningPromise() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        private Function<String, Promise<String>> loader;
                        void run() {
                            loader.apply("id").onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_qualifiedCallToANameDeclaredWithOtherReturnTypesToo() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Promise<String> load() { return null; }
                        String load(int index) { return null; }
                        void run(Foo other) {
                            other.load().onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_returnInsideAnonymousClassMethodThatReturnsPromise() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run() {
                            var holder = new Object() {
                                Promise<String> inner() {
                                    return repository.find().onSuccess(_ -> done = true);
                                }
                            };
                        }
                    }
                    """));
        }

        @Test
        void flags_promiseNameSurvivesComparisonsThatLookLikeGenerics() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> flag, int limit, int max, int count) {
                            if (limit < max && count > flag) {
                                log.info("x");
                            }
                            flag.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_varWhoseInitializerHoldsABlockLambda() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        private Supplier<Promise<String>> operation;
                        void run() {
                            var chained = operation.get().flatMap(v -> {
                                var twice = v + v;
                                return Promise.success(twice);
                            });
                            chained.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }
    }

    @Nested
    class ChainBoundaries {
        @Test
        void flags_callbackAsAnArgumentAfterAnotherArgument() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> pending) {
                            register("name", pending.onSuccess(_ -> done = true));
                        }
                    }
                    """));
        }

        @Test
        void flags_callbackOnTheRightOfAnAssignment() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> pending) {
                            var chained = pending.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_callbackInATernaryBranch() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        Object run(boolean go, Promise<String> pending) {
                            return go ? pending.onSuccess(_ -> done = true) : null;
                        }
                    }
                    """));
        }

        @Test
        void flags_callbackAfterABlockStatement() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(boolean go, Promise<String> pending) {
                            if (go) {
                                log.info("x");
                            }
                            pending.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_callbackInAnElseBranchWithoutBraces() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(boolean go, Promise<String> pending) {
                            if (go) {
                                log.info("x");
                            } else pending.onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void flags_callbackAfterAGenericCall() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> pending) {
                            pending.<String> map(String::trim).onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void clean_callbackAfterAFieldAccessOnAPromise() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(Promise<String> pending) {
                            pending.inner.onSuccess(_ -> done = true);
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
        void clean_prefixIncrementOfALocal() {
            assertFalse(flags("""
                    package org.example;
                    class Foo {
                        void run(Promise<String> p) {
                            p.onSuccess(v -> {
                                int n = 0;
                                ++n;
                                log.info("{}", n);
                            });
                        }
                    }
                    """));
        }

        @Test
        void clean_everyKindOfLocalDeclaredInTheCallback() {
            for (var local : List.of("var out = new ArrayList<String>(); out.add(v);",
                                     "List<String> out = new ArrayList<>(); out.add(v);",
                                     "Outer.Inner out = new Outer.Inner(); out.put(v, v);",
                                     "final StringBuilder out = new StringBuilder(); out.append(v);",
                                     "int[] out = new int[1]; out[0] = 1;",
                                     "for (var item : items) { item.add(v); }",
                                     "items.forEach(item -> item.add(v));",
                                     "items.forEach((a, b) -> a.add(b));",
                                     "try { run(); } catch (RuntimeException failure) { failure.add(v); }",
                                     "if (v instanceof Holder holder) { holder.set(v); }",
                                     "switch (v) { case Holder holder -> holder.set(v); default -> { } }")) {
                assertFalse(flags("""
                        package org.example;
                        class Foo {
                            void run(Promise<String> p, List<List<String>> items) {
                                p.onSuccess(v -> { %s });
                            }
                        }
                        """.formatted(local)),
                            local);
            }
        }

        @Test
        void clean_mutationsThatTouchNothingShared() {
            assertFalse(flags(chainIn("Promise.success(1).onSuccess(v -> MDC.put(\"id\", \"x\"));")));
            assertFalse(flags(chainIn("Promise.success(1).onSuccess(v -> log.info(String.format(\"%s\", v).replace(\"a\", \"b\")));")));
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

    @Nested
    class Scope {
        private static final String WRITER = """
                package org.example;
                class Foo {
                    private boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> done = true);
                    }
                }
                """;

        @Test
        void clean_inExcludedPackage() {
            var excluding = CstLinter.cstLinter(LintContext.lintContext(List.of("org.example.**")));

            assertTrue(flags(WRITER));
            assertFalse(excluding.lint(SourceFile.sourceFile(Path.of("Test.java"),
                                                             WRITER))
                                 .or(List.of())
                                 .stream()
                                 .anyMatch(diagnostic -> diagnostic.ruleId()
                                                                   .equals(RULE_ID)));
        }

        @Test
        void clean_inTestClass() {
            assertFalse(flags("""
                    package org.example;
                    class FooTest {
                        private boolean done;
                        @Test
                        void run() {
                            Promise.success(1).onSuccess(_ -> done = true);
                        }
                    }
                    """));
        }

        @Test
        void message_namesTheFixAndTheEscapeHatch() {
            var found = lint(WRITER).getFirst();

            assertTrue(found.message().contains("assigns done"),
                       found.message());
            assertTrue(found.message().contains("withSuccess/withFailure/withResult"),
                       found.message());
            assertTrue(found.message().contains("// detached: <reason>"),
                       found.message());
            assertTrue(found.details().contains("separate virtual thread"),
                       found.details());
        }

        @Test
        void flags_chainsInsideIfAndElseBranches() {
            assertTrue(flags("""
                    package org.example;
                    class Foo {
                        private boolean done;
                        void run(boolean go, Promise<String> p) {
                            if (go) {
                                p.map(String::trim).onSuccess(_ -> done = true);
                            } else p.onFailure(_ -> done = false);
                        }
                    }
                    """));
        }
    }

    /// A callback that calls m1, which calls m2, ... and only the last of `depth` methods writes a field.
    private static String chainOfMethods(int depth) {
        var methods = new StringBuilder();

        for (var i = 1; i < depth; i++) {
            methods.append("private void m%d() { m%d(); }%n".formatted(i, i + 1));
        }

        methods.append("private void m%d() { done = true; }%n".formatted(depth));

        return """
                package org.example;
                class Foo {
                    private boolean done;
                    void run(Promise<String> p) {
                        p.onSuccess(_ -> m1());
                    }
                    %s
                }
                """.formatted(methods);
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
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"),
                                                 source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of())
                     .stream()
                     .filter(diagnostic -> diagnostic.ruleId()
                                                     .equals(RULE_ID))
                     .toList();
    }
}
