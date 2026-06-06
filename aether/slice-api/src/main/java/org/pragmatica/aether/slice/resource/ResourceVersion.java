// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.resource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.serialization.Codec;


/// Resource schema version — the single, resource-generic version type for all addressable
/// Aether resources (streams, pub/sub topics, ...).
///
/// RC1 format: MAJOR.MINOR.PATCH triplet of non-negative integers.
/// No pre-release tags, no build metadata, no `v` prefix. Forward-compatible
/// with full SemVer 2.0.0 if widened later.
///
/// Versions are immutable once registered. Schema bug fixes produce a new PATCH version.
///
/// This type owns the version grammar and ordering. It is the single, uniform version type
/// reused directly by streams, topics, and any future addressable resource.
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
        return Verify.ensure(major, Is::nonNegative, ResourceVersionError.General.NEGATIVE_COMPONENT)
                     .all(_ -> Verify.ensure(minor, Is::nonNegative, ResourceVersionError.General.NEGATIVE_COMPONENT),
                          _ -> Verify.ensure(patch, Is::nonNegative, ResourceVersionError.General.NEGATIVE_COMPONENT))
                     .map((_, _) -> new ResourceVersion(major, minor, patch));
    }

    /// Default resource version applied to legacy / un-namespaced declarations that omit an
    /// explicit version. A bare name resolves to `1.0.0`.
    public static ResourceVersion defaultVersion() {
        return new ResourceVersion(1, 0, 0);
    }

    public static Result<ResourceVersion> resourceVersion(String value) {
        return Verify.ensure(value, Is::notNull, ResourceVersionError.General.NULL_VALUE)
                     .flatMap(notNull -> Verify.ensure(notNull, Is::notBlank, ResourceVersionError.General.BLANK_VALUE))
                     .map(blank -> blank.split("\\.", -1))
                     .flatMap(parts -> Verify.ensure(parts, p -> p.length == 3, ResourceVersionError.General.WRONG_FORMAT))
                     .all(parts -> parseComponent(parts[0]),
                          parts -> parseComponent(parts[1]),
                          parts -> parseComponent(parts[2]))
                     .flatMap(ResourceVersion::resourceVersion);
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
