// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;


/// Instance accounting behind `aether scale --wait` (#522 sibling sweep). Kept out of
/// `AetherCli` so the JSON reading has a single, testable home.
///
/// The gate counts ACTIVE instances of the scaled artifact in the `GET /api/slices`
/// document. It previously counted occurrences of the artifact COORDINATES in the raw
/// response text — but `ClusterSlicesResponse` names each artifact exactly once, and its
/// per-instance entries carry only `nodeId`/`state`, so that count was pinned at 1 no
/// matter how many instances were actually running: `--wait` for two or more instances
/// timed out on a scale that had in fact succeeded, and a scale down to one reported
/// success immediately without reading anything. This is the same defect as #522 —
/// a status synthesized from a substring rather than read from the status field.
public sealed interface ScaleWait {
    /// Distinct from a real count of zero: the slice list could not be read at all, so no
    /// target can be considered met and the caller keeps waiting until its deadline.
    int UNREADABLE = -1;
    String ACTIVE = "ACTIVE";

    /// Number of instances of `artifact` currently in state ACTIVE, or [#UNREADABLE] when
    /// the response is an error envelope or cannot be parsed.
    static int activeInstances(String slicesResponse, String artifact) {
        return OutputFormatter.isErrorResponse(slicesResponse)
               ? UNREADABLE
               : OutputFormatter.MAPPER.readTree(slicesResponse)
                                       .map(root -> countActive(root, artifact))
                                       .or(UNREADABLE);
    }

    /// Renders a reading for display. [#UNREADABLE] is not a measurement — printing it as
    /// `-1` would show a sentinel where the operator reads a count, so an unreadable slice
    /// list says so instead of naming a number nothing measured.
    static String describe(int activeInstances) {
        return activeInstances == UNREADABLE
               ? "unknown"
               : Integer.toString(activeInstances);
    }

    private static int countActive(JsonNode root, String artifact) {
        var active = new ArrayList<JsonNode>();

        collectSlices(root.path("slices"), artifact).forEach(slice -> collectActive(active, slice.path("instances")));

        return active.size();
    }

    /// Matches the exact deployed coordinates. During a version migration the list can carry
    /// several versions of the same slice; counting only the requested one keeps `--wait`
    /// answering the question the operator actually asked.
    private static List<JsonNode> collectSlices(JsonNode slicesArray, String artifact) {
        var matching = new ArrayList<JsonNode>();

        if (slicesArray instanceof ArrayNode array) {
            array.forEach(slice -> retainIfArtifact(matching, slice, artifact));
        }

        return matching;
    }

    private static void retainIfArtifact(List<JsonNode> matching, JsonNode slice, String artifact) {
        if (artifact.equals(slice.path("artifact").asText(""))) {
            matching.add(slice);
        }
    }

    private static void collectActive(List<JsonNode> active, JsonNode instancesArray) {
        if (instancesArray instanceof ArrayNode array) {
            array.forEach(instance -> retainIfActive(active, instance));
        }
    }

    private static void retainIfActive(List<JsonNode> active, JsonNode instance) {
        if (ACTIVE.equals(instance.path("state").asText(""))) {
            active.add(instance);
        }
    }

    record unused() implements ScaleWait {}
}
