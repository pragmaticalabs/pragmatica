package org.pragmatica.aether.worker.group;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.worker.group.WorkerGroupId.workerGroupId;

class WorkerGroupIdTest {
    @Test
    void communityId_defaultGroup_returnsDefaultLocal() {
        assertThat(WorkerGroupId.DEFAULT.communityId()).isEqualTo("default:local");
    }

    @Test
    void communityId_customGroupAndZone_formatsCorrectly() {
        var group = workerGroupId("prod", "us-east-1");
        assertThat(group.communityId()).isEqualTo("prod:us-east-1");
    }

    @Test
    void toString_returnsCommunityId() {
        var group = workerGroupId("staging", "eu-west-1");
        assertThat(group.toString()).isEqualTo("staging:eu-west-1");
    }

    @Test
    void equality_sameGroupAndZone_areEqual() {
        var a = workerGroupId("alpha", "zone1");
        var b = workerGroupId("alpha", "zone1");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equality_differentZone_notEqual() {
        var a = workerGroupId("alpha", "zone1");
        var b = workerGroupId("alpha", "zone2");
        assertThat(a).isNotEqualTo(b);
    }
}
