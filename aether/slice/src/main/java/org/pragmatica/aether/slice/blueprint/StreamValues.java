// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Value parsing for a `[streams.<alias>]` section (#1549), every outcome typed.
///
/// Before #1549 these values never reached the runtime, so the parser's number handling was never
/// exercised there: a non-numeric value threw `NumberFormatException`, a large one overflowed silently
/// into a negative bound, and an unknown enum spelling fell back to a default. Each is now a
/// [StreamDeclarationError] naming the key and the value — [StreamDeclarationError.MalformedValue],
/// [StreamDeclarationError.ValueOverflows], [StreamDeclarationError.ValueOutOfRange],
/// [StreamDeclarationError.NotAnInteger] — at deploy and at activation alike.
public sealed interface StreamValues {
    /// Digits with an optional unit, whitespace allowed between them; no sign, no fraction.
    Pattern AMOUNT = Pattern.compile("^([0-9]+)\\s*([A-Za-z]*)$");

    Map<String, Long> DURATION_UNITS = Map.of("", 1L, "s", 1_000L, "m", 60_000L, "h", 3_600_000L, "d", 86_400_000L);

    Map<String, Long> SIZE_UNITS = Map.of("", 1L, "B", 1L, "KB", 1024L, "MB", 1024L * 1024, "GB", 1024L * 1024 * 1024);

    String DURATION_FORM = "a whole number of milliseconds or of s/m/h/d, e.g. 5m";
    String SIZE_FORM = "a whole number of bytes or of KB/MB/GB, e.g. 64KB";
    String COUNT_FORM = "a whole number";

    /// A duration in milliseconds, at least 1.
    static Result<Long> duration(String alias, String key, String raw) {
        return amount(alias, key, raw, DURATION_UNITS, false, DURATION_FORM);
    }

    /// A size in bytes, at least 1.
    static Result<Long> size(String alias, String key, String raw) {
        return amount(alias, key, raw, SIZE_UNITS, true, SIZE_FORM);
    }

    /// A count, at least 1.
    static Result<Long> count(String alias, String key, String raw) {
        return amount(alias, key, raw, Map.of("", 1L), false, COUNT_FORM);
    }

    /// An `int`, refused (not truncated, not defaulted) when the text is not one.
    static Result<Integer> integer(String alias, String key, String raw) {
        return Result.lift(new StreamDeclarationError.NotAnInteger(alias, key, raw),
                           () -> Integer.parseInt(raw.trim()));
    }

    /// One of `allowed`, compared case-insensitively; anything else is refused rather than defaulted.
    static Result<String> oneOf(String alias, String key, String raw, List<String> allowed) {
        var normalized = raw.trim().toLowerCase();

        return allowed.contains(normalized)
               ? success(normalized)
               : new StreamDeclarationError.MalformedValue(alias, key, raw, "one of " + String.join(", ", allowed)).result();
    }

    private static Result<Long> amount(String alias,
                                       String key,
                                       String raw,
                                       Map<String, Long> units,
                                       boolean upperCaseUnit,
                                       String form) {
        var matcher = AMOUNT.matcher(raw.trim());

        if (!matcher.matches()) {
            return new StreamDeclarationError.MalformedValue(alias, key, raw, form).result();
        }

        var unit = upperCaseUnit
                   ? matcher.group(2).toUpperCase()
                   : matcher.group(2).toLowerCase();
        var multiplier = units.get(unit);

        if (multiplier == null) {
            return new StreamDeclarationError.MalformedValue(alias, key, raw, form).result();
        }

        return scaled(alias, key, raw, matcher.group(1), multiplier).flatMap(value -> atLeastOne(alias, key, raw, value));
    }

    private static Result<Long> scaled(String alias, String key, String raw, String digits, long multiplier) {
        return Result.lift(new StreamDeclarationError.ValueOverflows(alias, key, raw),
                           () -> Math.multiplyExact(Long.parseLong(digits), multiplier));
    }

    private static Result<Long> atLeastOne(String alias, String key, String raw, long value) {
        return value >= 1
               ? success(value)
               : new StreamDeclarationError.ValueOutOfRange(alias, key, raw, 1).result();
    }

    record unused() implements StreamValues {}
}
