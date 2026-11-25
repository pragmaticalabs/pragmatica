package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.lint.cst.LintRule;
import org.pragmatica.jbct.parser.Cursor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Registry invariants binding the live rule registry (`CstLinter.defaultRuleIds()`) to its
/// configuration and its fixture coverage (#454).
///
/// Every rule the linter registers must have (a) a severity entry in [LintConfig#DEFAULT] and
/// (b) at least one fixture in the [RuleFixtures] harness. The third leg — (c) a scoring-category
/// assignment — is owned exhaustively by
/// `org.pragmatica.jbct.score.RuleCategoryMappingTest` (the mapping↔registry bijection); it is
/// referenced here, not re-tested, to avoid duplicating that invariant.
///
/// The modes invariant (#2525): a rule's `modes()` is a subset of {business, infrastructure} and contains business.
class RuleRegistryInvariantTest {
    private static final List<String> REGISTRY = CstLinter.defaultRuleIds();

    private static Set<String> fixtureRuleIds() {
        return RuleFixtures.all()
                           .stream()
                           .map(RuleFixtures.RuleFixture::ruleId)
                           .collect(Collectors.toSet());
    }

    /// Invariant (a): the linter must not register a rule whose severity is undefined in the
    /// default config. (Before #454 this failed on exactly JBCT-RET-06.)
    @Test
    void everyRegisteredRule_hasSeverityEntry() {
        var missing = REGISTRY.stream()
                              .filter(ruleId -> !LintConfig.DEFAULT.ruleSeverities()
                                                                   .containsKey(ruleId))
                              .toList();

        assertTrue(missing.isEmpty(),
                   "Registered rules missing a severity entry in LintConfig.DEFAULT: " + missing);
    }

    /// Invariant (b): every registered rule is exercised by at least one harness fixture.
    @Test
    void everyRegisteredRule_hasFixture() {
        var fixtureIds = fixtureRuleIds();
        var uncovered = REGISTRY.stream()
                                .filter(ruleId -> !fixtureIds.contains(ruleId))
                                .toList();

        assertTrue(uncovered.isEmpty(),
                   "Registered rules with no fixture in RuleFixtures (add a positive+negative pair): "
                   + uncovered);
    }

    /// The harness must not carry fixtures for rules the linter no longer registers.
    @Test
    void everyFixture_targetsLiveRule() {
        var orphans = fixtureRuleIds().stream()
                                      .filter(ruleId -> !REGISTRY.contains(ruleId))
                                      .toList();

        assertTrue(orphans.isEmpty(), "RuleFixtures targets rule IDs no rule registers: " + orphans);
    }

    /// One fixture per rule — no accidental duplicate rows masking a coverage gap.
    @Test
    void fixtureCatalog_hasNoDuplicateRuleIds() {
        var ids = RuleFixtures.all()
                              .stream()
                              .map(RuleFixtures.RuleFixture::ruleId)
                              .toList();

        assertEquals(ids.size(),
                     (int) ids.stream()
                              .distinct()
                              .count(),
                     "RuleFixtures contains duplicate rule IDs: " + ids);
    }

    /// Invariant (d): every rule's modes are a subset of {business, infrastructure} and contain business.
    @Test
    void everyRegisteredRule_modesAreValid() {
        var violations = modeViolations(CstLinter.defaultRules());

        assertTrue(violations.isEmpty(), "Registered rules with invalid modes(): " + violations);
    }

    /// The structure rules and the naming rules that judge business code only; every other rule runs in both modes.
    @Test
    void businessOnlyRules_areTheStructureRules() {
        var businessOnly = CstLinter.defaultRules()
                                    .stream()
                                    .filter(rule -> !rule.modes()
                                                         .contains(JbctMode.INFRASTRUCTURE))
                                    .map(LintRule::ruleId)
                                    .collect(Collectors.toSet());

        assertEquals(Set.of("JBCT-ARCH-01", "JBCT-ARCH-02", "JBCT-ARCH-03", "JBCT-ARCH-04", "JBCT-MIX-01", "JBCT-NAM-06",
                            "JBCT-NAM-07", "JBCT-TREE-01", "JBCT-TREE-02"),
                     businessOnly);
    }

    /// The invariant check itself goes red for a rule declaring `format-only`.
    @Test
    void modeCheck_flagsRuleDeclaringFormatOnly() {
        var rule = new FixtureRule("JBCT-FIXTURE-FORMAT-ONLY", Set.of(JbctMode.BUSINESS, JbctMode.FORMAT_ONLY));

        assertEquals(List.of("JBCT-FIXTURE-FORMAT-ONLY"), modeViolations(List.of(rule)));
    }

    /// The invariant check itself goes red for a rule whose modes lack `business`.
    @Test
    void modeCheck_flagsRuleLackingBusiness() {
        var rule = new FixtureRule("JBCT-FIXTURE-NO-BUSINESS", Set.of(JbctMode.INFRASTRUCTURE));

        assertEquals(List.of("JBCT-FIXTURE-NO-BUSINESS"), modeViolations(List.of(rule)));
    }

    /// The invariant check stays green for the two valid declarations.
    @Test
    void modeCheck_acceptsValidDeclarations() {
        var both = new FixtureRule("JBCT-FIXTURE-BOTH", LintRule.CODE_MODES);
        var businessOnly = new FixtureRule("JBCT-FIXTURE-BUSINESS", LintRule.BUSINESS_ONLY);

        assertTrue(modeViolations(List.of(both, businessOnly)).isEmpty());
    }

    private static List<String> modeViolations(List<? extends LintRule> rules) {
        return rules.stream()
                    .filter(rule -> !validModes(rule.modes()))
                    .map(LintRule::ruleId)
                    .toList();
    }

    private static boolean validModes(Set<JbctMode> modes) {
        return modes.contains(JbctMode.BUSINESS) && LintRule.CODE_MODES.containsAll(modes);
    }

    private record FixtureRule(String ruleId, Set<JbctMode> modes) implements CstLintRule {
        @Override
        public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
            return Stream.empty();
        }
    }
}
