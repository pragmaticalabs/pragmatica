// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.json.JsonMapper;

import tools.jackson.databind.JsonNode;


/// #1336 — the `rejectedStreamBindings` a blueprint publish answers with (`field`/`rule`/`message` each: a
/// `[streams.X]` declaration the cluster accepted the blueprint WITHOUT binding), rendered for the TABLE
/// output of `aether blueprints publish`, whose success line alone would hide them. `deploy` and `apply`
/// print the whole JSON body and need nothing here. The key is absent when nothing was rejected.
public sealed interface RejectedStreamBindings {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// One line per rejected binding, in response order; empty when the key is absent or the body does
    /// not parse (the caller's formatter has already surfaced a parse problem by then).
    static List<String> lines(String json) {
        return MAPPER.readTree(json)
                     .map(root -> root.path("rejectedStreamBindings"))
                     .map(RejectedStreamBindings::render)
                     .or(List.of());
    }

    private static List<String> render(JsonNode bindings) {
        var lines = new ArrayList<String>();

        bindings.forEach(binding -> lines.add(renderOne(binding)));

        return List.copyOf(lines);
    }

    private static String renderOne(JsonNode binding) {
        return "Rejected stream binding " + binding.path("field")
                                                   .asText("?")
             + " [" + binding.path("rule")
                             .asText("?")
             + "]: " + binding.path("message")
                              .asText("");
    }

    record unused() implements RejectedStreamBindings {}
}
