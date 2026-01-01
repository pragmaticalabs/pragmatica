package org.pragmatica.aether.metrics.deployment;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.deployment.DeploymentMetrics.DeploymentStatus;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentMetricsTest {

    private final Artifact artifact = Artifact.artifact("org.example:test:1.0.0").unwrap();
    private final NodeId nodeId = NodeId.randomNodeId();

    @Test
    void started_creates_in_progress_metrics() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L);

        assertThat(metrics.artifact()).isEqualTo(artifact);
        assertThat(metrics.nodeId()).isEqualTo(nodeId);
        assertThat(metrics.startTime()).isEqualTo(1000L);
        assertThat(metrics.loadTime()).isEqualTo(0L);
        assertThat(metrics.loadedTime()).isEqualTo(0L);
        assertThat(metrics.activateTime()).isEqualTo(0L);
        assertThat(metrics.activeTime()).isEqualTo(0L);
        assertThat(metrics.status()).isEqualTo(DeploymentStatus.IN_PROGRESS);
    }

    @Test
    void withLoadTime_updates_load_timestamp() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L);

        assertThat(metrics.startTime()).isEqualTo(1000L);
        assertThat(metrics.loadTime()).isEqualTo(1100L);
        assertThat(metrics.status()).isEqualTo(DeploymentStatus.IN_PROGRESS);
    }

    @Test
    void withLoadedTime_updates_loaded_timestamp() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L);

        assertThat(metrics.loadTime()).isEqualTo(1100L);
        assertThat(metrics.loadedTime()).isEqualTo(1500L);
        assertThat(metrics.status()).isEqualTo(DeploymentStatus.IN_PROGRESS);
    }

    @Test
    void withActivateTime_updates_activate_timestamp() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .withActivateTime(1600L);

        assertThat(metrics.loadedTime()).isEqualTo(1500L);
        assertThat(metrics.activateTime()).isEqualTo(1600L);
        assertThat(metrics.status()).isEqualTo(DeploymentStatus.IN_PROGRESS);
    }

    @Test
    void completed_sets_active_time_and_success_status() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .withActivateTime(1600L)
            .completed(2000L);

        assertThat(metrics.activeTime()).isEqualTo(2000L);
        assertThat(metrics.status()).isEqualTo(DeploymentStatus.SUCCESS);
    }

    @Test
    void failedLoading_sets_failed_loading_status() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .failedLoading(1200L);

        assertThat(metrics.loadedTime()).isEqualTo(1200L);
        assertThat(metrics.activateTime()).isEqualTo(0L);
        assertThat(metrics.activeTime()).isEqualTo(0L);
        assertThat(metrics.status()).isEqualTo(DeploymentStatus.FAILED_LOADING);
    }

    @Test
    void failedActivating_sets_failed_activating_status() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .withActivateTime(1600L)
            .failedActivating(1700L);

        assertThat(metrics.activeTime()).isEqualTo(1700L);
        assertThat(metrics.status()).isEqualTo(DeploymentStatus.FAILED_ACTIVATING);
    }

    @Test
    void fullDeploymentTime_returns_total_time_for_completed() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .withActivateTime(1600L)
            .completed(2000L);

        assertThat(metrics.fullDeploymentTime()).isEqualTo(1000L); // 2000 - 1000
    }

    @Test
    void fullDeploymentTime_returns_negative_for_incomplete() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L);

        assertThat(metrics.fullDeploymentTime()).isEqualTo(-1L);
    }

    @Test
    void netDeploymentTime_returns_loaded_to_active_time() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .withActivateTime(1600L)
            .completed(2000L);

        assertThat(metrics.netDeploymentTime()).isEqualTo(500L); // 2000 - 1500
    }

    @Test
    void netDeploymentTime_returns_negative_for_incomplete() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L);

        assertThat(metrics.netDeploymentTime()).isEqualTo(-1L);
    }

    @Test
    void transitionLatencies_calculates_all_transitions() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .withActivateTime(1600L)
            .completed(2000L);

        var latencies = metrics.transitionLatencies();

        assertThat(latencies).containsEntry("START_TO_LOAD", 100L);    // 1100 - 1000
        assertThat(latencies).containsEntry("LOAD_TO_LOADED", 400L);   // 1500 - 1100
        assertThat(latencies).containsEntry("LOADED_TO_ACTIVATE", 100L); // 1600 - 1500
        assertThat(latencies).containsEntry("ACTIVATE_TO_ACTIVE", 400L); // 2000 - 1600
    }

    @Test
    void transitionLatencies_omits_incomplete_transitions() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L);

        var latencies = metrics.transitionLatencies();

        assertThat(latencies).containsEntry("START_TO_LOAD", 100L);
        assertThat(latencies).doesNotContainKey("LOAD_TO_LOADED");
        assertThat(latencies).doesNotContainKey("LOADED_TO_ACTIVATE");
        assertThat(latencies).doesNotContainKey("ACTIVATE_TO_ACTIVE");
    }

    @Test
    void toEntry_converts_to_protocol_format() {
        var metrics = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .completed(2000L);

        var entry = metrics.toEntry();

        assertThat(entry.artifact()).isEqualTo(artifact.asString());
        assertThat(entry.nodeId()).isEqualTo(nodeId.id());
        assertThat(entry.startTime()).isEqualTo(1000L);
        assertThat(entry.loadTime()).isEqualTo(1100L);
        assertThat(entry.loadedTime()).isEqualTo(1500L);
        assertThat(entry.status()).isEqualTo("SUCCESS");
    }

    @Test
    void fromEntry_roundtrip_preserves_data() {
        var original = DeploymentMetrics.started(artifact, nodeId, 1000L)
            .withLoadTime(1100L)
            .withLoadedTime(1500L)
            .withActivateTime(1600L)
            .completed(2000L);

        var entry = original.toEntry();
        var restored = DeploymentMetrics.fromEntry(entry);

        assertThat(restored.artifact()).isEqualTo(original.artifact());
        assertThat(restored.startTime()).isEqualTo(original.startTime());
        assertThat(restored.loadTime()).isEqualTo(original.loadTime());
        assertThat(restored.loadedTime()).isEqualTo(original.loadedTime());
        assertThat(restored.activateTime()).isEqualTo(original.activateTime());
        assertThat(restored.activeTime()).isEqualTo(original.activeTime());
        assertThat(restored.status()).isEqualTo(original.status());
    }

    @Test
    void fromEntry_returns_null_for_invalid_artifact() {
        var entry = new DeploymentMetricsEntry(
            "invalid artifact string",
            nodeId.id(),
            1000L, 1100L, 1500L, 1600L, 2000L,
            "SUCCESS"
        );

        var result = DeploymentMetrics.fromEntry(entry);

        assertThat(result).isNull();
    }

    @Test
    void deploymentStatus_fromString_parses_all_values() {
        assertThat(DeploymentStatus.fromString("IN_PROGRESS")).isEqualTo(DeploymentStatus.IN_PROGRESS);
        assertThat(DeploymentStatus.fromString("SUCCESS")).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(DeploymentStatus.fromString("FAILED_LOADING")).isEqualTo(DeploymentStatus.FAILED_LOADING);
        assertThat(DeploymentStatus.fromString("FAILED_ACTIVATING")).isEqualTo(DeploymentStatus.FAILED_ACTIVATING);
    }

    @Test
    void deploymentStatus_fromString_defaults_to_in_progress_for_unknown() {
        assertThat(DeploymentStatus.fromString("UNKNOWN")).isEqualTo(DeploymentStatus.IN_PROGRESS);
        assertThat(DeploymentStatus.fromString("")).isEqualTo(DeploymentStatus.IN_PROGRESS);
    }
}
