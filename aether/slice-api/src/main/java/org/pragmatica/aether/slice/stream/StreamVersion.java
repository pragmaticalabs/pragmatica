// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.resource.ResourceVersion.ResourceVersionError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;


/// Stream schema version.
///
/// RC1 format: MAJOR.MINOR.PATCH triplet of non-negative integers.
/// No pre-release tags, no build metadata, no `v` prefix. Forward-compatible
/// with full SemVer 2.0.0 if widened later.
///
/// Versions are immutable once registered. Schema bug fixes produce a new PATCH version.
///
/// This is the stream-flavored view of the resource-generic [ResourceVersion]: it keeps a
/// flat `(major, minor, patch)` record shape (so its `@Codec` wire form is unchanged) and its
/// own [StreamVersionError] vocabulary, but delegates all parsing and ordering to
/// [ResourceVersion]. It carries no rules of its own — [ResourceVersion] is the single
/// source of truth for the version grammar.
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
        return ResourceVersion.resourceVersion(major, minor, patch)
                              .map(StreamVersion::from)
                              .mapError(StreamVersion::mapVersionError);
    }

    public static Result<StreamVersion> streamVersion(String value) {
        return ResourceVersion.resourceVersion(value)
                              .map(StreamVersion::from)
                              .mapError(StreamVersion::mapVersionError);
    }

    /// Adapt the resource-generic version to the stream-flavored view.
    public static StreamVersion from(ResourceVersion version) {
        return new StreamVersion(version.major(), version.minor(), version.patch());
    }

    /// The resource-generic view of this version.
    public ResourceVersion toResourceVersion() {
        return new ResourceVersion(major, minor, patch);
    }

    /// Translate a shared [ResourceVersionError] into the stream-flavored vocabulary so that
    /// callers (and tests) observe [StreamVersionError] constants regardless of the delegate.
    /// Package-visible so [StreamAddress] can re-flavor version errors surfaced during parsing.
    static Cause mapVersionError(Cause cause) {
        return switch (cause) {
            case ResourceVersionError.General general -> switch (general) {
                case NULL_VALUE -> StreamVersionError.General.NULL_VALUE;
                case BLANK_VALUE -> StreamVersionError.General.BLANK_VALUE;
                case WRONG_FORMAT -> StreamVersionError.General.WRONG_FORMAT;
                case NON_NUMERIC_COMPONENT -> StreamVersionError.General.NON_NUMERIC_COMPONENT;
                case NEGATIVE_COMPONENT -> StreamVersionError.General.NEGATIVE_COMPONENT;
            };
            default -> cause;
        };
    }

    @Override public int compareTo(StreamVersion other) {
        return toResourceVersion().compareTo(other.toResourceVersion());
    }

    @Override public String toString() {
        return asString();
    }

    public String asString() {
        return major + "." + minor + "." + patch;
    }
}
