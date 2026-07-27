package org.pragmatica.jbct.score;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Direction gate for [DensityGate]. The threshold this replaced meant "fail below"; density
/// means "fail above". Both surfaces read the comparison from here precisely so the inversion is
/// stated once, and the removal message has to name the inversion — a copied-forward CI snippet
/// that kept the old number would otherwise assert the opposite of what it says.
class DensityGateTest {
    @Test
    void exceeds_densityAboveThreshold_breaches() {
        assertThat(DensityGate.exceeds(2.4, 2.3)).isTrue();
        assertThat(DensityGate.exceeds(100.0, 0.0)).isTrue();
    }

    @Test
    void exceeds_densityAtOrBelowThreshold_passes() {
        assertThat(DensityGate.exceeds(2.3, 2.3)).isFalse();
        assertThat(DensityGate.exceeds(0.0, 2.3)).isFalse();
    }

    @Test
    void breachMessage_bothDensities_areNamed() {
        assertThat(DensityGate.breachMessage(2.4, 2.3)).isEqualTo("Density 2.4/KLOC exceeds maximum 2.3/KLOC");
    }

    @Test
    void removedBaselineMessage_namesTheReplacementAndTheInversion() {
        assertThat(DensityGate.REMOVED_BASELINE_MESSAGE).contains("jbct.score.baseline")
                                                        .contains("--baseline")
                                                        .contains(DensityGate.MAX_DENSITY_PROPERTY)
                                                        .contains(DensityGate.MAX_DENSITY_OPTION)
                                                        .contains("ABOVE")
                                                        .contains("inverted");
    }
}
