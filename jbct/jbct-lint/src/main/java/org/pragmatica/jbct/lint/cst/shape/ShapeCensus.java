package org.pragmatica.jbct.lint.cst.shape;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;

import static org.pragmatica.jbct.parser.CstNodes.findAllMethods;


/// Shape-distribution census over classified methods (issue #448, phase 1).
///
/// Aggregates [MethodShapeClassifier] verdicts into a shape histogram so the corpus-calibration gate
/// — the ticket's "UNCLASSIFIED + misclassified rate acceptably low (target < 5%)" — is measurable in
/// one sweep. This is the deliberate *census mechanism*: a plain public aggregator (not a per-method
/// INFO diagnostic, which the ticket forbids — only MIXED/UNCLASSIFIED get diagnostics) reachable
/// from both tests (pass parsed roots directly) and a future CLI `shape-census` subcommand (pass a
/// [SourceFile] collection; parsing happens here). A histogram over an enum is the smallest surface
/// that answers "what is the distribution and the residual rate" without materialising per-method
/// records the caller would only fold away.
///
/// The automatically-measurable residual is MIXED + UNCLASSIFIED ([CensusReport#residualRate]);
/// *misclassification within the six shapes* needs a human-labelled oracle and stays a review
/// concern, as the ticket's "known limits" require.
public final class ShapeCensus {
    private ShapeCensus() {}

    /// A shape histogram plus the derived residual rate.
    public record CensusReport(Map<MethodShape, Integer> histogram, int totalMethods) {
        /// Count for one shape (0 when absent).
        public int count(MethodShape shape) {
            return histogram.getOrDefault(shape, 0);
        }

        /// Fraction of classified methods left [MethodShape#UNCLASSIFIED].
        public double unclassifiedRate() {
            return rate(count(MethodShape.UNCLASSIFIED));
        }

        /// Fraction of classified methods flagged [MethodShape#MIXED].
        public double mixedRate() {
            return rate(count(MethodShape.MIXED));
        }

        /// The phase-1 calibration proxy: MIXED + UNCLASSIFIED over the total — the surfaced-diagnostic
        /// rate the ticket's < 5% gate targets (intra-shape misclassification needs labels, excluded).
        public double residualRate() {
            return rate(count(MethodShape.MIXED) + count(MethodShape.UNCLASSIFIED));
        }

        private double rate(int part) {
            return totalMethods == 0 ? 0.0 : (double) part / totalMethods;
        }

        /// A one-block human summary: per-shape counts, total, and residual rate — the text a test or
        /// CLI sweep prints.
        public String render() {
            var builder = new StringBuilder("Method-shape census (").append(totalMethods).append(" methods)\n");

            for (var shape : MethodShape.values()) {
                builder.append(String.format("  %-13s %d%n", shape, count(shape)));
            }

            return builder.append(String.format("  residual (MIXED+UNCLASSIFIED): %.2f%%%n", residualRate() * 100)).toString();
        }
    }

    /// Census over one already-parsed compilation unit.
    public static CensusReport census(Cursor root) {
        var counts = new EnumMap<MethodShape, Integer>(MethodShape.class);

        tallyInto(root, counts);

        return report(counts);
    }

    /// Census over a source-file collection — the one-sweep corpus entry point. Each file is parsed;
    /// a file that fails to parse contributes nothing (parse failures are the parser's concern, not
    /// the census's).
    public static CensusReport census(Collection<SourceFile> files) {
        var parser = new Java25Parser();
        var counts = new EnumMap<MethodShape, Integer>(MethodShape.class);

        files.forEach(file -> parser.parse(file.content())
                                    .onSuccess(root -> tallyInto(root, counts)));

        return report(counts);
    }

    private static void tallyInto(Cursor root, Map<MethodShape, Integer> counts) {
        findAllMethods(root).forEach(method -> MethodShapeClassifier.classify(method)
                                                                    .onPresent(verdict -> counts.merge(verdict.shape(), 1, Integer::sum)));
    }

    private static CensusReport report(Map<MethodShape, Integer> counts) {
        var total = counts.values()
                          .stream()
                          .mapToInt(Integer::intValue)
                          .sum();

        return new CensusReport(Map.copyOf(counts), total);
    }
}
