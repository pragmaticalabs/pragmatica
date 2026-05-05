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
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_TASK_REASSIGN;
import static org.pragmatica.aether.management.route.ManagementRoute.DEPLOY_COMPLETE;
import static org.pragmatica.aether.management.route.ManagementRoute.DEPLOY_PROMOTE;
import static org.pragmatica.aether.management.route.ManagementRoute.DEPLOY_ROLLBACK;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_PAUSE;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_RESUME;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_STATE;
import static org.pragmatica.aether.management.route.ManagementRoute.SCHEDULED_TASK_TRIGGER;
import static org.pragmatica.aether.management.route.ManagementRoute.STORAGE_SNAPSHOT;
import static org.pragmatica.aether.management.route.ManagementRoute.STREAMS_METADATA;
import static org.pragmatica.aether.management.route.ManagementRoute.STREAMS_PUBLISH;


/// Verifies all routes from the plan's "Path Rearrangement" table have the expected shape:
/// fixed prefix segments first, parameter segments at well-defined positions described by
/// the path template (literals interleaved with params per spec event-stream-namespaces §12).
class PathRearrangementTest {

    @Test
    void deployPromote_paramAtTail() {
        assertThat(DEPLOY_PROMOTE.prefix()).isEqualTo("/api/deploy/promote");
        assertThat(DEPLOY_PROMOTE.paramNames()).containsExactly("deploymentId");
    }

    @Test
    void deployRollback_paramAtTail() {
        assertThat(DEPLOY_ROLLBACK.prefix()).isEqualTo("/api/deploy/rollback");
        assertThat(DEPLOY_ROLLBACK.paramNames()).containsExactly("deploymentId");
    }

    @Test
    void deployComplete_paramAtTail() {
        assertThat(DEPLOY_COMPLETE.prefix()).isEqualTo("/api/deploy/complete");
        assertThat(DEPLOY_COMPLETE.paramNames()).containsExactly("deploymentId");
    }

    @Test
    void abTestMetrics_paramAtTail() {
        assertThat(AB_TEST_METRICS.prefix()).isEqualTo("/api/ab-test/metrics");
        assertThat(AB_TEST_METRICS.paramNames()).containsExactly("testId");
    }

    @Test
    void abTestConclude_paramAtTail() {
        assertThat(AB_TEST_CONCLUDE.prefix()).isEqualTo("/api/ab-test/conclude");
        assertThat(AB_TEST_CONCLUDE.paramNames()).containsExactly("testId");
    }

    @Test
    void blueprintStatus_paramAtTail() {
        assertThat(BLUEPRINT_STATUS.prefix()).isEqualTo("/api/blueprint/status");
        assertThat(BLUEPRINT_STATUS.paramNames()).containsExactly("blueprintId");
    }

    @Test
    void streamsMetadata_threeParamsAfterPrefix() {
        // Spec event-stream-namespaces §12 — STREAMS_METADATA: GET /api/streams/{ns}/{stream}/{version}
        assertThat(STREAMS_METADATA.prefix()).isEqualTo("/api/streams");
        assertThat(STREAMS_METADATA.paramNames()).containsExactly("namespace", "stream", "version");
    }

    @Test
    void streamsPublish_threeParamsThenLiteral() {
        // Spec event-stream-namespaces §12 — STREAMS_PUBLISH: POST /api/streams/{ns}/{stream}/{version}/publish
        // Literal "publish" appears after the three params (interleaved path template).
        assertThat(STREAMS_PUBLISH.prefix()).isEqualTo("/api/streams");
        assertThat(STREAMS_PUBLISH.paramNames()).containsExactly("namespace", "stream", "version");
        var lastSegment = STREAMS_PUBLISH.segments().get(STREAMS_PUBLISH.segments().size() - 1);
        assertThat(lastSegment.isParam()).isFalse();
        assertThat(lastSegment.text()).isEqualTo("publish");
    }

    @Test
    void storageSnapshot_paramAtTail() {
        assertThat(STORAGE_SNAPSHOT.prefix()).isEqualTo("/api/storage/snapshot");
        assertThat(STORAGE_SNAPSHOT.paramNames()).containsExactly("name");
    }

    @Test
    void clusterTaskReassign_paramAtTail() {
        assertThat(CLUSTER_TASK_REASSIGN.prefix()).isEqualTo("/api/cluster/tasks/reassign");
        assertThat(CLUSTER_TASK_REASSIGN.paramNames()).containsExactly("group");
    }

    @Test
    void scheduledTaskState_paramsAtTail() {
        assertThat(SCHEDULED_TASK_STATE.prefix()).isEqualTo("/api/scheduled-tasks/state");
        assertThat(SCHEDULED_TASK_STATE.paramNames()).containsExactly("section", "artifact", "methodName");
    }

    @Test
    void scheduledTaskActions_splitIntoThreeRoutes() {
        assertThat(SCHEDULED_TASK_PAUSE.prefix()).isEqualTo("/api/scheduled-tasks/pause");
        assertThat(SCHEDULED_TASK_RESUME.prefix()).isEqualTo("/api/scheduled-tasks/resume");
        assertThat(SCHEDULED_TASK_TRIGGER.prefix()).isEqualTo("/api/scheduled-tasks/trigger");
        assertThat(SCHEDULED_TASK_PAUSE.paramNames()).containsExactly("section", "artifact", "methodName");
        assertThat(SCHEDULED_TASK_RESUME.paramNames()).containsExactly("section", "artifact", "methodName");
        assertThat(SCHEDULED_TASK_TRIGGER.paramNames()).containsExactly("section", "artifact", "methodName");
    }

    @Test
    void allRoutes_haveLiteralOnlyPrefix() {
        // Prefixes hold only literal segments; parameters belong in the path template (segments()).
        for (var r : ManagementRoute.values()) {
            assertThat(r.prefix())
                    .as("Route %s prefix must not contain '{' (parameters belong in the segments template)", r.name())
                    .doesNotContain("{");
        }
    }
}
