package org.pragmatica.jbct.score;

import java.util.HashSet;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Invariant tests binding [RuleCategoryMapping] to the live rule registry
/// (`CstLinter.defaultRules()`), plus demonstrations that diagnostics land in their
/// mapped categories instead of a default bucket.
///
/// The mapping lives in jbct-core and the registry in jbct-lint; this test lives in
/// jbct-lint because it is the module that can see both.
class RuleCategoryMappingTest {
    private static final List<String> REGISTRY = CstLinter.defaultRuleIds();

    private static Diagnostic diagnostic(String ruleId) {
        return Diagnostic.diagnostic(ruleId, DiagnosticSeverity.ERROR, "Test.java", 1, 1, "test", "test");
    }

    @Nested
    class RegistryMappingBijection {
        @Test
        void defaultRuleIds_registry_hasNoDuplicates() {
            assertEquals(REGISTRY.size(),
                         (int) REGISTRY.stream().distinct().count(),
                         "CstLinter.defaultRules() must not register the same rule ID twice: " + REGISTRY);
        }

        @Test
        void mapping_everyRegisteredRule_isCategorizedOrUncategorized() {
            var unclassified = REGISTRY.stream()
                                       .filter(ruleId -> !RuleCategoryMapping.isKnown(ruleId))
                                       .toList();

            assertTrue(unclassified.isEmpty(),
                       "Registered rules missing from RuleCategoryMapping (add them to MAPPING or UNCATEGORIZED): "
                       + unclassified);
        }

        @Test
        void mapping_everyCategorizedId_isEmittedByRegistry() {
            var orphans = RuleCategoryMapping.mapping()
                                             .keySet()
                                             .stream()
                                             .filter(ruleId -> !REGISTRY.contains(ruleId))
                                             .toList();

            assertTrue(orphans.isEmpty(), "MAPPING contains rule IDs that no rule emits: " + orphans);
        }

        @Test
        void mapping_everyUncategorizedId_isEmittedByRegistry() {
            var orphans = RuleCategoryMapping.uncategorized()
                                             .stream()
                                             .filter(ruleId -> !REGISTRY.contains(ruleId))
                                             .toList();

            assertTrue(orphans.isEmpty(), "UNCATEGORIZED contains rule IDs that no rule emits: " + orphans);
        }

        @Test
        void mapping_categorizedAndUncategorized_areDisjoint() {
            var overlap = new HashSet<>(RuleCategoryMapping.mapping().keySet());

            overlap.retainAll(RuleCategoryMapping.uncategorized());
            assertTrue(overlap.isEmpty(), "Rule IDs appear in both MAPPING and UNCATEGORIZED: " + overlap);
        }

        @Test
        void mapping_partition_coversRegistryExactly() {
            var classified = new HashSet<String>(RuleCategoryMapping.mapping().keySet());

            classified.addAll(RuleCategoryMapping.uncategorized());
            assertEquals(new HashSet<>(REGISTRY), classified, "Mapping partition must equal the live registry exactly");
        }
    }

    @Nested
    class CategoryAssignment {
        @Test
        void categoryFor_returnKindRule_mapsToReturnTypes() {
            assertEquals(Option.some(ScoreCategory.RETURN_TYPES), RuleCategoryMapping.categoryFor("JBCT-RET-01"));
        }

        @Test
        void categoryFor_nullReturnRule_mapsToNullSafety() {
            assertEquals(Option.some(ScoreCategory.NULL_SAFETY), RuleCategoryMapping.categoryFor("JBCT-RET-03"));
        }

        @Test
        void categoryFor_businessExceptionRule_mapsToExceptionHygiene() {
            assertEquals(Option.some(ScoreCategory.EXCEPTION_HYGIENE), RuleCategoryMapping.categoryFor("JBCT-EX-01"));
        }

        @Test
        void categoryFor_zoneRule_isUncategorized() {
            assertEquals(Option.none(), RuleCategoryMapping.categoryFor("JBCT-ZONE-01"));
            assertTrue(RuleCategoryMapping.isKnown("JBCT-ZONE-01"));
        }

        @Test
        void categoryFor_unknownRule_isNoneAndNotKnown() {
            assertEquals(Option.none(), RuleCategoryMapping.categoryFor("JBCT-RETIRED-99"));
            assertFalse(RuleCategoryMapping.isKnown("JBCT-RETIRED-99"));
        }
    }

    @Nested
    class LiveBuckets {
        @Test
        void calculate_exceptionRuleDiagnostic_scoresExceptionHygieneNotPatternPurity() {
            var score = ScoreCalculator.calculate(List.of(diagnostic("JBCT-EX-01")), 1);

            assertThat(score.breakdown().get(ScoreCategory.EXCEPTION_HYGIENE).violations()).isEqualTo(1);
            assertThat(score.breakdown().get(ScoreCategory.PATTERN_PURITY).violations()).isZero();
        }

        @Test
        void calculate_nullReturnDiagnostic_scoresNullSafety() {
            var score = ScoreCalculator.calculate(List.of(diagnostic("JBCT-RET-03")), 1);

            assertThat(score.breakdown().get(ScoreCategory.NULL_SAFETY).violations()).isEqualTo(1);
        }

        @Test
        void calculate_lambdaDiagnostic_scoresLambdaCompliance() {
            var score = ScoreCalculator.calculate(List.of(diagnostic("JBCT-LAM-01")), 1);

            assertThat(score.breakdown().get(ScoreCategory.LAMBDA_COMPLIANCE).violations()).isEqualTo(1);
        }

        @Test
        void calculate_uncategorizedDiagnostic_isExcludedFromAllCategories() {
            var score = ScoreCalculator.calculate(List.of(diagnostic("JBCT-ZONE-01")), 1);
            var totalViolations = score.breakdown()
                                       .values()
                                       .stream()
                                       .mapToInt(categoryScore -> categoryScore.violations())
                                       .sum();

            assertThat(totalViolations).isZero();
        }

        @Test
        void calculate_unknownDiagnostic_isExcludedFromAllCategories() {
            var score = ScoreCalculator.calculate(List.of(diagnostic("JBCT-RETIRED-99")), 1);
            var totalViolations = score.breakdown()
                                       .values()
                                       .stream()
                                       .mapToInt(categoryScore -> categoryScore.violations())
                                       .sum();

            assertThat(totalViolations).isZero();
        }
    }

    @Nested
    class WarnOnce {
        @Test
        void unknownRuleIds_repeatedUnknownId_reportedOnce() {
            var diagnostics = List.of(diagnostic("JBCT-RETIRED-99"),
                                      diagnostic("JBCT-RETIRED-99"),
                                      diagnostic("JBCT-RETIRED-99"));

            assertThat(ScoreCalculator.unknownRuleIds(diagnostics)).containsExactly("JBCT-RETIRED-99");
        }

        @Test
        void unknownRuleIds_multipleUnknownIds_eachReportedOnce() {
            var diagnostics = List.of(diagnostic("JBCT-RETIRED-99"),
                                      diagnostic("JBCT-GONE-01"),
                                      diagnostic("JBCT-RETIRED-99"));

            assertThat(ScoreCalculator.unknownRuleIds(diagnostics)).containsExactlyInAnyOrder("JBCT-RETIRED-99",
                                                                                              "JBCT-GONE-01");
        }

        @Test
        void unknownRuleIds_knownRules_areNotReported() {
            var diagnostics = List.of(diagnostic("JBCT-EX-01"), diagnostic("JBCT-ZONE-01"));

            assertThat(ScoreCalculator.unknownRuleIds(diagnostics)).isEmpty();
        }
    }
}
