// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.slice.routing.RouteCoverageValidator.IssueKind.UNROUTED_METHOD;

/// Unit tests for the pure route-coverage check (#389, routes<->methods forward direction).
/// Mirrors the pure-validator style of [ErrorMappingValidatorTest]: descriptors and routed handler
/// names are built directly, no compile-fail harness needed.
class RouteCoverageValidatorTest {

    private static final String PATH = "com/acme/order/routes.toml";

    private static RouteCoverageValidator.MethodDescriptor routable(String name) {
        return new RouteCoverageValidator.MethodDescriptor(name, false);
    }

    private static RouteCoverageValidator.MethodDescriptor exempt(String name) {
        return new RouteCoverageValidator.MethodDescriptor(name, true);
    }

    private static List<String> messagesOf(List<RouteCoverageValidator.Issue> issues) {
        return issues.stream()
                     .filter(issue -> issue.kind() == UNROUTED_METHOD)
                     .map(RouteCoverageValidator.Issue::message)
                     .toList();
    }

    @Nested
    class Coverage {

        @Test
        void validate_flagsMethodWithNoRoute() {
            var methods = List.of(routable("getOrder"), routable("createOrder"));
            var routed = Set.of("getOrder");

            var issues = RouteCoverageValidator.validate(methods, routed, PATH);

            var unrouted = messagesOf(issues);
            assertThat(unrouted).hasSize(1);
            assertThat(unrouted.getFirst()).contains("createOrder");
            assertThat(unrouted.getFirst()).contains(PATH);
        }

        @Test
        void validate_passesWhenEveryMethodIsRouted() {
            var methods = List.of(routable("getOrder"), routable("createOrder"));
            var routed = Set.of("getOrder", "createOrder");

            var issues = RouteCoverageValidator.validate(methods, routed, PATH);

            assertThat(issues).isEmpty();
        }

        @Test
        void validate_flagsEveryUnroutedMethod() {
            var methods = List.of(routable("getOrder"), routable("createOrder"), routable("deleteOrder"));
            var routed = Set.<String>of();

            var issues = RouteCoverageValidator.validate(methods, routed, PATH);

            assertThat(messagesOf(issues)).hasSize(3);
        }
    }

    @Nested
    class ReactiveExemptions {

        @Test
        void validate_doesNotFlagExemptReactiveHandler() {
            // A subscription/scheduled/stream handler is invoked by its own transport, never routed.
            var methods = List.of(routable("getOrder"), exempt("onOrderPlaced"));
            var routed = Set.of("getOrder");

            var issues = RouteCoverageValidator.validate(methods, routed, PATH);

            assertThat(issues).isEmpty();
        }

        @Test
        void validate_flagsRoutableMethodEvenWhenExemptSiblingsPresent() {
            var methods = List.of(routable("getOrder"), routable("createOrder"), exempt("onOrderPlaced"));
            var routed = Set.of("getOrder");

            var issues = RouteCoverageValidator.validate(methods, routed, PATH);

            var unrouted = messagesOf(issues);
            assertThat(unrouted).hasSize(1);
            assertThat(unrouted.getFirst()).contains("createOrder");
        }
    }

    @Nested
    class VersionedBindings {

        @Test
        void validate_treatsVersionBoundMethodAsRouted() {
            // Versioned slices route getV1/getV2 via version bindings; the caller folds those method
            // names into the routed set, so they must not be flagged as unrouted.
            var methods = List.of(routable("getV1"), routable("getV2"));
            var routed = Set.of("getV1", "getV2");

            var issues = RouteCoverageValidator.validate(methods, routed, PATH);

            assertThat(issues).isEmpty();
        }
    }
}
