// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.management.route.ManagementRoute.AB_TEST_CONCLUDE;
import static org.pragmatica.aether.management.route.ManagementRoute.AB_TEST_METRICS;
import static org.pragmatica.aether.management.route.ManagementRoute.BLUEPRINT_STATUS;
import static org.pragmatica.aether.management.route.ManagementRoute.DEPLOY_COMPLETE;
import static org.pragmatica.aether.management.route.ManagementRoute.DEPLOY_PROMOTE;
import static org.pragmatica.aether.management.route.ManagementRoute.DEPLOY_ROLLBACK;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_PAUSE;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_RESUME;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_STATE;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_TRIGGER;
import static org.pragmatica.aether.management.route.ManagementRoute.STORAGE_SNAPSHOT;
import static org.pragmatica.aether.management.route.ManagementRoute.STREAM_PUBLISH;
import static org.pragmatica.aether.management.route.ManagementRoute.STREAM_READ;
import static org.pragmatica.aether.management.route.PathToken.param;
import static org.pragmatica.aether.management.route.PathToken.spacer;


/// Verifies the routes from the plan's "Path Rearrangement" table have the shape their migration
/// gave them: fixed prefix segments first, then path parameters at the tail -- except where a route
/// has since moved to the identity-first catalog shape, whose params are followed by a literal
/// (see [#streamRead_identityParamsThenReadLiteralThenPartition]). The one invariant that still
/// holds for EVERY route is that `prefix()` never contains placeholder syntax
/// (see [#allRoutes_obeyTailParamsInvariant]).
class PathRearrangementTest {

    @Test
    void deployPromote_paramAtTail() {
        assertThat(DEPLOY_PROMOTE.prefix()).isEqualTo("/api/v1/deploy/promote");
        assertThat(DEPLOY_PROMOTE.paramNames()).containsExactly("id");
    }

    @Test
    void deployRollback_paramAtTail() {
        assertThat(DEPLOY_ROLLBACK.prefix()).isEqualTo("/api/v1/deploy/rollback");
        assertThat(DEPLOY_ROLLBACK.paramNames()).containsExactly("id");
    }

    @Test
    void deployComplete_paramAtTail() {
        assertThat(DEPLOY_COMPLETE.prefix()).isEqualTo("/api/v1/deploy/complete");
        assertThat(DEPLOY_COMPLETE.paramNames()).containsExactly("id");
    }

    @Test
    void abTestMetrics_paramAtTail() {
        assertThat(AB_TEST_METRICS.prefix()).isEqualTo("/api/v1/ab-tests/metrics");
        assertThat(AB_TEST_METRICS.paramNames()).containsExactly("id");
    }

    @Test
    void abTestConclude_paramAtTail() {
        assertThat(AB_TEST_CONCLUDE.prefix()).isEqualTo("/api/v1/ab-tests/conclude");
        assertThat(AB_TEST_CONCLUDE.paramNames()).containsExactly("id");
    }

    @Test
    void blueprintStatus_paramAtTail() {
        assertThat(BLUEPRINT_STATUS.prefix()).isEqualTo("/api/v1/blueprints/status");
        assertThat(BLUEPRINT_STATUS.paramNames()).containsExactly("id");
    }

    @Test
    void streamPublish_paramAtTail() {
        assertThat(STREAM_PUBLISH.prefix()).isEqualTo("/api/v1/streams/publish");
        assertThat(STREAM_PUBLISH.paramNames()).containsExactly("name");
    }

    /// STREAM_READ migrated to the identity-first catalog shape (management-api-versioning-spec.md
    /// Sections 3.2/3.3): it is the one route in this table whose params are NO LONGER all at the
    /// tail, so it is pinned by exact token layout rather than by prefix+paramNames -- that pair
    /// cannot express a literal sitting between params, which is precisely the property at stake.
    /// `prefix()` is now the leading literal run only ("/api/v1/streams", stopping at `namespace`);
    /// "read" is an interior spacer between `version` and `partition`, not part of the prefix and
    /// not trailing.
    @Test
    void streamRead_identityParamsThenReadLiteralThenPartition() {
        assertThat(STREAM_READ.prefix()).isEqualTo("/api/v1/streams");
        assertThat(STREAM_READ.paramNames()).containsExactly("namespace", "stream", "version", "partition");
        assertThat(STREAM_READ.tokens()).containsExactly(spacer("api"),
                                                         spacer("v1"),
                                                         spacer("streams"),
                                                         param("namespace"),
                                                         param("stream"),
                                                         param("version"),
                                                         spacer("read"),
                                                         param("partition"));
    }

    @Test
    void storageSnapshot_paramAtTail() {
        assertThat(STORAGE_SNAPSHOT.prefix()).isEqualTo("/api/v1/storage/snapshot");
        assertThat(STORAGE_SNAPSHOT.paramNames()).containsExactly("name");
    }

    @Test
    void scheduledTaskState_paramsAtTail() {
        assertThat(SCHEDULED_TASK_STATE.prefix()).isEqualTo("/api/v1/scheduled-tasks/state");
        assertThat(SCHEDULED_TASK_STATE.paramNames()).containsExactly("section", "artifact", "methodName");
    }

    @Test
    void scheduledTaskActions_splitIntoThreeRoutes() {
        assertThat(SCHEDULED_TASK_PAUSE.prefix()).isEqualTo("/api/v1/scheduled-tasks/pause");
        assertThat(SCHEDULED_TASK_RESUME.prefix()).isEqualTo("/api/v1/scheduled-tasks/resume");
        assertThat(SCHEDULED_TASK_TRIGGER.prefix()).isEqualTo("/api/v1/scheduled-tasks/trigger");
        assertThat(SCHEDULED_TASK_PAUSE.paramNames()).containsExactly("section", "artifact", "methodName");
        assertThat(SCHEDULED_TASK_RESUME.paramNames()).containsExactly("section", "artifact", "methodName");
        assertThat(SCHEDULED_TASK_TRIGGER.paramNames()).containsExactly("section", "artifact", "methodName");
    }

    @Test
    void allRoutes_obeyTailParamsInvariant() {
        // No prefix may itself contain placeholder syntax (e.g. {id}).
        for (var r : ManagementRoute.values()) {
            assertThat(r.prefix())
                    .as("Route %s prefix must not contain '{' (parameters belong at tail)", r.name())
                    .doesNotContain("{");
        }
    }
}
