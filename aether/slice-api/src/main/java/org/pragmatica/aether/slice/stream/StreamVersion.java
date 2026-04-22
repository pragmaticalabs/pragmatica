// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Result.success;


/// Stream schema version.
///
/// RC1 format: MAJOR.MINOR.PATCH triplet of non-negative integers.
/// No pre-release tags, no build metadata, no `v` prefix. Forward-compatible
/// with full SemVer 2.0.0 if widened later.
///
/// Versions are immutable once registered. Schema bug fixes produce a new PATCH version.
@Codec public record StreamVersion(int major, int minor, int patch) implements Comparable<StreamVersion> {
    public sealed interface StreamVersionError extends Cause {
        enum General implements StreamVersionError {
            NULL_VALUE("Stream version cannot be null"),
            BLANK_VALUE("Stream version cannot be blank"),
            WRONG_FORMAT("Stream version must match MAJOR.MINOR.PATCH"),
            NON_NUMERIC_COMPONENT("Stream version components must be non-negative integers"),
            NEGATIVE_COMPONENT("Stream version components must be non-negative");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements StreamVersionError {
            @Override public String message() {
                return "";
            }
        }
    }

    public static Result<StreamVersion> streamVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            return StreamVersionError.General.NEGATIVE_COMPONENT.result();
        }
        return success(new StreamVersion(major, minor, patch));
    }

    public static Result<StreamVersion> streamVersion(String value) {
        if (value == null) {
            return StreamVersionError.General.NULL_VALUE.result();
        }
        if (value.isBlank()) {
            return StreamVersionError.General.BLANK_VALUE.result();
        }
        var parts = value.split("\\.", -1);
        if (parts.length != 3) {
            return StreamVersionError.General.WRONG_FORMAT.result();
        }
        try {
            var major = Integer.parseInt(parts[0]);
            var minor = Integer.parseInt(parts[1]);
            var patch = Integer.parseInt(parts[2]);
            return streamVersion(major, minor, patch);
        } catch (NumberFormatException _) {
            return StreamVersionError.General.NON_NUMERIC_COMPONENT.result();
        }
    }

    @Override public int compareTo(StreamVersion other) {
        var majorDiff = Integer.compare(major, other.major);
        if (majorDiff != 0) {
            return majorDiff;
        }
        var minorDiff = Integer.compare(minor, other.minor);
        if (minorDiff != 0) {
            return minorDiff;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override public String toString() {
        return asString();
    }

    public String asString() {
        return major + "." + minor + "." + patch;
    }
}
