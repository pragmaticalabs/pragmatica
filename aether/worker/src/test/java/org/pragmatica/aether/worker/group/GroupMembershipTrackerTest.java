package org.pragmatica.aether.worker.group;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class GroupMembershipTrackerTest {

    private static NodeId node(String id) {
        return NodeId.nodeId(id).unwrap();
    }

    private static SwimMember aliveMember(String id) {
        return new SwimMember(node(id), MemberState.ALIVE, 0, new InetSocketAddress("localhost", 7200));
    }

    private static SwimMember faultyMember(String id) {
        return new SwimMember(node(id), MemberState.FAULTY, 0, new InetSocketAddress("localhost", 7200));
    }

    @Nested
    class GroupComputation {
        @Test
        void initialState_emptyGroups() {
            var tracker = GroupMembershipTracker.groupMembershipTracker(node("local-self"), "default", 100);
            assertThat(tracker.allGroups()).isEmpty();
            assertThat(tracker.myGroupMembers()).isEmpty();
        }

        @Test
        void updateMember_singleAlive_selfInGroup() {
            var self = node("local-self");
            var tracker = GroupMembershipTracker.groupMembershipTracker(self, "default", 100);
            tracker.updateMember(aliveMember("local-self"));
            assertThat(tracker.myGroupMembers()).contains(self);
            assertThat(tracker.allGroups()).hasSize(1);
        }

        @Test
        void updateMember_twoZones_twoGroups() {
            var self = node("us-east-1-self");
            var tracker = GroupMembershipTracker.groupMembershipTracker(self, "default", 100);
            tracker.updateMember(aliveMember("us-east-1-self"));
            tracker.updateMember(aliveMember("eu-west-1-other"));
            assertThat(tracker.allGroups()).hasSize(2);
        }
    }

    @Nested
    class MembershipChanges {
        @Test
        void removeMember_groupShrinks() {
            var self = node("local-self");
            var tracker = GroupMembershipTracker.groupMembershipTracker(self, "default", 100);
            tracker.updateMember(aliveMember("local-self"));
            tracker.updateMember(aliveMember("local-other"));
            assertThat(tracker.allAliveMembers()).hasSize(2);
            tracker.removeMember(node("local-other"));
            assertThat(tracker.allAliveMembers()).hasSize(1);
        }

        @Test
        void updateMember_faultyRemoved() {
            var self = node("local-self");
            var tracker = GroupMembershipTracker.groupMembershipTracker(self, "default", 100);
            tracker.updateMember(aliveMember("local-self"));
            tracker.updateMember(aliveMember("local-other"));
            tracker.updateMember(faultyMember("local-other"));
            assertThat(tracker.allAliveMembers()).hasSize(1);
        }
    }

    @Nested
    class GroupIdentification {
        @Test
        void myGroup_returnsCorrectGroup() {
            var self = node("us-east-1-self");
            var tracker = GroupMembershipTracker.groupMembershipTracker(self, "prod", 100);
            tracker.updateMember(aliveMember("us-east-1-self"));
            assertThat(tracker.myGroup().groupName()).isEqualTo("prod");
            assertThat(tracker.myGroup().zone()).isEqualTo("us-east-1");
        }
    }
}
