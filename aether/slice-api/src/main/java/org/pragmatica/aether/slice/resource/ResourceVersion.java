// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.resource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Result.success;


/// Resource schema version — the single, resource-generic version type for all addressable
/// Aether resources (streams, pub/sub topics, ...).
///
/// RC1 format: MAJOR.MINOR.PATCH triplet of non-negative integers.
/// No pre-release tags, no build metadata, no `v` prefix. Forward-compatible
/// with full SemVer 2.0.0 if widened later.
///
/// Versions are immutable once registered. Schema bug fixes produce a new PATCH version.
///
/// This type owns the version grammar and ordering. Resource-flavored version types
/// (e.g. `StreamVersion`) delegate their parsing/ordering here while keeping their own
/// error vocabulary for backward-compatible diagnostics.
@Codec public record ResourceVersion(int major, int minor, int patch) implements Comparable<ResourceVersion> {
    public sealed interface ResourceVersionError extends Cause {
        enum General implements ResourceVersionError {
            NULL_VALUE("Resource version cannot be null"),
            BLANK_VALUE("Resource version cannot be blank"),
            WRONG_FORMAT("Resource version must match MAJOR.MINOR.PATCH"),
            NON_NUMERIC_COMPONENT("Resource version components must be non-negative integers"),
            NEGATIVE_COMPONENT("Resource version components must be non-negative");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements ResourceVersionError {
            @Override public String message() {
                return "";
            }
        }
    }

    public static Result<ResourceVersion> resourceVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            return ResourceVersionError.General.NEGATIVE_COMPONENT.result();
        }
        return success(new ResourceVersion(major, minor, patch));
    }

    public static Result<ResourceVersion> resourceVersion(String value) {
        if (value == null) {
            return ResourceVersionError.General.NULL_VALUE.result();
        }
        if (value.isBlank()) {
            return ResourceVersionError.General.BLANK_VALUE.result();
        }
        var parts = value.split("\\.", -1);
        if (parts.length != 3) {
            return ResourceVersionError.General.WRONG_FORMAT.result();
        }
        return parseComponent(parts[0])
                .flatMap(major -> parseComponent(parts[1])
                        .flatMap(minor -> parseComponent(parts[2])
                                .flatMap(patch -> resourceVersion(major, minor, patch))));
    }

    private static Result<Integer> parseComponent(String component) {
        return Number.parseInt(component)
                     .mapError(_ -> ResourceVersionError.General.NON_NUMERIC_COMPONENT);
    }

    @Override public int compareTo(ResourceVersion other) {
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
