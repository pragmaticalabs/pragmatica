package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// E2E test for backup management API endpoints.
///
///
/// Verifies that backup API routes are properly wired in the management server.
/// Since backup is disabled by default, endpoints should return appropriate
/// "backup disabled" responses, confirming the full pipeline works.
class BackupApiE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 3;
    }

    @Test
    void backupTrigger_whenDisabled_returnsDisabledMessage() {
        var node = cluster.anyNode();
        var response = node.post("/api/backup", "{}");

        assertThat(response)
            .describedAs("Backup trigger should indicate backup is disabled")
            .contains("Backup is not enabled");
    }

    @Test
    void backupList_whenDisabled_returnsDisabledMessage() {
        var node = cluster.anyNode();
        var response = node.get("/api/backups");

        assertThat(response)
            .describedAs("Backup list should indicate backup is disabled")
            .contains("Backup is not enabled");
    }

    @Test
    void backupRestore_whenDisabled_returnsDisabledMessage() {
        var node = cluster.anyNode();
        var response = node.post("/api/backup/restore", "{\"commit\":\"abc123\"}");

        assertThat(response)
            .describedAs("Backup restore should indicate backup is disabled")
            .contains("Backup is not enabled");
    }
}
