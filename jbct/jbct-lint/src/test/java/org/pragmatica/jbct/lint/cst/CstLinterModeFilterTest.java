package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The mode filter of the rule registry (#2525): which rules each mode runs, and what the linter reports for it.
///
/// `format-only` and `disabled` are refused by the config parser until the entry points honour them, so the filter
/// is pinned here at the linter level, where it must answer for them regardless.
class CstLinterModeFilterTest {
    private static final String IO_IN_DOMAIN = """
        package com.example.domain.user;
        import java.io.File;
        public class UserData {
            File file;
        }
        """;
    private static final String NULL_RETURN = """
        package org.example;
        class Foo {
            String run() {
                return null;
            }
        }
        """;

    private static Set<String> ids(List<LintRule> rules) {
        return rules.stream()
                    .map(LintRule::ruleId)
                    .collect(Collectors.toSet());
    }

    private static Set<String> reported(JbctMode mode, String source) {
        var context = LintContext.defaultContext()
                                 .withProject(mode, Option.none());
        var file = SourceFile.sourceFile(Path.of("Test.java"), source);

        return CstLinter.cstLinter(context)
                        .lint(file)
                        .map(list -> list.stream()
                                         .map(Diagnostic::ruleId)
                                         .collect(Collectors.toSet()))
                        .or(Set.of("PARSE-FAILED"));
    }

    @Test
    void rulesFor_business_isTheWholeRegistry() {
        assertEquals(CstLinter.defaultRuleIds(),
                     CstLinter.rulesFor(JbctMode.BUSINESS)
                              .stream()
                              .map(LintRule::ruleId)
                              .toList());
    }

    @Test
    void rulesFor_everyMode_holdsExactlyTheRulesWhoseModesContainIt() {
        var registry = CstLinter.rulesFor(JbctMode.BUSINESS);

        assertFalse(registry.isEmpty());
        for (var mode : JbctMode.values()) {
            var expected = registry.stream()
                                   .filter(rule -> rule.modes().contains(mode))
                                   .map(LintRule::ruleId)
                                   .collect(Collectors.toSet());

            assertEquals(expected, ids(CstLinter.rulesFor(mode)), "mode " + mode);
        }
    }

    @Test
    void rulesFor_infrastructure_omitsStructureRules_anchoredOnArch02() {
        var infrastructure = ids(CstLinter.rulesFor(JbctMode.INFRASTRUCTURE));

        assertFalse(infrastructure.contains("JBCT-ARCH-02"));
        assertTrue(ids(CstLinter.rulesFor(JbctMode.BUSINESS)).contains("JBCT-ARCH-02"));
        assertTrue(infrastructure.contains("JBCT-RET-03"));
    }

    @Test
    void rulesFor_formatOnly_isEmpty() {
        assertTrue(CstLinter.rulesFor(JbctMode.FORMAT_ONLY).isEmpty());
    }

    @Test
    void rulesFor_disabled_isEmpty() {
        assertTrue(CstLinter.rulesFor(JbctMode.DISABLED).isEmpty());
    }

    @Test
    void lint_business_reportsStructureAndCodeRules() {
        assertTrue(reported(JbctMode.BUSINESS, IO_IN_DOMAIN).contains("JBCT-MIX-01"));
        assertTrue(reported(JbctMode.BUSINESS, NULL_RETURN).contains("JBCT-RET-03"));
    }

    @Test
    void lint_infrastructure_dropsStructureRulesKeepsCodeRules() {
        assertFalse(reported(JbctMode.INFRASTRUCTURE, IO_IN_DOMAIN).contains("JBCT-MIX-01"));
        assertTrue(reported(JbctMode.INFRASTRUCTURE, NULL_RETURN).contains("JBCT-RET-03"));
    }

    @Test
    void lint_formatOnlyAndDisabled_reportNothing() {
        assertTrue(reported(JbctMode.FORMAT_ONLY, IO_IN_DOMAIN).isEmpty());
        assertTrue(reported(JbctMode.FORMAT_ONLY, NULL_RETURN).isEmpty());
        assertTrue(reported(JbctMode.DISABLED, IO_IN_DOMAIN).isEmpty());
        assertTrue(reported(JbctMode.DISABLED, NULL_RETURN).isEmpty());
    }
}
