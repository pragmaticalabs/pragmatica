package org.pragmatica.aether.invoke;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.BitSet;

/// Lightweight 5-field cron expression parser.
///
/// Supports standard cron syntax: minute hour day-of-month month day-of-week.
/// Field syntax: `*`, specific values, ranges (`1-5`), steps (`*/5`, `1-30/5`), lists (`1,3,5`).
public record CronExpression(BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months, BitSet daysOfWeek) {
    private static final int MAX_SEARCH_YEARS = 4;

    private static final Cause INVALID_FORMAT = Causes.cause("Cron expression must have exactly 5 fields: minute hour day-of-month month day-of-week");
    private static final Cause NO_NEXT_FIRE_TIME = Causes.cause("No matching fire time found within search limit");

    /// Parse a 5-field cron expression string.
    public static Result<CronExpression> parse(String expression) {
        var parts = expression.trim()
                              .split("\\s+");
        if (parts.length != 5) {
            return INVALID_FORMAT.result();
        }
        return Result.all(parseField(parts[0], 0, 59, "minute"),
                          parseField(parts[1], 0, 23, "hour"),
                          parseField(parts[2], 1, 31, "day-of-month"),
                          parseField(parts[3], 1, 12, "month"),
                          parseField(parts[4], 0, 6, "day-of-week"))
                     .map(CronExpression::new);
    }

    /// Calculate the next fire time after the given instant.
    public Result<Instant> nextFireTime(Instant now) {
        var dt = now.atZone(ZoneOffset.UTC)
                    .plusMinutes(1)
                    .withSecond(0)
                    .withNano(0);
        var limit = dt.plusYears(MAX_SEARCH_YEARS);
        return searchNextMatch(dt, limit);
    }

    /// Calculate the delay from now until the next fire time.
    public Result<TimeSpan> delayUntilNext(Instant now) {
        return nextFireTime(now)
        .map(next -> TimeSpan.timeSpan(java.time.Duration.between(now, next)
                                           .toMillis())
                             .millis());
    }

    private Result<Instant> searchNextMatch(ZonedDateTime dt, ZonedDateTime limit) {
        var current = dt;
        while (current.isBefore(limit)) {
            current = advanceToMatchingMonth(current);
            if (current.isAfter(limit)) {
                break;
            }
            if (!matchesDayFields(current)) {
                current = current.plusDays(1)
                                 .withHour(0)
                                 .withMinute(0);
                continue;
            }
            current = advanceToMatchingHour(current);
            if (!months.get(current.getMonthValue()) || !matchesDayFields(current)) {
                current = current.plusDays(1)
                                 .withHour(0)
                                 .withMinute(0);
                continue;
            }
            current = advanceToMatchingMinute(current);
            if (!hours.get(current.getHour()) || !months.get(current.getMonthValue()) || !matchesDayFields(current)) {
                current = current.plusHours(1)
                                 .withMinute(0);
                continue;
            }
            return Result.success(current.toInstant());
        }
        return NO_NEXT_FIRE_TIME.result();
    }

    private ZonedDateTime advanceToMatchingMonth(ZonedDateTime dt) {
        var current = dt;
        while (!months.get(current.getMonthValue())) {
            current = current.plusMonths(1)
                             .withDayOfMonth(1)
                             .withHour(0)
                             .withMinute(0);
        }
        return current;
    }

    private ZonedDateTime advanceToMatchingHour(ZonedDateTime dt) {
        var nextHour = hours.nextSetBit(dt.getHour());
        if (nextHour < 0) {
            return dt.plusDays(1)
                     .withHour(0)
                     .withMinute(0);
        }
        return nextHour == dt.getHour()
               ? dt
               : dt.withHour(nextHour)
                   .withMinute(0);
    }

    private ZonedDateTime advanceToMatchingMinute(ZonedDateTime dt) {
        var nextMinute = minutes.nextSetBit(dt.getMinute());
        if (nextMinute < 0) {
            return dt.plusHours(1)
                     .withMinute(0);
        }
        return dt.withMinute(nextMinute);
    }

    private boolean matchesDayFields(ZonedDateTime dt) {
        return daysOfMonth.get(dt.getDayOfMonth()) && daysOfWeek.get(dt.getDayOfWeek()
                                                                       .getValue() % 7);
    }

    private static Result<BitSet> parseField(String field, int min, int max, String fieldName) {
        var bits = new BitSet(max + 1);
        var parts = field.split(",");
        for (var part : parts) {
            var partResult = parsePart(part.trim(), min, max, fieldName);
            if (partResult.isFailure()) {
                return partResult;
            }
            partResult.onSuccess(bits::or);
        }
        if (bits.isEmpty()) {
            return Causes.cause("Empty " + fieldName + " field: " + field)
                         .result();
        }
        return Result.success(bits);
    }

    private static Result<BitSet> parsePart(String part, int min, int max, String fieldName) {
        var slashIndex = part.indexOf('/');
        if (slashIndex >= 0) {
            return parseStep(part, slashIndex, min, max, fieldName);
        }
        var dashIndex = part.indexOf('-');
        if (dashIndex >= 0) {
            return parseRange(part, dashIndex, min, max, fieldName);
        }
        if ("*".equals(part)) {
            return Result.success(rangeBitSet(min, max, max));
        }
        return parseValue(part, min, max, fieldName).map(val -> singleBitSet(val, max));
    }

    private static Result<BitSet> parseStep(String part, int slashIndex, int min, int max, String fieldName) {
        var stepStr = part.substring(slashIndex + 1);
        var rangeStr = part.substring(0, slashIndex);
        return parseValue(stepStr, 1, max, fieldName + " step")
        .flatMap(step -> resolveStepRange(rangeStr, min, max, fieldName)
        .map(range -> fillStepped(range[0], range[1], step)));
    }

    private static Result<int[]> resolveStepRange(String rangeStr, int min, int max, String fieldName) {
        if ("*".equals(rangeStr)) {
            return Result.success(new int[]{min, max});
        }
        var dashIndex = rangeStr.indexOf('-');
        if (dashIndex >= 0) {
            return parseValue(rangeStr.substring(0, dashIndex), min, max, fieldName)
            .flatMap(start -> parseValue(rangeStr.substring(dashIndex + 1), min, max, fieldName)
            .flatMap(end -> validateRange(start, end, fieldName).map(_ -> new int[]{start, end})));
        }
        return parseValue(rangeStr, min, max, fieldName).map(start -> new int[]{start, max});
    }

    private static Result<BitSet> parseRange(String part, int dashIndex, int min, int max, String fieldName) {
        return parseValue(part.substring(0, dashIndex), min, max, fieldName)
        .flatMap(start -> parseValue(part.substring(dashIndex + 1), min, max, fieldName)
        .flatMap(end -> validateRange(start, end, fieldName).map(_ -> rangeBitSet(start, end, max))));
    }

    private static Result<Unit> validateRange(int start, int end, String fieldName) {
        if (start > end) {
            return Causes.cause("Invalid " + fieldName + " range: " + start + "-" + end)
                         .result();
        }
        return Result.unitResult();
    }

    private static BitSet rangeBitSet(int start, int end, int max) {
        var bits = new BitSet(max + 1);
        bits.set(start, end + 1);
        return bits;
    }

    private static BitSet singleBitSet(int value, int max) {
        var bits = new BitSet(max + 1);
        bits.set(value);
        return bits;
    }

    private static BitSet fillStepped(int start, int end, int step) {
        var bits = new BitSet(end + 1);
        for (int i = start; i <= end; i += step) {
            bits.set(i);
        }
        return bits;
    }

    private static Result<Integer> parseValue(String str, int min, int max, String fieldName) {
        try{
            int value = Integer.parseInt(str);
            if (value < min || value > max) {
                return Causes.cause("Invalid " + fieldName + " value " + value + ": must be " + min + "-" + max)
                             .result();
            }
            return Result.success(value);
        } catch (NumberFormatException _) {
            return Causes.cause("Invalid " + fieldName + " value: " + str)
                         .result();
        }
    }
}
