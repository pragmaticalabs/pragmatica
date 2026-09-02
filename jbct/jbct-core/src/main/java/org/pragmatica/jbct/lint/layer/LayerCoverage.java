package org.pragmatica.jbct.lint.layer;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;


/// Layering-coverage census for one lint run (issue #534).
///
/// The layering rules — JBCT-ARCH-01 (dependency direction) and JBCT-ARCH-02 (`lift(...)` zone) —
/// are deliberately silent for a file whose own package cannot be classified: a direction cannot be
/// ranked when one endpoint has no rank. That skip is correct, but it leaves no trace, so a narrow
/// `[lint.layers]` config enforces almost nothing while the run still reports clean. Measured: an
/// adapter-only config over aether's 46 cloud-provider files produced **0** findings; the same config
/// plus a domain catch-all produced **37**.
///
/// This census restores the trace as ONE line per run ([#render()]) rather than per-file INFO
/// diagnostics — under a narrow config most files are unclassified, so per-file notes would trade
/// silence for thousands of lines. It is a standalone aggregator over the run's file collection (a
/// report, not diagnostics), so every CLI command and Maven goal that lints shares one implementation
/// of the count instead of each growing its own counter in its own file loop.
///
/// **Gated on provenance.** A summary is produced only when the classifier was built from an explicit
/// `[lint.layers]` config ([LayerClassifier#isExplicitlyConfigured()]). Without one, classification
/// needs a literal layer keyword (`domain` / `adapter` / `usecase` / ...) among the package segments,
/// which most real package names lack — an ungated summary would fire for every project that never
/// opted in.
///
/// **What the buckets mean.** `evaluated` — the file's own package classified into a [Layer], so both
/// layering rules ran against it; `unclassified` — no layer, so both stayed silent; `excluded` — the
/// package matched `excludePackages`, so linting skipped the file before layering was consulted. A
/// file whose content cannot be read falls into no bucket (the run reports that failure separately),
/// so [#total()] counts the files the census could actually see.
///
/// **Limit (syntax only):** jbct-core has no parser, so the package name is read from the source text
/// — the first `package ...;` that starts a line outside comments — not from the CST the rules
/// classify. The two agree on every well-formed compilation unit; a package declaration split across
/// lines is counted as unclassified.
public record LayerCoverage(int evaluated, int unclassified, int excluded) {
    /// A package declaration opening a (stripped) line. Matched line by line, so a `package ...;`
    /// spelled inside a `//` or `*`-prefixed comment cannot match.
    private static final Pattern PACKAGE_DECL = Pattern.compile("^package\\s+([\\w.]+)\\s*;");

    /// Why a file did or did not reach the layering rules.
    private enum Bucket {
        EVALUATED,
        UNCLASSIFIED,
        EXCLUDED
    }

    /// Files the census could read — the sum of the three buckets.
    public int total() {
        return evaluated + unclassified + excluded;
    }

    /// The one-line run summary, e.g. `layering: evaluated 46 of 3458 files, 3412 unclassified`. The
    /// excluded count is appended only when `excludePackages` actually skipped something, so the
    /// arithmetic on the line always closes.
    public String render() {
        var summary = "layering: evaluated " + evaluated + " of " + total() + " files, " + unclassified
                     + " unclassified";

        return excluded == 0
               ? summary
               : summary + ", " + excluded + " excluded";
    }

    /// Census over the paths a run collected — the CLI / Mojo entry point. Files are read here (the
    /// gate is checked first, so a run without explicit layer config reads nothing); an unreadable
    /// file contributes to no bucket.
    public static Option<LayerCoverage> coverage(Collection<Path> paths, LintContext ctx) {
        return census(paths.stream()
                           .flatMap(LayerCoverage::readPackage), ctx);
    }

    /// Census over already-loaded sources — the in-memory entry point.
    public static Option<LayerCoverage> coverageOfSources(Collection<SourceFile> files, LintContext ctx) {
        return census(files.stream()
                           .map(SourceFile::content)
                           .map(LayerCoverage::packageOf), ctx);
    }

    private static Option<LayerCoverage> census(Stream<String> packageNames, LintContext ctx) {
        if (!ctx.layers()
                .isExplicitlyConfigured()) {
            return Option.none();
        }

        var counts = new EnumMap<Bucket, Integer>(Bucket.class);

        packageNames.forEach(packageName -> counts.merge(bucketOf(packageName, ctx), 1, Integer::sum));

        return Option.some(new LayerCoverage(count(counts, Bucket.EVALUATED),
                                             count(counts, Bucket.UNCLASSIFIED),
                                             count(counts, Bucket.EXCLUDED)));
    }

    private static Bucket bucketOf(String packageName, LintContext ctx) {
        if (!ctx.shouldLint(packageName)) {
            return Bucket.EXCLUDED;
        }

        return ctx.layers()
                  .layerOf(packageName)
                  .isPresent()
               ? Bucket.EVALUATED
               : Bucket.UNCLASSIFIED;
    }

    private static int count(Map<Bucket, Integer> counts, Bucket bucket) {
        return counts.getOrDefault(bucket, 0);
    }

    private static Stream<String> readPackage(Path path) {
        return SourceFile.sourceFile(path)
                         .map(SourceFile::content)
                         .map(LayerCoverage::packageOf)
                         .stream();
    }

    /// The file's declared package, or `""` when it declares none — the same string the CST-based
    /// classification yields for a default-package file.
    private static String packageOf(String content) {
        var inBlockComment = false;

        for (var rawLine : content.split("\n")) {
            var line = rawLine.strip();

            if (inBlockComment) {
                inBlockComment = !line.contains("*/");

                continue;
            }

            if (line.startsWith("/*")) {
                inBlockComment = !line.contains("*/");

                continue;
            }

            var matcher = PACKAGE_DECL.matcher(line);

            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        return "";
    }
}
