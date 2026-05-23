// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ClusterTasksCommandFilterTest {
    private static final String SAMPLE_RESPONSE =
            "{\"assignments\":["
            + "{\"group\":\"METRICS\",\"assignedTo\":\"node-2\",\"assignedAt\":\"2026-04-04T10:30:00Z\",\"status\":\"ACTIVE\",\"failureReason\":\"\"},"
            + "{\"group\":\"SCALING\",\"assignedTo\":\"node-3\",\"assignedAt\":\"2026-04-04T10:30:00Z\",\"status\":\"ACTIVE\",\"failureReason\":\"\"},"
            + "{\"group\":\"DEPLOYMENT\",\"assignedTo\":\"node-1\",\"assignedAt\":\"2026-04-04T10:30:01Z\",\"status\":\"ACTIVE\",\"failureReason\":\"\"}"
            + "]}";

    @Nested
    class HappyPath {
        @Test
        void filterByGroup_metricsRecord_extractedAndReWrapped() {
            var result = ClusterTasksCommand.filterByGroup(SAMPLE_RESPONSE, "METRICS");
            assertTrue(result.isSuccess(), "filter should succeed for known group");
            result.onSuccess(json -> {
                assertTrue(json.startsWith("{\"assignments\":["), "envelope preserved");
                assertTrue(json.endsWith("]}"), "envelope closes correctly");
                assertTrue(json.contains("\"group\":\"METRICS\""), "matched record present");
                assertTrue(json.contains("\"assignedTo\":\"node-2\""), "assignedTo carried over");
                assertTrue(json.contains("\"status\":\"ACTIVE\""), "status carried over");
                // Other groups must NOT bleed into the filtered record.
                assertEquals(1,
                             countOccurrences(json, "\"group\":"),
                             "exactly one group entry in filtered envelope");
            });
        }

        @Test
        void filterByGroup_scalingMiddleRecord_extractsExactlyThatRecord() {
            var result = ClusterTasksCommand.filterByGroup(SAMPLE_RESPONSE, "SCALING");
            assertTrue(result.isSuccess());
            result.onSuccess(json -> {
                assertTrue(json.contains("\"group\":\"SCALING\""));
                assertEquals(1, countOccurrences(json, "\"group\":"));
            });
        }

        @Test
        void filterByGroup_deploymentLastRecord_extractsCorrectly() {
            var result = ClusterTasksCommand.filterByGroup(SAMPLE_RESPONSE, "DEPLOYMENT");
            assertTrue(result.isSuccess());
            result.onSuccess(json -> {
                assertTrue(json.contains("\"group\":\"DEPLOYMENT\""));
                assertEquals(1, countOccurrences(json, "\"group\":"));
            });
        }
    }

    @Nested
    class FailureCases {
        @Test
        void filterByGroup_unknownGroup_returnsGroupNotFoundError() {
            var result = ClusterTasksCommand.filterByGroup(SAMPLE_RESPONSE, "NOPE");
            assertTrue(result.isFailure());
            result.onFailure(cause -> assertEquals(ClusterTasksCommand.TasksError.GROUP_NOT_FOUND, cause));
        }

        @Test
        void filterByGroup_emptyAssignments_returnsGroupNotFoundError() {
            var empty = "{\"assignments\":[]}";
            var result = ClusterTasksCommand.filterByGroup(empty, "METRICS");
            assertTrue(result.isFailure());
            result.onFailure(cause -> assertEquals(ClusterTasksCommand.TasksError.GROUP_NOT_FOUND, cause));
        }
    }

    private static int countOccurrences(String text, String needle) {
        var count = 0;
        var idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
