// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.http.handler.RouteIdentity;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// #1206: blueprint admission identifies a route from its PATH TEMPLATE, the runtime from the `Route` the generated code builds.
/// Both ask [RouteIdentity], and this pins that the template-side answer equals the runtime-side one over the shapes a
/// `routes.toml` can declare, built the way the slice generator builds them (`basePath` up to the first `{`, then one
/// `withPath` element per parameter and per literal, in order). If either side's derivation drifts from the other, a collision
/// slips past admission (or a warning is raised for routes the runtime tells apart) and this table is where it shows.
class RouteIdentityAgreementTest {
    private record Shape(String template, Route<?> runtimeRoute) {}

    private static Shape shape(String template, Route<?> route) {
        return new Shape(template, route);
    }

    static Stream<Shape> shapes() {
        return Stream.of(shape("/health", Route.<String>get("/health").withoutParameters().to(_ -> Promise.success("x")).asJson()),
                         shape("/orders/{id}", Route.<String>get("/orders/").withPath(aLong()).to(id -> Promise.success("x")).asJson()),
                         shape("/orders/{id}/items/{itemId}",
                               Route.<String>get("/orders/")
                                    .withPath(aLong(), spacer("items"), aLong())
                                    .to((id, _, item) -> Promise.success("x"))
                                    .asJson()),
                         shape("/items/{id}/image",
                               Route.<String>get("/items/").withPath(aLong(), spacer("image")).to((id, _) -> Promise.success("x")).asJson()),
                         shape("/{shortCode}", Route.<String>get("/").withPath(aLong()).to(code -> Promise.success("x")).asJson()),
                         shape("/files/x{a}", Route.<String>get("/files/x").withPath(aLong()).to(a -> Promise.success("x")).asJson()),
                         shape("/export/{id}/a/b",
                               Route.<String>get("/export/")
                                    .withPath(aLong(), spacer("a"), spacer("b"))
                                    .to((id, _, _) -> Promise.success("x"))
                                    .asJson()),
                         shape("/a/{id}/b/{x}",
                               Route.<String>get("/a/")
                                    .withPath(aLong(), spacer("b"), aLong())
                                    .to((id, _, x) -> Promise.success("x"))
                                    .asJson()),
                         shape("/a/{id}/{x}/b",
                               Route.<String>get("/a/")
                                    .withPath(aLong(), aLong(), spacer("b"))
                                    .to((id, x, _) -> Promise.success("x"))
                                    .asJson()));
    }

    @Test
    void admissionAndRuntime_agreeOnTheIdentity_overEveryShape() {
        var shapes = shapes().toList();

        assertThat(shapes).as("the table is not empty").hasSizeGreaterThan(5);
        shapes.forEach(shape -> {
            var runtime = RouteMetadataExtractor.routeMetadataExtractor()
                                                .extract(shape.runtimeRoute(), "org.example:slice:1.0.0")
                                                .getFirst();

            assertThat(RouteIdentity.ofTemplate("GET", shape.template()))
                .as("template %s", shape.template())
                .isEqualTo(RouteIdentity.ofDefinition(runtime));
        });
    }

    /// The routes that differ ONLY in where a literal sits are two routes to the runtime, so they are two to admission.
    @Test
    void routesDifferingOnlyInTheSpacerPosition_areDifferentIdentities() {
        assertThat(RouteIdentity.ofTemplate("GET", "/a/{id}/b/{x}")).isNotEqualTo(RouteIdentity.ofTemplate("GET", "/a/{id}/{x}/b"));
        assertThat(RouteIdentity.ofTemplate("GET", "/a/{id}/b/{x}").spacers()).isEqualTo(List.of("b"));
    }
}
