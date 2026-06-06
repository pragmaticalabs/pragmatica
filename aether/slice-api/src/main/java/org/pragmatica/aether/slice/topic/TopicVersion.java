// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.resource.ResourceVersion.ResourceVersionError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;


/// Pub/sub topic schema version.
///
/// RC1 format: MAJOR.MINOR.PATCH triplet of non-negative integers.
/// No pre-release tags, no build metadata, no `v` prefix. Forward-compatible
/// with full SemVer 2.0.0 if widened later.
///
/// Versions are immutable once registered. Schema bug fixes produce a new PATCH version.
///
/// This is the topic-flavored view of the resource-generic [ResourceVersion]: it keeps a
/// flat `(major, minor, patch)` record shape (so its `@Codec` wire form is unchanged) and its
/// own [TopicVersionError] vocabulary, but delegates all parsing and ordering to
/// [ResourceVersion]. It carries no rules of its own — [ResourceVersion] is the single
/// source of truth for the version grammar.
@Codec public record TopicVersion(int major, int minor, int patch) implements Comparable<TopicVersion> {
    public sealed interface TopicVersionError extends Cause {
        enum General implements TopicVersionError {
            NULL_VALUE("Topic version cannot be null"),
            BLANK_VALUE("Topic version cannot be blank"),
            WRONG_FORMAT("Topic version must match MAJOR.MINOR.PATCH"),
            NON_NUMERIC_COMPONENT("Topic version components must be non-negative integers"),
            NEGATIVE_COMPONENT("Topic version components must be non-negative");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements TopicVersionError {
            @Override public String message() {
                return "";
            }
        }
    }

    public static Result<TopicVersion> topicVersion(int major, int minor, int patch) {
        return ResourceVersion.resourceVersion(major, minor, patch)
                              .map(TopicVersion::from)
                              .mapError(TopicVersion::mapVersionError);
    }

    public static Result<TopicVersion> topicVersion(String value) {
        return ResourceVersion.resourceVersion(value)
                              .map(TopicVersion::from)
                              .mapError(TopicVersion::mapVersionError);
    }

    /// Default topic version applied to legacy / un-namespaced topic declarations that omit an
    /// explicit version. Mirrors the stream default — a bare topic name resolves to `1.0.0`.
    public static TopicVersion defaultVersion() {
        return new TopicVersion(1, 0, 0);
    }

    /// Adapt the resource-generic version to the topic-flavored view.
    public static TopicVersion from(ResourceVersion version) {
        return new TopicVersion(version.major(), version.minor(), version.patch());
    }

    /// The resource-generic view of this version.
    public ResourceVersion toResourceVersion() {
        return new ResourceVersion(major, minor, patch);
    }

    /// Translate a shared [ResourceVersionError] into the topic-flavored vocabulary so that
    /// callers (and tests) observe [TopicVersionError] constants regardless of the delegate.
    /// Package-visible so [TopicAddress] can re-flavor version errors surfaced during parsing.
    static Cause mapVersionError(Cause cause) {
        return switch (cause) {
            case ResourceVersionError.General general -> switch (general) {
                case NULL_VALUE -> TopicVersionError.General.NULL_VALUE;
                case BLANK_VALUE -> TopicVersionError.General.BLANK_VALUE;
                case WRONG_FORMAT -> TopicVersionError.General.WRONG_FORMAT;
                case NON_NUMERIC_COMPONENT -> TopicVersionError.General.NON_NUMERIC_COMPONENT;
                case NEGATIVE_COMPONENT -> TopicVersionError.General.NEGATIVE_COMPONENT;
            };
            default -> cause;
        };
    }

    @Override public int compareTo(TopicVersion other) {
        return toResourceVersion().compareTo(other.toResourceVersion());
    }

    @Override public String toString() {
        return asString();
    }

    public String asString() {
        return major + "." + minor + "." + patch;
    }
}
