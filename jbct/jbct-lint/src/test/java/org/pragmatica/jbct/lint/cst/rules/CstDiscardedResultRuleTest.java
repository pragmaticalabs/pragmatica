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

class CstDiscardedResultRuleTest {
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

    @Test
    void detects_discarded_map_chain() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        repository.save(entity).map(this::enrich);
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void detects_discarded_flatMap_chain() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        fetchUser(id).flatMap(this::validate);
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void detects_discarded_result_factory() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        Result.success(value);
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void detects_discarded_promise_factory() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        Promise.failure(error);
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void detects_discarded_option_factory() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        Option.some(value);
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void detects_discarded_result_method() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        someError.result();
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void no_false_positive_on_returned_chain() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    Object run() {
                        return repository.save(entity).map(this::enrich);
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void no_false_positive_on_assigned_chain() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        var result = repository.save(entity).map(this::enrich);
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void no_false_positive_on_void_method() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        list.add(item);
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }

    @Test
    void detects_discarded_onSuccess() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        fetchData().onSuccess(System.out::println);
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-07")));
    }
}
