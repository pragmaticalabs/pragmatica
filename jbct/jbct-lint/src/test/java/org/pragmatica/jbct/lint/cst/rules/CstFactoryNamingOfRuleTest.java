package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-NAM-01 `of` coverage (#3017): a static `of(...)` is flagged in every type kind, whatever it
/// returns; the pre-existing record check reached only records returning themselves.
class CstFactoryNamingOfRuleTest {
    private static final String RULE_ID = "JBCT-NAM-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void flags_staticOf_onGenericInterfaceReturningBuilder() {
        assertEquals(1,
                     count("""
                           package org.example;
                           public interface Projection<T> {
                               interface Builder<T> {}
                               static <T> Builder<T> of(Topic<T> topic) {
                                   return null;
                               }
                           }
                           """));
    }

    @Test
    void flags_staticOf_onClassReturningSelf() {
        assertEquals(1,
                     count("""
                           package org.example;
                           public final class Halt {
                               public static Halt of(String detail) {
                                   return new Halt();
                               }
                           }
                           """));
    }

    @Test
    void flags_staticOf_onEnum() {
        assertEquals(1,
                     count("""
                           package org.example;
                           public enum RuleKind {
                               A;
                               public static RuleKind of(int id) {
                                   return A;
                               }
                           }
                           """));
    }

    @Test
    void flags_staticOf_onUtilityClassReturningList() {
        assertEquals(1,
                     count("""
                           package org.example;
                           public final class Announcements {
                               public static java.util.List<String> of(int a) {
                                   return java.util.List.of();
                               }
                           }
                           """));
    }

    @Test
    void flags_staticOf_onRecordReturningResult_exactlyOnce() {
        assertEquals(1,
                     count("""
                           package org.example;
                           import org.pragmatica.lang.Result;
                           public record Email(String value) {
                               public static Result<Email> of(String value) {
                                   return Result.success(new Email(value));
                               }
                           }
                           """));
    }

    @Test
    void flags_staticOf_withJavadocMentioningOtherTypeKinds() {
        assertEquals(1,
                     count("""
                           package org.example;
                           /// Wraps a record and an enum for a class of callers.
                           public final class Wrapper {
                               public static Wrapper of(String detail) {
                                   return new Wrapper();
                               }
                           }
                           """));
    }

    @Test
    void flags_nestedStaticOf_againstNestedTypeName() {
        var diagnostics = lint("""
                               package org.example;
                               public final class Outer {
                                   record Inner(int x) {
                                       static Inner of(int x) {
                                           return new Inner(x);
                                       }
                                   }
                               }
                               """);

        assertEquals(1,
                     diagnostics.stream()
                                .filter(d -> d.ruleId().equals(RULE_ID))
                                .filter(d -> d.message().contains("'inner'"))
                                .count());
    }

    @Test
    void allows_typeNamedFactory() {
        assertEquals(0,
                     count("""
                           package org.example;
                           public final class Halt {
                               public static Halt halt(String detail) {
                                   return new Halt();
                               }
                           }
                           """));
    }

    @Test
    void allows_instanceMethodNamedOf() {
        assertEquals(0,
                     count("""
                           package org.example;
                           public interface Lookup {
                               long of(String stream, int partition);
                           }
                           """));
    }

    @Test
    void allows_callToCollectionOf() {
        assertEquals(0,
                     count("""
                           package org.example;
                           import java.util.List;
                           public final class Names {
                               public static List<String> names() {
                                   return List.of("a");
                               }
                           }
                           """));
    }

    @Test
    void suppressed_bySuppressWarnings() {
        assertEquals(0,
                     count("""
                           package org.example;
                           public final class Halt {
                               @SuppressWarnings("JBCT-NAM-01")
                               public static Halt of(String detail) {
                                   return new Halt();
                               }
                           }
                           """));
    }

    private long count(String source) {
        return lint(source).stream()
                           .filter(d -> d.ruleId().equals(RULE_ID))
                           .count();
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
