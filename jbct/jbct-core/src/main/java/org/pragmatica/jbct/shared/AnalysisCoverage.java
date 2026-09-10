package org.pragmatica.jbct.shared;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// How much of a collected file set a run actually ANALYSED (#977).
///
/// Every JBCT summary line printed the COLLECTED count where it meant the ANALYSED count, so a file
/// the tool could not parse — and therefore never examined — was reported as having passed. With one
/// unparseable file and nothing else, `jbct lint` printed
/// `✓ All 1 file(s) passed JBCT compliance check.`: a perfect score over a set the instrument never
/// read. `Parse errors: 1` sat two lines above, in a separate sentence, so anything keying on the
/// summary — a job, or a reader quoting the count line as gate evidence — was told the opposite of
/// the truth.
///
/// This is the shape a check fails in most dangerously: it passes by not looking. The countermeasure
/// is the one that applies to any instrument — **render the failure, not the success.** The
/// `UNPARSEABLE` clause exists only when the gap does, so a reader whose pattern over-matches raises
/// a false ALARM, which gets chased, instead of manufacturing an all-clear, which invites relief.
/// [LayerCoverage][org.pragmatica.jbct.lint.layer.LayerCoverage] does this job for the layering
/// rules' silent skips; this does it for files that never reached any rule at all.
///
/// Together with [AbstractJbctMojo#reportNothingToCheck][org.pragmatica.jbct.maven] (#740) it makes
/// three states that all render as silence tell each other apart: *the goal did not run* (`Skipping
/// JBCT <goal>`), *the goal ran on zero files* (`examined NOTHING` / `No Java files found.`), and
/// *the goal ran and could not read what it was pointed at* (this record's `UNPARSEABLE` clause).
/// They call for different actions, and none of them is a pass.
///
/// `unparseable` counts files that could not be READ or could not be PARSED. Either way the rules
/// never ran on them, which is the only distinction a coverage claim cares about; the per-file line
/// logged where the failure happened carries the specific cause. It can never exceed `collected`,
/// because every increment happens inside the loop over the collected set.
public record AnalysisCoverage(int collected, int unparseable) {
    public static AnalysisCoverage analysisCoverage(int collected, int unparseable) {
        return new AnalysisCoverage(collected, unparseable);
    }

    /// Files the rules actually ran on — the number a summary line means when it says "checked".
    public int analysed() {
        return collected - unparseable;
    }

    /// True when the run cannot speak for every file it collected.
    public boolean isPartial() {
        return unparseable > 0;
    }

    /// The coverage clause for a summary line: `2 file(s)` when the run covered everything it
    /// collected, `1 of 2 file(s), 1 UNPARSEABLE` when it did not.
    ///
    /// The complete form is left byte-identical to what these summaries printed before, so every
    /// change a reader sees lands in the case that was lying, and nothing keying on a clean run
    /// has to be updated to keep working.
    public String render() {
        return isPartial()
               ? analysed() + " of " + collected + " file(s), " + unparseable + " UNPARSEABLE"
               : collected + " file(s)";
    }

    /// The coverage-gap sentence, present only when there is a gap — see the class note on rendering
    /// the failure rather than the success. Opens with `Parse errors: N` so anything already grepping
    /// for that line keeps matching, and says outright that the run is not evidence about the files
    /// it never read, which is the sentence #740 established for the same failure one state earlier.
    public Option<String> gapReport(String goalName) {
        return isPartial()
               ? some("Parse errors: " + unparseable
                     + " — " + unparseable
                     + " file(s) could not be read or parsed and were NOT analysed by JBCT " + goalName
                     + ". This is a COVERAGE GAP, not a pass: this run is not evidence about those file(s).")
               : none();
    }
}
