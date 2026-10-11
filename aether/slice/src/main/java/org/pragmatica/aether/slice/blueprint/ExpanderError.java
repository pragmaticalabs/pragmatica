// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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

    /// #1206: the blueprint being published declares a route that an ALREADY-STORED blueprint's slice declares too. The request is
    /// well-formed; it conflicts with the current state of the cluster, so the caller sees a conflict (409), naming the stored
    /// blueprint and its slice.
    record RoutePrefixConflictsWithStored(List<RouteCollision> conflicts) implements ExpanderError {
        public static RoutePrefixConflictsWithStored routePrefixConflictsWithStored(List<RouteCollision> conflicts) {
            return new RoutePrefixConflictsWithStored(List.copyOf(conflicts));
        }

        @Override
        public String message() {
            return "HTTP route conflicts with a stored blueprint: " + conflicts.stream()
                                                                               .map(RouteCollision::describe)
                                                                               .collect(java.util.stream.Collectors.joining("; "));
        }
    }

    /// One colliding route: `method` and the `path` template (path parameters normalized) declared by BOTH `first` and
    /// `second`, which are the lexically ordered artifact coordinates.
    record RouteCollision(String method,
                          String path,
                          String otherPath,
                          String first,
                          String second,
                          String storedBlueprint) {
        /// Two slices of the blueprint being published; `path` and `otherPath` are the templates they declare.
        public static RouteCollision routeCollision(String method,
                                                    String path,
                                                    String otherPath,
                                                    String first,
                                                    String second) {
            return new RouteCollision(method, path, otherPath, first, second, "");
        }

        /// `first` is the slice of the blueprint being published, `second` the slice of the ALREADY-STORED blueprint
        /// `storedBlueprint`.
        public static RouteCollision conflictWithStored(String method,
                                                        String path,
                                                        String otherPath,
                                                        String first,
                                                        String second,
                                                        String storedBlueprint) {
            return new RouteCollision(method, path, otherPath, first, second, storedBlueprint);
        }

        private String templates() {
            return path.equals(otherPath)
                   ? method + " " + path
                   : method + " " + path + " and " + method + " " + otherPath + " (the same route)";
        }

        public String describe() {
            if (!storedBlueprint.isEmpty()) {
                return templates()
                     + " is declared by slice " + first
                     + " of this blueprint and by slice " + second
                     + " of the stored blueprint " + storedBlueprint
                     + " (one of them would activate, report healthy and serve nothing)";
            }

            return templates()
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
