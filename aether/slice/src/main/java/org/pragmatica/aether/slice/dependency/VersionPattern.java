// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-NEST-01", "JBCT-UTIL-02"})
public sealed interface VersionPattern {
    boolean matches(Version version);
    String asString();

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

    record Range(Version from, boolean fromInclusive, Version to, boolean toInclusive) implements VersionPattern {
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
                    case ">" -> success(GT);
                    case ">=" -> success(GTE);
                    case "<" -> success(LT);
                    case "<=" -> success(LTE);
                    default -> INVALID_OPERATOR.apply(symbol).result();
                };
            }
            private static final Fn1<Cause, String> INVALID_OPERATOR = Causes.forOneValue("Invalid comparison operator: %s");
        }
    }

    record Tilde(Version version) implements VersionPattern {
        @Override
        public boolean matches(Version other) {
            if (compareVersions(other, version) < 0) {
                return false;
            }

            return other.major() == version.major() && other.minor() == version.minor();
        }

        @Override
        public String asString() {
            return "~" + version.withQualifier();
        }
    }

    record Caret(Version version) implements VersionPattern {
        @Override
        public boolean matches(Version other) {
            if (compareVersions(other, version) < 0) {
                return false;
            }

            return other.major() == version.major();
        }

        @Override
        public String asString() {
            return "^" + version.withQualifier();
        }
    }

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

        return compareQualifiers(v1.qualifier(), v2.qualifier());
    }

    /// #1435 — pre-release ordering, following semver §11 and Maven's `ComparableVersion` on the cases that
    /// matter here. It used to be a plain `String.compareTo`, which put `1.0.0` BELOW `1.0.0-SNAPSHOT` and
    /// `rc10` below `rc9`; since #1184 that ordering decides whether an `[infra]` slice loads.
    ///
    ///   - An empty qualifier (a release) is newer than any pre-release of the same `major.minor.patch`.
    ///     So a loaded pre-release never satisfies a requester of the release it precedes: `^1.0.0`
    ///     against a loaded `1.0.0-SNAPSHOT` is a conflict, and `^1.0.0-SNAPSHOT` against `1.0.0` is
    ///     compatible.
    ///   - Otherwise the qualifiers are compared token by token. A token is a run of digits or a run of
    ///     letters (`.` and `-` separate too), so `rc10` is `[rc, 10]`. Two numeric tokens compare
    ///     numerically (`rc9` < `rc10`), a numeric token sorts before a letter token, letter tokens compare
    ///     case-insensitively (`alpha` < `beta` < `rc` < `snapshot`), and a qualifier that is a prefix of
    ///     another sorts first.
    static int compareQualifiers(String q1, String q2) {
        if (q1.isEmpty() || q2.isEmpty()) {
            return Boolean.compare(q1.isEmpty(), q2.isEmpty());
        }

        var t1 = qualifierTokens(q1);
        var t2 = qualifierTokens(q2);

        for (int i = 0; i < Math.min(t1.size(), t2.size()); i++) {
            var cmp = compareTokens(t1.get(i), t2.get(i));

            if (cmp != 0) {
                return cmp;
            }
        }

        return Integer.compare(t1.size(), t2.size());
    }

    private static List<String> qualifierTokens(String qualifier) {
        var tokens = new ArrayList<String>();
        var matcher = QUALIFIER_TOKEN.matcher(qualifier);

        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        return tokens;
    }

    /// Numeric tokens of any length, without overflow: strip leading zeros, then the longer is larger.
    private static int compareNumericTokens(String a, String b) {
        var x = stripLeadingZeros(a);
        var y = stripLeadingZeros(b);

        return x.length() != y.length()
               ? Integer.compare(x.length(), y.length())
               : x.compareTo(y);
    }

    private static String stripLeadingZeros(String digits) {
        var i = 0;

        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }

        return digits.substring(i);
    }

    private static int compareTokens(String a, String b) {
        var aNumeric = Character.isDigit(a.charAt(0));
        var bNumeric = Character.isDigit(b.charAt(0));

        if (aNumeric && bNumeric) {
            return compareNumericTokens(a, b);
        }

        if (aNumeric != bNumeric) {
            return aNumeric
                   ? -1
                   : 1;
        }

        return a.compareToIgnoreCase(b);
    }

    static Result<VersionPattern> parse(String pattern) {
        var trimmed = pattern.trim();

        if (trimmed.isEmpty()) {
            return EMPTY_PATTERN.result();
        }

        if (isRangePattern(trimmed)) {
            return parseRange(trimmed);
        }

        if (trimmed.startsWith("~")) {
            return parseTilde(trimmed);
        }

        if (trimmed.startsWith("^")) {
            return parseCaret(trimmed);
        }

        if (isComparisonPattern(trimmed)) {
            return parseComparison(trimmed);
        }

        return parseExact(trimmed);
    }

    private static boolean isRangePattern(String pattern) {
        return (pattern.startsWith("[") || pattern.startsWith("(")) && (pattern.endsWith("]") || pattern.endsWith(")"));
    }

    private static boolean isComparisonPattern(String pattern) {
        return pattern.startsWith(">=") || pattern.startsWith(">") || pattern.startsWith("<=") || pattern.startsWith("<");
    }

    private static Result<VersionPattern> parseRange(String pattern) {
        var fromInclusive = pattern.startsWith("[");
        var toInclusive = pattern.endsWith("]");
        var content = pattern.substring(1, pattern.length() - 1);
        var parts = content.split(",");

        if (parts.length != 2) {
            return INVALID_RANGE_FORMAT.apply(pattern).result();
        }

        return Version.version(parts[0].trim()).flatMap(from -> Version.version(parts[1].trim()).map(to -> new Range(from,
                                                                                                                     fromInclusive,
                                                                                                                     to,
                                                                                                                     toInclusive)));
    }

    private static Result<VersionPattern> parseTilde(String pattern) {
        var versionStr = pattern.substring(1).trim();

        return Version.version(versionStr).map(Tilde::new);
    }

    private static Result<VersionPattern> parseCaret(String pattern) {
        var versionStr = pattern.substring(1).trim();

        return Version.version(versionStr).map(Caret::new);
    }

    private static Result<VersionPattern> parseComparison(String pattern) {
        String opStr;
        String versionStr;

        if (pattern.startsWith(">=")) {
            opStr = ">=";
            versionStr = pattern.substring(2).trim();
        } else if (pattern.startsWith("<=")) {
            opStr = "<=";
            versionStr = pattern.substring(2).trim();
        } else if (pattern.startsWith(">")) {
            opStr = ">";
            versionStr = pattern.substring(1).trim();
        } else if (pattern.startsWith("<")) {
            opStr = "<";
            versionStr = pattern.substring(1).trim();
        } else {
            return INVALID_COMPARISON_FORMAT.apply(pattern).result();
        }

        return Comparison.Operator.fromSymbol(opStr).flatMap(operator -> Version.version(versionStr).map(version -> new Comparison(operator,
                                                                                                                                   version)));
    }

    private static Result<VersionPattern> parseExact(String pattern) {
        return Version.version(pattern).map(Exact::new);
    }

    Cause EMPTY_PATTERN = Causes.cause("Version pattern cannot be empty");
    Pattern QUALIFIER_TOKEN = Pattern.compile("\\d+|[A-Za-z]+");
    Fn1<Cause, String> INVALID_RANGE_FORMAT = Causes.forOneValue("Invalid range format: %s");
    Fn1<Cause, String> INVALID_COMPARISON_FORMAT = Causes.forOneValue("Invalid comparison format: %s");

    record unused() implements VersionPattern {
        @Override
        public boolean matches(Version version) {
            return false;
        }

        @Override
        public String asString() {
            return "unused";
        }
    }
}
