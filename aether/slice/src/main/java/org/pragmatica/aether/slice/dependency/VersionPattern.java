package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Version pattern for dependency matching using semantic versioning.
 * <p>
 * Supported patterns:
 * - Exact: "1.2.3"
 * - Range: "[1.0.0,2.0.0)", "(1.0.0,2.0.0]", "[1.0.0,2.0.0]", "(1.0.0,2.0.0)"
 * - Comparison: ">=1.5.0", ">1.0.0", "<=2.0.0", "<3.0.0"
 * - Tilde: "~1.2.3" (patch-level: >=1.2.3, <1.3.0)
 * - Caret: "^1.2.3" (minor-level: >=1.2.3, <2.0.0)
 */
public sealed interface VersionPattern {
    boolean matches(Version version);

    String asString();

    /// Exact version match
    record Exact(Version version) implements VersionPattern {
        @Override
        public boolean matches(Version other) {
            return version.equals(other);
        }

        @Override
        public String asString() {
            return version.withQualifier();
        }
    }

    /// Version range with inclusive/exclusive bounds
    record Range(Version from,
                 boolean fromInclusive,
                 Version to,
                 boolean toInclusive) implements VersionPattern {
        @Override
        public boolean matches(Version version) {
            int fromCmp = compareVersions(version, from);
            int toCmp = compareVersions(version, to);
            boolean fromMatch = fromInclusive
                                ? fromCmp >= 0
                                : fromCmp > 0;
            boolean toMatch = toInclusive
                              ? toCmp <= 0
                              : toCmp < 0;
            return fromMatch && toMatch;
        }

        @Override
        public String asString() {
            var fromBracket = fromInclusive
                              ? "["
                              : "(";
            var toBracket = toInclusive
                            ? "]"
                            : ")";
            return fromBracket + from.withQualifier() + "," + to.withQualifier() + toBracket;
        }
    }

    /// Comparison operator version pattern
    record Comparison(Operator operator, Version version) implements VersionPattern {
        @Override
        public boolean matches(Version other) {
            int cmp = compareVersions(other, version);
            return switch (operator) {
                case GT -> cmp > 0;
                case GTE -> cmp >= 0;
                case LT -> cmp < 0;
                case LTE -> cmp <= 0;
            };
        }

        @Override
        public String asString() {
            return operator.symbol() + version.withQualifier();
        }

        public enum Operator {
            GT(">"),
            GTE(">="),
            LT("<"),
            LTE("<=");
            private final String symbol;
            Operator(String symbol) {
                this.symbol = symbol;
            }
            public String symbol() {
                return symbol;
            }
            public static Result<Operator> fromSymbol(String symbol) {
                return switch (symbol) {
                    case ">" -> Result.success(GT);
                    case ">=" -> Result.success(GTE);
                    case "<" -> Result.success(LT);
                    case "<=" -> Result.success(LTE);
                    default -> INVALID_OPERATOR.apply(symbol)
                                               .result();
                };
            }
            private static final Fn1<Cause, String> INVALID_OPERATOR = Causes.forOneValue("Invalid comparison operator: %s");
        }
    }

    /// Tilde pattern: patch-level changes (~1.2.3 means >=1.2.3, <1.3.0)
    record Tilde(Version version) implements VersionPattern {
        @Override
        public boolean matches(Version other) {
            if (compareVersions(other, version) < 0) {
                return false;
            }
            // Must have same major and minor
            return other.major() == version.major() &&
            other.minor() == version.minor();
        }

        @Override
        public String asString() {
            return "~" + version.withQualifier();
        }
    }

    /// Caret pattern: minor-level changes (^1.2.3 means >=1.2.3, <2.0.0)
    record Caret(Version version) implements VersionPattern {
        @Override
        public boolean matches(Version other) {
            if (compareVersions(other, version) < 0) {
                return false;
            }
            // Must have same major
            return other.major() == version.major();
        }

        @Override
        public String asString() {
            return "^" + version.withQualifier();
        }
    }

    /// Compare two versions: returns negative if v1 < v2, 0 if equal, positive if v1 > v2
    static int compareVersions(Version v1, Version v2) {
        if (v1.major() != v2.major()) {
            return Integer.compare(v1.major(), v2.major());
        }
        if (v1.minor() != v2.minor()) {
            return Integer.compare(v1.minor(), v2.minor());
        }
        if (v1.patch() != v2.patch()) {
            return Integer.compare(v1.patch(), v2.patch());
        }
        // Qualifier comparison: lexicographic
        return v1.qualifier()
                 .compareTo(v2.qualifier());
    }

    /// Parse version pattern from string
    static Result<VersionPattern> parse(String pattern) {
        var trimmed = pattern.trim();
        if (trimmed.isEmpty()) {
            return EMPTY_PATTERN.result();
        }
        // Range pattern: [1.0.0,2.0.0)
        if (isRangePattern(trimmed)) {
            return parseRange(trimmed);
        }
        // Tilde pattern: ~1.2.3
        if (trimmed.startsWith("~")) {
            return parseTilde(trimmed);
        }
        // Caret pattern: ^1.2.3
        if (trimmed.startsWith("^")) {
            return parseCaret(trimmed);
        }
        // Comparison pattern: >=1.5.0, >1.0.0, <=2.0.0, <3.0.0
        if (isComparisonPattern(trimmed)) {
            return parseComparison(trimmed);
        }
        // Exact version: 1.2.3
        return parseExact(trimmed);
    }

    private static boolean isRangePattern(String pattern) {
        return ( pattern.startsWith("[") || pattern.startsWith("(")) &&
        (pattern.endsWith("]") || pattern.endsWith(")"));
    }

    private static boolean isComparisonPattern(String pattern) {
        return pattern.startsWith(">=") || pattern.startsWith(">") ||
        pattern.startsWith("<=") || pattern.startsWith("<");
    }

    private static Result<VersionPattern> parseRange(String pattern) {
        var fromInclusive = pattern.startsWith("[");
        var toInclusive = pattern.endsWith("]");
        var content = pattern.substring(1, pattern.length() - 1);
        var parts = content.split(",");
        if (parts.length != 2) {
            return INVALID_RANGE_FORMAT.apply(pattern)
                                       .result();
        }
        return Version.version(parts[0].trim())
                      .flatMap(from -> Version.version(parts[1].trim())
                                              .map(to -> new Range(from, fromInclusive, to, toInclusive)));
    }

    private static Result<VersionPattern> parseTilde(String pattern) {
        var versionStr = pattern.substring(1)
                                .trim();
        return Version.version(versionStr)
                      .map(Tilde::new);
    }

    private static Result<VersionPattern> parseCaret(String pattern) {
        var versionStr = pattern.substring(1)
                                .trim();
        return Version.version(versionStr)
                      .map(Caret::new);
    }

    private static Result<VersionPattern> parseComparison(String pattern) {
        // Extract operator
        String opStr;
        String versionStr;
        if (pattern.startsWith(">=")) {
            opStr = ">=";
            versionStr = pattern.substring(2)
                                .trim();
        } else if (pattern.startsWith("<=")) {
            opStr = "<=";
            versionStr = pattern.substring(2)
                                .trim();
        } else if (pattern.startsWith(">")) {
            opStr = ">";
            versionStr = pattern.substring(1)
                                .trim();
        } else if (pattern.startsWith("<")) {
            opStr = "<";
            versionStr = pattern.substring(1)
                                .trim();
        } else {
            return INVALID_COMPARISON_FORMAT.apply(pattern)
                                            .result();
        }
        return Comparison.Operator.fromSymbol(opStr)
                         .flatMap(operator -> Version.version(versionStr)
                                                     .map(version -> new Comparison(operator, version)));
    }

    private static Result<VersionPattern> parseExact(String pattern) {
        return Version.version(pattern)
                      .map(Exact::new);
    }

    // Error constants
    Cause EMPTY_PATTERN = Causes.cause("Version pattern cannot be empty");
    Fn1<Cause, String> INVALID_RANGE_FORMAT = Causes.forOneValue("Invalid range format: %s");
    Fn1<Cause, String> INVALID_COMPARISON_FORMAT = Causes.forOneValue("Invalid comparison format: %s");
}
