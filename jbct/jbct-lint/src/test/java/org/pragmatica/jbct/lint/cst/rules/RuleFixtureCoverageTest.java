package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.lint.cst.rules.RuleFixtures.RuleFixture;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Shared parameterized coverage harness for every rule in `CstLinter.defaultRules()` (#454).
///
/// Drives the [RuleFixtures] catalog: for each rule the POSITIVE snippet must emit the
/// diagnostic on the expected line (rule ID AND line asserted — no vacuous "some diagnostic
/// exists" checks), and the NEGATIVE snippet must stay clean of that rule. Adding a future
/// rule's coverage costs one [RuleFixtures] row, not a new test class.
class RuleFixtureCoverageTest {
    private static CstLinter linter;

    @BeforeAll
    static void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    static List<RuleFixture> fixtures() {
        return RuleFixtures.all();
    }

    @ParameterizedTest(name = "{0} positive emits on expected line")
    @MethodSource("fixtures")
    void positiveFixture_emitsDiagnostic_onExpectedLine(RuleFixture fixture) {
        var matching = diagnosticsFor(fixture.ruleId(), fixture.positiveSource());

        assertFalse(matching.isEmpty(),
                    () -> fixture.ruleId() + " positive fixture emitted no diagnostic. Diagnostics: "
                          + ruleIds(fixture.positiveSource()));
        assertTrue(matching.stream()
                           .anyMatch(diagnostic -> diagnostic.line() == fixture.positiveLine()),
                   () -> fixture.ruleId() + " expected on line " + fixture.positiveLine() + " but was on "
                         + matching.stream()
                                   .map(Diagnostic::line)
                                   .toList());
    }

    @ParameterizedTest(name = "{0} negative stays clean")
    @MethodSource("fixtures")
    void negativeFixture_staysClean(RuleFixture fixture) {
        var matching = diagnosticsFor(fixture.ruleId(), fixture.negativeSource());

        assertTrue(matching.isEmpty(),
                   () -> fixture.ruleId() + " negative fixture unexpectedly triggered on line(s) "
                         + matching.stream()
                                   .map(Diagnostic::line)
                                   .toList());
    }

    private List<Diagnostic> diagnosticsFor(String ruleId, String source) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId()
                                                           .equals(ruleId))
                           .toList();
    }

    private List<String> ruleIds(String source) {
        return lint(source).stream()
                           .map(Diagnostic::ruleId)
                           .toList();
    }

    private List<Diagnostic> lint(String source) {
        var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), source);

        return linter.lint(sourceFile)
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
