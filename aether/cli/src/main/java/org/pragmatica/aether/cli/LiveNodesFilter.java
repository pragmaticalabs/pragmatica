// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;


/// Client-side post-processing for the `GET /api/nodes/live` document consumed by
/// `aether nodes live` / `aether nodes resolve`. Kept out of `AetherCli` so the JSON
/// tree manipulation has a single, testable home.
public sealed interface LiveNodesFilter {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// Filter the `nodes` array of a `LiveNodesResponse` to entries with `swimAlive=true`
    /// and recompute `liveCount`/`zombieCount`. Returns the original JSON unchanged when it
    /// cannot be parsed (the caller's downstream formatter then surfaces the parse problem).
    static String onlyAlive(String json) {
        return MAPPER.readTree(json)
                     .map(LiveNodesFilter::rebuildAliveOnly)
                     .flatMap(MAPPER::writeAsString)
                     .or(json);
    }

    /// Extract the boolean `reachable` field from a `NodeEndpointResponse`. Absent / unparseable
    /// fields read as `false` — a node we cannot confirm reachable is treated as not reachable.
    static boolean isReachable(String json) {
        return MAPPER.readTree(json)
                     .map(node -> node.path("reachable").asBoolean(false))
                     .or(false);
    }

    private static ObjectNode rebuildAliveOnly(JsonNode root) {
        var alive = collectAlive(root.path("nodes"));
        var result = JsonNodeFactory.instance.objectNode();
        var nodes = result.putArray("nodes");

        alive.forEach(nodes::add);
        result.put("liveCount", alive.size());
        result.put("zombieCount", 0);

        return result;
    }

    private static List<JsonNode> collectAlive(JsonNode nodesArray) {
        var alive = new ArrayList<JsonNode>();

        if (nodesArray instanceof ArrayNode array) {
            array.forEach(entry -> retainIfAlive(alive, entry));
        }

        return alive;
    }

    private static void retainIfAlive(List<JsonNode> alive, JsonNode entry) {
        if (entry.path("swimAlive").asBoolean(false)) {
            alive.add(entry);
        }
    }

    record unused() implements LiveNodesFilter {}
}
