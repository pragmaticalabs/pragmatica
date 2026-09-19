package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
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
}
