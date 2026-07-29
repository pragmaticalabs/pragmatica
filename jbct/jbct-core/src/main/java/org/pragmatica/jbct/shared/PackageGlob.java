package org.pragmatica.jbct.shared;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/// Canonical package-glob compiler — the single source of truth for the glob syntax used by
/// `excludePackages` and the `[lint.layers]` package lists, so the two surfaces cannot drift apart.
///
/// Globs are matched against a whole package name and are fully anchored (callers use
/// [java.util.regex.Matcher#matches()]).
///
///   - `*` — **exactly one** package segment. `com.example.*` matches `com.example.core` but not
///     `com.example.core.user`. It is usable inside a segment too: `com.*svc` matches
///     `com.ordersvc`, and never reaches across a `.`.
///   - `**` — **zero or more** package segments. `com.example.core.**` therefore matches the bare
///     package `com.example.core` itself as well as every subpackage beneath it, and never the
///     sibling `com.example.corex`.
///
/// The zero-or-more reading is what makes a layer glob cover the package that declares the layer:
/// a class sitting directly in `com.example.core` belongs to the same layer as one in
/// `com.example.core.user`.
///
/// `**` is special only as a whole segment. In the middle of a glob it still spans any depth
/// including none, so `com.**.impl` matches `com.impl`, `com.a.impl` and `com.a.b.impl`; leading,
/// `**.impl` matches the bare package `impl` as well as `com.a.impl`. Embedded in a longer segment
/// (`co**re`) it degrades to the single-segment wildcard, and repeated `**` segments (`a.**.**.b`)
/// collapse into one.
public sealed interface PackageGlob permits PackageGlob.unused {
    record unused() implements PackageGlob {}

    /// The whole-segment wildcard spanning zero or more package segments.
    String ANY_DEPTH = "**";

    /// Compile a package glob into a fully-anchored pattern.
    static Pattern compile(String glob) {
        return Pattern.compile(toRegex(glob));
    }

    /// Regex source of a package glob — the compilation step of [#compile(String)], exposed so the
    /// syntax can be asserted directly.
    static String toRegex(String glob) {
        var segments = normalize(glob);
        var regex = new StringBuilder();

        for (var index = 0; index < segments.size(); index++) {
            regex.append(segmentRegex(segments, index));
        }

        return regex.toString();
    }

    /// Split into segments, collapsing repeated `**` so that `a.**.**.b` reads as `a.**.b`.
    private static List<String> normalize(String glob) {
        var segments = new ArrayList<String>();

        for (var segment : glob.split("\\.", -1)) {
            if (! isRepeatedAnyDepth(segments, segment)) {
                segments.add(segment);
            }
        }

        return segments;
    }

    private static boolean isRepeatedAnyDepth(List<String> segments, String segment) {
        return ANY_DEPTH.equals(segment) && ! segments.isEmpty() && ANY_DEPTH.equals(segments.getLast());
    }

    private static String segmentRegex(List<String> segments, int index) {
        if (ANY_DEPTH.equals(segments.get(index))) {
            return anyDepthRegex(index, segments.size());
        }

        return separatorBefore(segments, index) + literalRegex(segments.get(index));
    }

    /// `**` absorbs one adjacent separator — that is what lets it match zero segments. A leading
    /// `**` takes the separator that follows it, any other `**` takes the one that precedes it; a
    /// glob that is nothing but `**` matches every package.
    private static String anyDepthRegex(int index, int size) {
        if (index > 0) {
            return "(?:\\.[^.]+)*";
        }

        return size == 1
               ? ".*"
               : "(?:[^.]+\\.)*";
    }

    /// The separator preceding a literal segment, omitted for the first segment and after a leading
    /// `**` (which has already emitted it).
    private static String separatorBefore(List<String> segments, int index) {
        return index == 0 || (index == 1 && ANY_DEPTH.equals(segments.getFirst()))
               ? ""
               : "\\.";
    }

    /// One glob segment: literal runs quoted, `*` standing for any characters within the segment.
    private static String literalRegex(String segment) {
        return Arrays.stream(segment.split("\\*", -1))
                     .map(PackageGlob::quoteLiteral)
                     .collect(Collectors.joining("[^.]*"));
    }

    private static String quoteLiteral(String literal) {
        return literal.isEmpty()
               ? ""
               : Pattern.quote(literal);
    }
}
