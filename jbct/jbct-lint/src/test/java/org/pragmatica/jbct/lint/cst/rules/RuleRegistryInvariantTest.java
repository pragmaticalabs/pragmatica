package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.jbct.lint.cst.CstLinter;

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
}
