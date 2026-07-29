package org.pragmatica.jbct.score;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// Weighting invariants for [ScoreCategory]. The six principle categories must keep
/// summing to 100 so every published score stays comparable; advisory categories are
/// the only ones allowed to carry weight 0.
class ScoreCategoryTest {
    private static double sumOf(List<ScoreCategory> categories) {
        return categories.stream()
                         .mapToDouble(ScoreCategory::weight)
                         .sum();
    }

    @Test
    void weight_principleCategories_sumTo100() {
        assertThat(sumOf(ScoreCategory.weightedCategories())).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void weight_allCategories_sumTo100() {
        assertThat(sumOf(Arrays.asList(ScoreCategory.values()))).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void advisory_style_isTheOnlyZeroWeightCategory() {
        assertThat(ScoreCategory.advisoryCategories()).containsExactly(ScoreCategory.STYLE);
        assertThat(ScoreCategory.STYLE.weight()).isZero();
        assertThat(ScoreCategory.STYLE.weightFraction()).isZero();
    }

    @Test
    void advisory_principleCategories_areNotAdvisory() {
        assertThat(ScoreCategory.weightedCategories()).noneMatch(ScoreCategory::advisory)
                                                      .containsExactly(ScoreCategory.RETURN_TYPES,
                                                                       ScoreCategory.NULL_SAFETY,
                                                                       ScoreCategory.EXCEPTION_HYGIENE,
                                                                       ScoreCategory.PATTERN_PURITY,
                                                                       ScoreCategory.FACTORY_METHODS,
                                                                       ScoreCategory.LAMBDA_COMPLIANCE);
    }

    @Test
    void weightedCategories_andAdvisoryCategories_partitionAllValues() {
        var partition = new ArrayList<>(ScoreCategory.weightedCategories());

        partition.addAll(ScoreCategory.advisoryCategories());
        assertThat(partition).containsExactlyInAnyOrder(ScoreCategory.values());
    }
}
