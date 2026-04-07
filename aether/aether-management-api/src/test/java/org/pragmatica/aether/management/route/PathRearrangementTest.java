/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
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
import static org.pragmatica.aether.management.route.ManagementRoute.STREAM_PUBLISH;
import static org.pragmatica.aether.management.route.ManagementRoute.STREAM_READ;


/// Verifies all routes from the plan's "Path Rearrangement" table have the new shape:
/// fixed prefix segments first, all path parameters at the tail.
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
    void streamPublish_paramAtTail() {
        assertThat(STREAM_PUBLISH.prefix()).isEqualTo("/api/streams/publish");
        assertThat(STREAM_PUBLISH.paramNames()).containsExactly("streamName");
    }

    @Test
    void streamRead_paramsAtTail() {
        assertThat(STREAM_READ.prefix()).isEqualTo("/api/streams/read");
        assertThat(STREAM_READ.paramNames()).containsExactly("streamName", "partition");
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
    void allRoutes_obeyTailParamsInvariant() {
        // No prefix may itself contain placeholder syntax (e.g. {id}).
        for (var r : ManagementRoute.values()) {
            assertThat(r.prefix())
                    .as("Route %s prefix must not contain '{' (parameters belong at tail)", r.name())
                    .doesNotContain("{");
        }
    }
}
