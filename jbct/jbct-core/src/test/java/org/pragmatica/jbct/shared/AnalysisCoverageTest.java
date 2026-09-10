package org.pragmatica.jbct.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.shared.AnalysisCoverage.analysisCoverage;


/// Unit gate for the coverage clause every JBCT summary line now carries (#977).
///
/// Two properties, and they pull in opposite directions on purpose:
///
///   - a COMPLETE run renders exactly what these summaries rendered before, so nothing keying on a
///     clean result has to change and the whole visible diff lands in the case that was lying;
///   - a PARTIAL run renders the gap, loudly, and can never be mistaken for the complete form —
///     which is checked here as the absence of the bare count, the value the old code printed.
class AnalysisCoverageTest {
    @Test
    void render_printsTheBareCount_whenEveryCollectedFileWasAnalysed() {
        assertThat(analysisCoverage(2, 0).render()).isEqualTo("2 file(s)");
    }

    @Test
    void render_printsBothDenominators_whenSomeFilesCouldNotBeAnalysed() {
        assertThat(analysisCoverage(2, 1).render()).isEqualTo("1 of 2 file(s), 1 UNPARSEABLE");
    }

    /// The forbidden value: `2 file(s)` is exactly what the old summaries printed for this state, and
    /// a reader keying on the count line took it for full coverage.
    ///
    /// Asserted as inequality against the complete form's own rendering rather than as a `contains`
    /// check, because the partial clause legitimately ends `... of 2 file(s), 1 UNPARSEABLE` — a
    /// substring pattern cannot separate the honest use of that count from the dishonest one, and the
    /// first version of this test failed for exactly that reason. What must hold is that the two
    /// states of the same collected set never render alike.
    @Test
    void render_neverRendersLikeACompleteRun_whenCoverageIsPartial() {
        assertThat(analysisCoverage(2, 1).render()).isNotEqualTo(analysisCoverage(2, 0).render())
                                                   .startsWith("1 of 2 ")
                                                   .endsWith(" UNPARSEABLE");
    }

    /// The degenerate case: nothing was analysed at all, and the clause has to survive it rather than
    /// rendering an empty or absurd numerator.
    @Test
    void render_reportsZeroAnalysed_whenNothingCouldBeAnalysed() {
        assertThat(analysisCoverage(1, 1).render()).isEqualTo("0 of 1 file(s), 1 UNPARSEABLE");
    }

    @Test
    void analysed_countsOnlyTheFilesTheRulesRanOn() {
        assertThat(analysisCoverage(7, 3).analysed()).isEqualTo(4);
    }

    @Test
    void isPartial_isFalse_whenNothingWasSkipped() {
        assertThat(analysisCoverage(7, 0).isPartial()).isFalse();
    }

    @Test
    void isPartial_isTrue_whenAnythingWasSkipped() {
        assertThat(analysisCoverage(7, 1).isPartial()).isTrue();
    }

    /// Silent when there is nothing to disclose — the same discipline as #740's `reportNothingToCheck`.
    /// A warning that fires on clean runs stops being read, and then it protects nothing.
    @Test
    void gapReport_isAbsent_whenCoverageIsComplete() {
        assertThat(analysisCoverage(3, 0).gapReport("lint").isPresent()).isFalse();
    }

    @Test
    void gapReport_namesTheGoalTheCountAndTheConsequence_whenCoverageIsPartial() {
        var report = analysisCoverage(3, 2).gapReport("lint")
                                           .or("<absent>");

        assertThat(report).startsWith("Parse errors: 2")
                          .contains("2 file(s) could not be read or parsed")
                          .contains("NOT analysed by JBCT lint")
                          .contains("COVERAGE GAP")
                          .contains("not evidence about those file(s)");
    }
}
