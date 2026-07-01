// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Pure compile-time coverage check for a slice's HTTP route table (#389, routes<->methods).
///
/// Mirrors [ErrorMappingValidator]: the check is side-effect free and returns a list of
/// Messager-ready [Issue]s, so the rule can be unit-tested without a compile-fail harness.
/// [org.pragmatica.jbct.slice.SliceProcessor] builds the [MethodDescriptor]s from the slice
/// interface's public methods and the routed handler names from the parsed [RouteConfig], calls
/// this, and reports each [Issue] via the Messager, choosing the severity.
///
/// The check is the FORWARD direction of the routes<->methods contract. The reverse direction (a
/// `[routes]` entry naming a method that does not exist) is already an ERROR in
/// [RouteSourceGenerator]; this adds the missing forward half:
///
///   - UNROUTED METHOD - a public, HTTP-eligible slice method that no `[routes]` entry maps to is
///     unreachable over HTTP and yields an [IssueKind#UNROUTED_METHOD]. Reactive handlers
///     (subscription / scheduled / stream / pg-notification / config-update) are invoked by their
///     own transport rather than routed and are exempt: the caller marks them `httpExempt`.
///
/// Severity is decided by the caller: an unrouted method is a WARNING by default so slices with
/// deliberately-internal methods keep building, and an ERROR only when the
/// `-Ajbct.routes.coverage.strict=true` processor option is set.
public sealed interface RouteCoverageValidator {

    /// A public slice method considered for route coverage.
    ///
    /// @param name       the method name, which is also the `[routes]` handler key it would be mapped by
    /// @param httpExempt whether the method is a reactive handler (subscription / scheduled / stream /
    ///                   pg-notification / config-update) invoked by its own transport rather than HTTP,
    ///                   and therefore not required to have a route
    record MethodDescriptor(String name, boolean httpExempt) {}

    /// The category of a coverage problem, which selects the diagnostic severity in the caller.
    enum IssueKind {
        /// A public, HTTP-eligible slice method has no `[routes]` entry (coverage gap).
        UNROUTED_METHOD
    }

    /// A single coverage problem with a Messager-ready message.
    ///
    /// @param kind    the category (selects severity)
    /// @param message the full diagnostic message, naming the method and the `routes.toml`
    record Issue(IssueKind kind, String message) {}

    /// Validate a slice's public methods against the set of routed handler names.
    ///
    /// @param methods            every public method of the routed slice, each flagged `httpExempt`
    ///                           when it is a reactive handler that does not require a route
    /// @param routedHandlerNames the handler names covered by `[routes]` entries (including version
    ///                           bindings for versioned slices)
    /// @param routesTomlPath     the resource path of the `routes.toml` to name in messages
    /// @return the unrouted-method problems in method order; empty when every HTTP-eligible method is
    ///         routed
    static List<Issue> validate(List<MethodDescriptor> methods,
                                Set<String> routedHandlerNames,
                                String routesTomlPath) {
        var issues = new ArrayList<Issue>();
        for (var method : methods) {
            if (!method.httpExempt() && !routedHandlerNames.contains(method.name())) {
                issues.add(new Issue(IssueKind.UNROUTED_METHOD, unroutedMessage(method.name(), routesTomlPath)));
            }
        }
        return List.copyOf(issues);
    }

    private static String unroutedMessage(String methodName, String routesTomlPath) {
        return "Unrouted slice method '" + methodName + "': no [routes] entry maps to it, so it is unreachable over HTTP. "
               + "Add a route in " + routesTomlPath + " (e.g. " + methodName + " = \"GET /" + methodName
               + "\"), or - if it is invoked by another transport - make it a reactive handler.";
    }

    record unused() implements RouteCoverageValidator {}
}
