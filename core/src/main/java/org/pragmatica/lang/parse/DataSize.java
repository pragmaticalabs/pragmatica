/*
 *  Copyright (c) 2020-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.lang.parse;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.success;

/// Human-friendly data size representation with parsing support.
///
/// Supports parsing size strings with the following units:
/// - `B` - bytes
/// - `KB` - kilobytes (1024 bytes)
/// - `MB` - megabytes (1024^2 bytes)
/// - `GB` - gigabytes (1024^3 bytes)
/// - `TB` - terabytes (1024^4 bytes)
///
/// Examples:
/// - "10MB" - 10 megabytes
/// - "512KB" - 512 kilobytes
/// - "1GB" - 1 gigabyte
/// - "16384" - 16384 bytes (plain number, no unit)
public sealed interface DataSize {

    long KB = 1024L;
    long MB = KB * 1024L;
    long GB = MB * 1024L;
    long TB = GB * 1024L;

    /// Parse a human-friendly data size string.
    ///
    /// @param text the size string to parse
    /// @return Result containing parsed DataSize or parsing error
    static Result<DataSize> dataSize(String text) {
        return Option.option(text)
                     .toResult(DataSizeError.NULL_INPUT)
                     .map(String::strip)
                     .flatMap(DataSize::parseNormalized);
    }

    /// Get the size in bytes.
    long bytes();

    /// Get the size as an int, clamped to Integer.MAX_VALUE.
    default int bytesAsInt() {
        return bytes() > Integer.MAX_VALUE
               ? Integer.MAX_VALUE
               : (int) bytes();
    }

    /// Sealed interface for DataSize parsing errors.
    sealed interface DataSizeError extends Cause {
        enum General implements DataSizeError {
            NULL_INPUT("Input cannot be null"),
            EMPTY_INPUT("Input cannot be empty"),
            NEGATIVE_VALUE("Data size cannot be negative");

            private final String message;

            General(String message) { this.message = message; }

            @Override
            public String message() { return message; }
        }

        DataSizeError NULL_INPUT = General.NULL_INPUT;
        DataSizeError EMPTY_INPUT = General.EMPTY_INPUT;
        DataSizeError NEGATIVE_VALUE = General.NEGATIVE_VALUE;

        record InvalidFormat(String input) implements DataSizeError {
            @Override
            public String message() { return "Invalid data size format: " + input; }
        }

        record InvalidValue(String value, String unit) implements DataSizeError {
            @Override
            public String message() { return "Invalid value '" + value + "' for unit '" + unit + "'"; }
        }
    }

    Pattern SIZE_PATTERN = Pattern.compile("^(\\d+)\\s*(TB|GB|MB|KB|B)?$", Pattern.CASE_INSENSITIVE);

    private static Result<DataSize> parseNormalized(String input) {
        if (input.isEmpty()) {
            return DataSizeError.EMPTY_INPUT.result();
        }

        var matcher = SIZE_PATTERN.matcher(input);

        if (!matcher.matches()) {
            return new DataSizeError.InvalidFormat(input).result();
        }

        var valueStr = matcher.group(1);
        var unit = Option.option(matcher.group(2)).or("B");

        return parseLongValue(valueStr, unit)
            .flatMap(DataSize::validateNonNegative);
    }

    private static Result<Long> parseLongValue(String valueStr, String unit) {
        return Result.lift1(Long::parseLong, valueStr)
                     .map(value -> toBytes(value, unit.toUpperCase()))
                     .mapError(_ -> new DataSizeError.InvalidValue(valueStr, unit));
    }

    private static long toBytes(long value, String unit) {
        return switch (unit) {
            case "TB" -> value * TB;
            case "GB" -> value * GB;
            case "MB" -> value * MB;
            case "KB" -> value * KB;
            default -> value;
        };
    }

    private static Result<DataSize> validateNonNegative(long bytes) {
        return bytes >= 0
               ? success(new DataSizeValue(bytes))
               : DataSizeError.NEGATIVE_VALUE.result();
    }

    record DataSizeValue(long bytes) implements DataSize {
        public DataSizeValue {
            assert bytes >= 0 : "DataSizeValue bytes must be non-negative";
        }

        @Override
        public String toString() {
            return formatSize(bytes);
        }

        private static String formatSize(long bytes) {
            if (bytes == 0) {
                return "0B";
            }
            if (bytes % TB == 0) {
                return (bytes / TB) + "TB";
            }
            if (bytes % GB == 0) {
                return (bytes / GB) + "GB";
            }
            if (bytes % MB == 0) {
                return (bytes / MB) + "MB";
            }
            if (bytes % KB == 0) {
                return (bytes / KB) + "KB";
            }
            return bytes + "B";
        }
    }

    record unused() implements DataSize {
        @Override
        public long bytes() {
            return 0L;
        }
    }
}
