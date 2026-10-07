// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;


public sealed interface ExpanderError extends Cause {
    record ArtifactMismatch(Artifact requested, Artifact declared) implements ExpanderError {
        public static ArtifactMismatch artifactMismatch(Artifact requested, Artifact declared) {
            return new ArtifactMismatch(requested, declared);
        }

        @Override
        public String message() {
            return "Artifact mismatch: requested " + requested.asString()
                 + " but JAR manifest declares " + declared.asString();
        }
    }

    record OrphanPublishers(List<String> topics) implements ExpanderError {
        public static OrphanPublishers orphanPublishers(List<String> topics) {
            return new OrphanPublishers(List.copyOf(topics));
        }

        @Override
        public String message() {
            return "Publisher topics with no subscribers in blueprint: " + String.join(", ", topics);
        }
    }

    /// #1206: two slices of DIFFERENT artifacts declare the same HTTP route (same method, same path template). The runtime
    /// resolves such a collision deterministically (the lexically smaller coordinate serves, `HttpRoutePublisher`), so the
    /// other slice would activate, report healthy and serve nothing. Refused at admission instead, naming both slices and the
    /// route.
    record RoutePrefixCollisions(List<RouteCollision> collisions) implements ExpanderError {
        public static RoutePrefixCollisions routePrefixCollisions(List<RouteCollision> collisions) {
            return new RoutePrefixCollisions(List.copyOf(collisions));
        }

        @Override
        public String message() {
            return "HTTP route collision: " + collisions.stream()
                                                        .map(RouteCollision::describe)
                                                        .collect(java.util.stream.Collectors.joining("; "));
        }
    }

    /// One colliding route: `method` and the `path` template (path parameters normalized) declared by BOTH `first` and
    /// `second`, which are the lexically ordered artifact coordinates.
    record RouteCollision(String method, String path, String first, String second) {
        public static RouteCollision routeCollision(String method, String path, String first, String second) {
            return new RouteCollision(method, path, first, second);
        }

        public String describe() {
            return method
                 + " " + path
                 + " is declared by both " + first
                 + " and " + second
                 + " (one of them would activate, report healthy and serve nothing)";
        }
    }

    /// One or more topic declarations carry an invalid address: a malformed `namespace:topic:version`
    /// form, an invalid topic name, or the reserved `system` namespace used by an app topic.
    record InvalidTopicAddresses(List<String> diagnostics) implements ExpanderError {
        public static InvalidTopicAddresses invalidTopicAddresses(List<String> diagnostics) {
            return new InvalidTopicAddresses(List.copyOf(diagnostics));
        }

        @Override
        public String message() {
            return "Invalid topic addresses in blueprint: " + String.join("; ", diagnostics);
        }
    }

    record unused() implements ExpanderError {
        @Override
        public String message() {
            return "unused";
        }
    }
}
