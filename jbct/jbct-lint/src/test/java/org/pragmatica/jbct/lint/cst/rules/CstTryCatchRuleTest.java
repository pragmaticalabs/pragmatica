package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// JBCT-EX-03 (#1247): before this rule no lint rule matched `try` or `catch`, so a green gate said nothing
/// about the construct and `@SuppressWarnings("JBCT-EX-01")` on a catching method was decorative.
class CstTryCatchRuleTest {
    private static final String RULE_ID = "JBCT-EX-03";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    private List<Diagnostic> lint(String source) {
        var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), source);
        return linter.lint(sourceFile)
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }

    private List<Diagnostic> ruleHits(String source) {
        return lint(source).stream()
                           .filter(d -> d.ruleId().equals(RULE_ID))
                           .toList();
    }

    @Test
    void unannotatedTryCatch_isFlagged_onCatchLine() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    Result<String> run() {
                        try {
                            return success(read());
                        } catch (IllegalStateException e) {
                            return FAILED.result();
                        }
                    }
                }
                """);

        assertEquals(1, hits.size(), "one catch clause, one diagnostic: " + hits);
        assertEquals(6, hits.getFirst().line());
        assertTrue(hits.getFirst().message().contains("'run'"), hits.getFirst().message());
    }

    @Test
    void multiCatch_isFlaggedOncePerClause() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    Result<String> run() {
                        try {
                            return success(read());
                        } catch (IllegalStateException e) {
                            return CLOSED.result();
                        } catch (IndexOutOfBoundsException e) {
                            return CORRUPT.result();
                        }
                    }
                }
                """);

        assertEquals(2, hits.size(), hits.toString());
    }

    @Test
    void markedJdkBoundary_isAllowed() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-EX-03")
                    private Result<MemorySegment> allocate(long bytes) {
                        try {
                            return success(arena.allocate(bytes));
                        } catch (OutOfMemoryError _) {
                            return MEMORY_EXCEEDED.result();
                        }
                    }
                }
                """);

        assertTrue(hits.isEmpty(), hits.toString());
    }

    @Test
    void exceptionRuleSuppression_doesNotExemptCatch() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-EX-01")
                    Result<String> run() {
                        try {
                            return success(read());
                        } catch (IndexOutOfBoundsException e) {
                            return CLOSED.result();
                        }
                    }
                }
                """);

        assertEquals(1, hits.size(), "a JBCT-EX-01 suppression is not a JDK-boundary mark: " + hits);
    }

    @Test
    void markOnOneMethod_doesNotCoverItsSibling() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-EX-03")
                    private Result<MemorySegment> allocate(long bytes) {
                        try {
                            return success(arena.allocate(bytes));
                        } catch (OutOfMemoryError _) {
                            return MEMORY_EXCEEDED.result();
                        }
                    }

                    Result<String> run() {
                        try {
                            return success(read());
                        } catch (RuntimeException e) {
                            return FAILED.result();
                        }
                    }
                }
                """);

        assertEquals(1, hits.size(), hits.toString());
        assertEquals(15, hits.getFirst().line());
    }

    @Test
    void tryFinally_withoutCatch_isNotFlagged() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    boolean add(long offset) {
                        lock.lock();
                        try {
                            return payloads.add(offset);
                        } finally {
                            lock.unlock();
                        }
                    }
                }
                """);

        assertTrue(hits.isEmpty(), hits.toString());
    }

    @Test
    void tryWithResources_withoutCatch_isNotFlagged() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-EX-01")
                    void writeSynced(Path path, byte[] bytes) throws IOException {
                        try (var channel = FileChannel.open(path)) {
                            channel.write(ByteBuffer.wrap(bytes));
                        }
                    }
                }
                """);

        assertTrue(hits.isEmpty(), hits.toString());
    }

    @Test
    void testClass_isExempt() {
        var hits = ruleHits("""
                package org.example;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test
                    void reflects() {
                        try {
                            field.get(buffer);
                        } catch (ReflectiveOperationException e) {
                            fail(e);
                        }
                    }
                }
                """);

        assertTrue(hits.isEmpty(), hits.toString());
    }

    /// #1247 review N2: the default severity is WARNING until the corpus burn-down (see `LintConfig`).
    @Test
    void defaultSeverity_isWarning() {
        var hits = ruleHits("""
                package org.example;
                class Foo {
                    void run() {
                        try { go(); } catch (RuntimeException e) { log(e); }
                    }
                }
                """);

        assertEquals(1, hits.size(), hits.toString());
        assertEquals(DiagnosticSeverity.WARNING, hits.getFirst().severity());
    }

    /// #1247 review M1: the mark is method-scoped. Fixtures r09-r15 are the reviewer's (rev1294).
    @Nested
    class MethodScopedMark {

        @Test
        void classLevelMark_doesNotExemptUnmarkedMethods() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    @SuppressWarnings("JBCT-EX-03")
                    class Foo {
                        void business() {
                            try { go(); } catch (RuntimeException e) { log(e); }
                        }
                    }
                    """).size());
        }

        @Test
        void classLevelContract_doesNotExemptUnmarkedMethods() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    @Contract
                    class Foo {
                        void business() {
                            try { go(); } catch (RuntimeException e) { log(e); }
                        }
                    }
                    """).size());
        }

        @Test
        void markedMethod_doesNotCoverAnonymousClassInside() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    class Foo {
                        @SuppressWarnings("JBCT-EX-03")
                        Runnable leaf() {
                            try { go(); } catch (IllegalStateException e) { log(e); }
                            return new Runnable() {
                                public void run() {
                                    try { go(); } catch (RuntimeException e) { log(e); }
                                }
                            };
                        }
                    }
                    """).size());
        }

        @Test
        void markedMethod_doesNotCoverNestedClassMethod() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    class Foo {
                        @SuppressWarnings("JBCT-EX-03")
                        void leaf() {
                            try { go(); } catch (IllegalStateException e) { log(e); }
                            class Local {
                                void run() {
                                    try { go(); } catch (RuntimeException e) { log(e); }
                                }
                            }
                        }
                    }
                    """).size());
        }

        @Test
        void parameterMark_exemptsNothing() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    class Foo {
                        void run(@SuppressWarnings("JBCT-EX-03") int x) { try { go(); } catch (RuntimeException e) { log(e); } }
                    }
                    """).size());
        }

        @Test
        void localVariableMark_exemptsNothing() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    class Foo {
                        void run() {
                            @SuppressWarnings("JBCT-EX-03")
                            Runnable r = () -> {
                                try { go(); } catch (RuntimeException e) { log(e); }
                            };
                            r.run();
                        }
                    }
                    """).size());
        }

        @Test
        void fieldMark_exemptsNothing() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    class Foo {
                        @SuppressWarnings("JBCT-EX-03")
                        private final Runnable r = () -> {
                            try { go(); } catch (RuntimeException e) { log(e); }
                        };
                    }
                    """).size());
        }

        @Test
        void sameLineSibling_isNotCovered() {
            assertEquals(1, ruleHits("""
                    package org.example;
                    class Foo {
                        @SuppressWarnings("JBCT-EX-03")
                        void leaf() { try { go(); } catch (IllegalStateException e) { log(e); } } void business() { try { go(); } catch (RuntimeException e) { log(e); } }
                    }
                    """).size());
        }

        @Test
        void markedMethod_coversLambdaInside() {
            assertTrue(ruleHits("""
                    package org.example;
                    class Foo {
                        @SuppressWarnings("JBCT-EX-03")
                        Runnable leaf() {
                            return () -> {
                                try { go(); } catch (IllegalStateException e) { log(e); }
                            };
                        }
                    }
                    """).isEmpty());
        }

        @Test
        void markedConstructor_isAllowed() {
            assertTrue(ruleHits("""
                    package org.example;
                    class Foo {
                        @SuppressWarnings("JBCT-EX-03")
                        Foo() {
                            try { go(); } catch (IllegalStateException e) { log(e); }
                        }
                    }
                    """).isEmpty());
        }

        @Test
        void contractMethod_isAllowed() {
            assertTrue(ruleHits("""
                    package org.example;
                    class Foo {
                        @Contract
                        void run() {
                            try { go(); } catch (RuntimeException e) { log(e); }
                        }
                    }
                    """).isEmpty());
        }

        @Test
        void markedInterfaceDefaultMethod_isAllowed() {
            assertTrue(ruleHits("""
                    package org.example;
                    interface Foo {
                        @SuppressWarnings("JBCT-EX-03")
                        default void run() {
                            try { go(); } catch (IllegalStateException e) { log(e); }
                        }
                    }
                    """).isEmpty());
        }
    }
}
