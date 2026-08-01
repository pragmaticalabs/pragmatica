package org.pragmatica.jbct.score;

import java.util.ArrayList;
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
    private static final int ONE_KLOC = 1000;

    private static Diagnostic diagnostic(String ruleId) {
        return Diagnostic.diagnostic(ruleId, DiagnosticSeverity.ERROR, "Test.java", 1, 1, "test", "test");
    }

    /// Measure the diagnostics against a fixed one-KLOC denominator, so every density in this
    /// suite is simply the violation count.
    private static ScoreResult calculate(List<Diagnostic> diagnostics) {
        return ScoreCalculator.calculate(new SourceScan(diagnostics, ONE_KLOC, 1));
    }

    private static List<String> registryIdsIn(ScoreCategory category) {
        return REGISTRY.stream()
                       .filter(ruleId -> RuleCategoryMapping.categoryFor(ruleId).equals(Option.some(category)))
                       .toList();
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
        void mapping_everyRegisteredRule_isCategorized() {
            var unclassified = REGISTRY.stream()
                                       .filter(ruleId -> !RuleCategoryMapping.isKnown(ruleId))
                                       .toList();

            assertTrue(unclassified.isEmpty(),
                       "Registered rules missing from RuleCategoryMapping (add them to MAPPING — "
                       + "ScoreCategory.STYLE is the home for advisory style/log/ordering/zone rules): "
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
        void mapping_partition_coversRegistryExactly() {
            assertEquals(new HashSet<>(REGISTRY),
                         new HashSet<>(RuleCategoryMapping.mapping().keySet()),
                         "Every registered rule must have exactly one score category, and the mapping "
                         + "must contain nothing else");
        }

        @Test
        void mapping_styleBucket_holdsExactlyTheAdvisoryRules() {
            assertThat(registryIdsIn(ScoreCategory.STYLE)).containsExactlyInAnyOrder("JBCT-STY-03",
                                                                                     "JBCT-STY-04",
                                                                                     "JBCT-STY-06",
                                                                                     "JBCT-STY-07",
                                                                                     "JBCT-STY-08",
                                                                                     "JBCT-STY-09",
                                                                                     "JBCT-ORD-01",
                                                                                     "JBCT-STATIC-01",
                                                                                     "JBCT-LOG-01",
                                                                                     "JBCT-LOG-02",
                                                                                     "JBCT-NAM-05",
                                                                                     "JBCT-ZONE-01",
                                                                                     "JBCT-ZONE-02",
                                                                                     "JBCT-ZONE-03");
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
        void categoryFor_zoneRule_mapsToStyle() {
            assertEquals(Option.some(ScoreCategory.STYLE), RuleCategoryMapping.categoryFor("JBCT-ZONE-01"));
            assertTrue(RuleCategoryMapping.isKnown("JBCT-ZONE-01"));
        }

        @Test
        void categoryFor_staticImportRule_mapsToStyle() {
            assertEquals(Option.some(ScoreCategory.STYLE), RuleCategoryMapping.categoryFor("JBCT-STATIC-01"));
        }

        @Test
        void categoryFor_memberOrderingRule_mapsToStyle() {
            assertEquals(Option.some(ScoreCategory.STYLE), RuleCategoryMapping.categoryFor("JBCT-ORD-01"));
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
            var score = calculate(List.of(diagnostic("JBCT-EX-01")));

            assertThat(score.breakdown().get(ScoreCategory.EXCEPTION_HYGIENE).violations()).isEqualTo(1);
            assertThat(score.breakdown().get(ScoreCategory.PATTERN_PURITY).violations()).isZero();
        }

        @Test
        void calculate_nullReturnDiagnostic_scoresNullSafety() {
            var score = calculate(List.of(diagnostic("JBCT-RET-03")));

            assertThat(score.breakdown().get(ScoreCategory.NULL_SAFETY).violations()).isEqualTo(1);
        }

        @Test
        void calculate_lambdaDiagnostic_scoresLambdaCompliance() {
            var score = calculate(List.of(diagnostic("JBCT-LAM-01")));

            assertThat(score.breakdown().get(ScoreCategory.LAMBDA_COMPLIANCE).violations()).isEqualTo(1);
        }

        @Test
        void calculate_styleDiagnostic_scoresStyleAndNoPrincipleCategory() {
            var score = calculate(List.of(diagnostic("JBCT-ZONE-01")));
            var principleViolations = ScoreCategory.countedCategories()
                                                   .stream()
                                                   .mapToInt(category -> score.breakdown()
                                                                              .get(category)
                                                                              .violations())
                                                   .sum();

            assertThat(score.breakdown().get(ScoreCategory.STYLE).violations()).isEqualTo(1);
            assertThat(principleViolations).isZero();
        }

        @Test
        void calculate_unknownDiagnostic_isExcludedFromAllCategories() {
            var score = calculate(List.of(diagnostic("JBCT-RETIRED-99")));
            var totalViolations = score.breakdown()
                                       .values()
                                       .stream()
                                       .mapToInt(categoryScore -> categoryScore.violations())
                                       .sum();

            assertThat(totalViolations).isZero();
        }
    }

    @Nested
    class AdvisoryNeutrality {
        @Test
        void calculate_everyLiveStyleRule_leavesTheTotalUnchanged() {
            var styleDiagnostics = registryIdsIn(ScoreCategory.STYLE).stream()
                                                                     .map(RuleCategoryMappingTest::diagnostic)
                                                                     .toList();
            var clean = calculate(List.of());
            var styleOnly = calculate(styleDiagnostics);

            assertThat(styleDiagnostics).isNotEmpty();
            assertThat(styleOnly.totalDensityPerKloc()).isEqualTo(clean.totalDensityPerKloc());
        }

        @Test
        void calculate_styleDiagnosticsAddedToPrincipleFindings_leaveTheTotalUnchanged() {
            var principle = List.of(diagnostic("JBCT-EX-01"), diagnostic("JBCT-RET-03"), diagnostic("JBCT-LAM-01"));
            var mixed = new ArrayList<>(principle);

            registryIdsIn(ScoreCategory.STYLE).forEach(ruleId -> mixed.add(diagnostic(ruleId)));
            var principleTotal = calculate(principle).totalDensityPerKloc();
            var mixedTotal = calculate(mixed).totalDensityPerKloc();

            assertThat(mixedTotal).isEqualTo(principleTotal);
        }

        @Test
        void calculate_styleDiagnostics_areStillCountedInTheirOwnCategory() {
            var styleDiagnostics = registryIdsIn(ScoreCategory.STYLE).stream()
                                                                     .map(RuleCategoryMappingTest::diagnostic)
                                                                     .toList();
            var score = calculate(styleDiagnostics);

            assertThat(score.breakdown().get(ScoreCategory.STYLE).violations()).isEqualTo(styleDiagnostics.size());
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

        @Test
        void unknownRuleIds_styleAndUnknownMixed_reportsOnlyUnknown() {
            var diagnostics = List.of(diagnostic("JBCT-STATIC-01"),
                                      diagnostic("JBCT-RETIRED-99"),
                                      diagnostic("JBCT-ORD-01"));

            assertThat(ScoreCalculator.unknownRuleIds(diagnostics)).containsExactly("JBCT-RETIRED-99");
        }
    }
}
