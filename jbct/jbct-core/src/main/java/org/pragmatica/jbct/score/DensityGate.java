package org.pragmatica.jbct.score;

import java.util.Locale;


/// The density gate: the one threshold that can fail a build, and the direction it fails in.
///
/// Density is *lower is better*, so the gate fails **above** the threshold — the exact opposite
/// of the 0-100 score's `jbct.score.baseline` / `--baseline`, which failed below it. Keeping the
/// old name with the inverted meaning would have been a silent-wrong-state defect all of its own:
/// a CI snippet copied forward would quietly assert the opposite of what it says. So the old
/// names are not accepted at all — they are detected and rejected with [#REMOVED_BASELINE_MESSAGE].
///
/// Both surfaces read the direction and the wording from here, so the CLI option and the Maven
/// property cannot drift apart on either.
public sealed interface DensityGate permits DensityGate.unused {
    record unused() implements DensityGate {}

    /// Maven property carrying the maximum acceptable density.
    String MAX_DENSITY_PROPERTY = "jbct.density.maxPerKloc";

    /// CLI option carrying the maximum acceptable density.
    String MAX_DENSITY_OPTION = "--max-density";

    /// Rejection message for the removed 0-100 score gate. It names the replacement *and* the
    /// inversion, because the old threshold value cannot be carried over: it meant "at least".
    String REMOVED_BASELINE_MESSAGE = """
        jbct.score.baseline / --baseline was removed: the 0-100 compliance score it gated no longer exists.
        The report is now violation density — violations per 1000 non-blank lines, where LOWER is better.
        Use %s (Maven) or %s (CLI), which fails when density is ABOVE the value.
        The direction is inverted, so the old threshold cannot be reused — pick a new one from a current report.\
        """.formatted(MAX_DENSITY_PROPERTY, MAX_DENSITY_OPTION);

    /// Whether a measured density breaches the gate. Equal to the threshold passes.
    static boolean exceeds(double densityPerKloc, double maxPerKloc) {
        return densityPerKloc > maxPerKloc;
    }

    /// Failure message naming both densities, so a breach reads as the comparison it is.
    static String breachMessage(double densityPerKloc, double maxPerKloc) {
        return String.format(Locale.ROOT,
                             "Density %.1f/KLOC exceeds maximum %.1f/KLOC",
                             densityPerKloc,
                             maxPerKloc);
    }
}
