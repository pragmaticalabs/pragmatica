package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-BND-01 forbidden-boundary-type rule.
class CstBoundaryTypeRuleTest {
    private static final String RULE_ID = "JBCT-BND-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_optional_import() {
        assertTrue(hasRule("""
                package org.example;
                import java.util.Optional;
                class Foo {
                    String run() { return ""; }
                }
                """));
    }

    @Test
    void detects_completableFuture_parameter_type() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    void run(CompletableFuture<String> f) {}
                }
                """));
    }

    @Test
    void detects_nested_optional_type_argument() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Result<Optional<String>> run() { return null; }
                }
                """));
    }

    @Test
    void detects_mono_return_type() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Mono<String> run() { return null; }
                }
                """));
    }

    @Test
    void detects_responseEntity_field_type() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    ResponseEntity<String> value;
                }
                """));
    }

    @Test
    void no_false_positive_on_jbct_types() {
        assertFalse(hasRule("""
                package org.example;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;
                class Foo {
                    Promise<Option<String>> run() { return null; }
                }
                """));
    }

    @Test
    void exempts_excluded_adapter_package() {
        var excluded = CstLinter.cstLinter(LintContext.lintContext(List.of("com.example.adapter")));
        var diagnostics = excluded.lint(SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.adapter;
                import java.util.Optional;
                class Foo {
                    Optional<String> run() { return Optional.empty(); }
                }
                """))
                                  .onFailure(cause -> fail("Parse failed: " + cause.message()))
                                  .or(List.of());

        assertFalse(diagnostics.stream()
                               .anyMatch(diagnostic -> diagnostic.ruleId()
                                                                 .equals(RULE_ID)));
    }

    @Test
    void suppressed_by_suppress_warnings_annotation() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-BND-01")
                    Mono<String> run() { return null; }
                }
                """));
    }

    private boolean hasRule(String source) {
        return lint(source).stream()
                           .anyMatch(diagnostic -> diagnostic.ruleId()
                                                             .equals(RULE_ID));
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
