package org.pragmatica.jbct.lint.layer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;


/// Package-classification engine (issue #452).
///
/// Classifies a package — from single-file facts, i.e. the file's own `package` declaration and its
/// imports — into a [Layer] and a [Zone], and resolves the slice a package belongs to. Two inputs
/// drive classification, explicit-first:
///
///   1. **Explicit config** ([LayerConfig]) — per-layer package globs and slice-root globs, using
///      the `excludePackages` glob syntax (`*` = one segment, `**` = any depth). Consulted first, in
///      registration order (domain, application, adapter, bootstrap).
///   2. **Book-layout conventions** — a package is classified by the layer keyword among its dotted
///      segments: `domain` -> domain; `application` / `usecase` -> application; `adapter` /
///      `integration` / `infra` -> adapter; `bootstrap` / `main` -> bootstrap. The deepest
///      (rightmost) matching segment wins, so a slice's `...registeruser.domain` sub-package reads as
///      domain even though it lives under a `usecase` ancestor.
///
/// A package that matches neither is deliberately left unclassified ([Option#none()]); the layering
/// rules emit nothing for it rather than guess.
///
/// Slices: a slice ROOT is the package that owns a vertical slice; every package strictly beneath a
/// root is one of that slice's internals. Roots come from explicit `slices` globs (the shortest
/// matching prefix of a package is its root) or, by convention, from the immediate child of a
/// `usecase` segment (`com.example.usecase.registeruser`). JBCT-ARCH-04 uses this to flag imports
/// that reach into another slice's internals.
///
/// **Limits (syntax only, no cross-file resolution):** classification sees package strings, not
/// resolved types; a framework/layer target is recognised only by package shape. There is no
/// transitive-dependency analysis — each import is judged on its own package.
public final class LayerClassifier {
    /// Convention layer keyword -> layer. The deepest (rightmost) matching package segment wins.
    private static final Map<String, Layer> SEGMENT_LAYERS = Map.ofEntries(Map.entry("domain", Layer.DOMAIN),
                                                                           Map.entry("application", Layer.APPLICATION),
                                                                           Map.entry("usecase", Layer.APPLICATION),
                                                                           Map.entry("adapter", Layer.ADAPTER),
                                                                           Map.entry("integration", Layer.ADAPTER),
                                                                           Map.entry("infra", Layer.ADAPTER),
                                                                           Map.entry("bootstrap", Layer.BOOTSTRAP),
                                                                           Map.entry("main", Layer.BOOTSTRAP));

    /// Convention slice-root marker: the immediate child of a `usecase` segment is a slice root.
    private static final String SLICE_CONVENTION_SEGMENT = "usecase";

    private final List<LayerRule> layerRules;
    private final List<Pattern> sliceGlobs;

    /// An explicit layer glob. `specificity` is the count of literal (non-`*`) characters in the
    /// source glob, so the most-specific matching glob wins across all layer lists.
    private record LayerRule(Pattern pattern, Layer layer, int specificity) {}

    private LayerClassifier(List<LayerRule> layerRules, List<Pattern> sliceGlobs) {
        this.layerRules = layerRules;
        this.sliceGlobs = sliceGlobs;
    }

    /// Build a classifier from explicit config. Globs are compiled once here.
    public static LayerClassifier from(LayerConfig config) {
        var rules = new ArrayList<LayerRule>();

        addRules(rules, config.domainGlobs(), Layer.DOMAIN);
        addRules(rules, config.applicationGlobs(), Layer.APPLICATION);
        addRules(rules, config.adapterGlobs(), Layer.ADAPTER);
        addRules(rules, config.bootstrapGlobs(), Layer.BOOTSTRAP);

        var slices = config.sliceGlobs()
                           .stream()
                           .map(LayerClassifier::compile)
                           .toList();

        return new LayerClassifier(List.copyOf(rules), slices);
    }

    /// A classifier with no explicit config — conventions only.
    public static LayerClassifier conventionsOnly() {
        return from(LayerConfig.DEFAULT);
    }

    private static void addRules(List<LayerRule> rules, List<String> globs, Layer layer) {
        for (var glob : globs) {
            rules.add(new LayerRule(compile(glob), layer, specificity(glob)));
        }
    }

    /// Number of literal (non-wildcard) characters in a glob — its specificity score.
    private static int specificity(String glob) {
        return (int) glob.chars()
                         .filter(c -> c != '*')
                         .count();
    }

    /// Layer of a package: explicit globs first (most-specific match wins), then the deepest
    /// matching convention segment; [Option#none()] when the package is unclassifiable.
    public Option<Layer> layerOf(String packageName) {
        var explicit = explicitLayer(packageName);

        if (explicit.isPresent()) {
            return explicit;
        }

        return conventionLayer(packageName);
    }

    private Option<Layer> explicitLayer(String packageName) {
        Layer bestLayer = null;
        var bestScore = -1;

        for (var rule : layerRules) {
            if (rule.specificity() > bestScore && rule.pattern()
                                                      .matcher(packageName)
                                                      .matches()) {
                bestScore = rule.specificity();
                bestLayer = rule.layer();
            }
        }

        return Option.option(bestLayer);
    }

    /// Zone of a package — the derived [Layer#zone()], or none when the package is unclassifiable.
    public Option<Zone> zoneOf(String packageName) {
        return layerOf(packageName).map(Layer::zone);
    }

    /// Slice root a package belongs to (the root package itself, or an ancestor of it), or none when
    /// the package is not part of any slice. Explicit `slices` globs win over the convention.
    public Option<String> sliceRootOf(String packageName) {
        var explicit = explicitSliceRoot(packageName);

        if (explicit.isPresent()) {
            return explicit;
        }

        return conventionSliceRoot(packageName);
    }

    private Option<Layer> conventionLayer(String packageName) {
        Layer match = null;

        for (var segment : packageName.split("\\.")) {
            match = SEGMENT_LAYERS.getOrDefault(segment, match);
        }

        return Option.option(match);
    }

    private Option<String> explicitSliceRoot(String packageName) {
        if (sliceGlobs.isEmpty()) {
            return Option.none();
        }

        var segments = packageName.split("\\.");

        for (var end = 0; end < segments.length; end++) {
            var prefix = joinSegments(segments, end);

            if (matchesAnySlice(prefix)) {
                return Option.some(prefix);
            }
        }

        return Option.none();
    }

    private boolean matchesAnySlice(String prefix) {
        for (var glob : sliceGlobs) {
            if (glob.matcher(prefix)
                    .matches()) {
                return true;
            }
        }

        return false;
    }

    private Option<String> conventionSliceRoot(String packageName) {
        var segments = packageName.split("\\.");
        var sliceEnd = -1;

        for (var i = 0; i < segments.length - 1; i++) {
            if (SLICE_CONVENTION_SEGMENT.equals(segments[i])) {
                sliceEnd = i + 1;
            }
        }

        if (sliceEnd < 0) {
            return Option.none();
        }

        return Option.some(joinSegments(segments, sliceEnd));
    }

    private static String joinSegments(String[] segments, int endInclusive) {
        return String.join(".", Arrays.copyOfRange(segments, 0, endInclusive + 1));
    }

    private static Pattern compile(String glob) {
        return Pattern.compile(globToRegex(glob));
    }

    private static String globToRegex(String glob) {
        // Placeholder keeps ** from being mangled by the single-* replacement (mirrors LintContext).
        // Literal dots are escaped so a glob segment separator matches only a real '.'.
        return glob.replace("**", "\0DOTSTAR\0")
                   .replace("*", "[^.]*")
                   .replace(".", "\\.")
                   .replace("\0DOTSTAR\0", ".*");
    }
}
