// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;

import java.util.List;


public sealed interface ExpanderError extends Cause {
    record ArtifactMismatch(Artifact requested, Artifact declared) implements ExpanderError {
        public static ArtifactMismatch artifactMismatch(Artifact requested, Artifact declared) {
            return new ArtifactMismatch(requested, declared);
        }

        @Override public String message() {
            return "Artifact mismatch: requested " + requested.asString() + " but JAR manifest declares " + declared.asString();
        }
    }

    record OrphanPublishers(List<String> topics) implements ExpanderError {
        public static OrphanPublishers orphanPublishers(List<String> topics) {
            return new OrphanPublishers(List.copyOf(topics));
        }

        @Override public String message() {
            return "Publisher topics with no subscribers in blueprint: " + String.join(", ", topics);
        }
    }

    /// One or more topic declarations carry an invalid address: a malformed `namespace:topic:version`
    /// form, an invalid topic name, or the reserved `system` namespace used by an app topic.
    record InvalidTopicAddresses(List<String> diagnostics) implements ExpanderError {
        public static InvalidTopicAddresses invalidTopicAddresses(List<String> diagnostics) {
            return new InvalidTopicAddresses(List.copyOf(diagnostics));
        }

        @Override public String message() {
            return "Invalid topic addresses in blueprint: " + String.join("; ", diagnostics);
        }
    }

    record unused() implements ExpanderError {
        @Override public String message() {
            return "unused";
        }
    }
}
