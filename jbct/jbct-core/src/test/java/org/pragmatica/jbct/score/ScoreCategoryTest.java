package org.pragmatica.jbct.score;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Reporting invariants for [ScoreCategory]. Categories carry no weights — density is a plain
/// count per KLOC — so the only thing a category declares beyond its name is whether it is
/// advisory, and that flag has to partition the enum cleanly or the total silently gains or loses
/// a bucket.
class ScoreCategoryTest {
    @Test
    void advisory_style_isTheOnlyAdvisoryCategory() {
        assertThat(ScoreCategory.advisoryCategories()).containsExactly(ScoreCategory.STYLE);
        assertThat(ScoreCategory.STYLE.advisory()).isTrue();
    }

    @Test
    void advisory_principleCategories_areNotAdvisory() {
        assertThat(ScoreCategory.countedCategories()).noneMatch(ScoreCategory::advisory)
                                                     .containsExactly(ScoreCategory.RETURN_TYPES,
                                                                      ScoreCategory.NULL_SAFETY,
                                                                      ScoreCategory.EXCEPTION_HYGIENE,
                                                                      ScoreCategory.PATTERN_PURITY,
                                                                      ScoreCategory.FACTORY_METHODS,
                                                                      ScoreCategory.LAMBDA_COMPLIANCE,
                                                                      ScoreCategory.CAUSE);
    }

    @Test
    void countedCategories_andAdvisoryCategories_partitionAllValues() {
        var partition = new ArrayList<>(ScoreCategory.countedCategories());

        partition.addAll(ScoreCategory.advisoryCategories());
        assertThat(partition).containsExactlyInAnyOrder(ScoreCategory.values());
    }
}
