package org.pragmatica.aether.worker.governor;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorElectionTest {
    private static final InetSocketAddress ADDR = new InetSocketAddress("localhost", 7200);

    private static SwimMember aliveMember(String id) {
        return SwimMember.swimMember(NodeId.nodeId(id).unwrap(), MemberState.ALIVE, 0, ADDR);
    }

    private static SwimMember faultyMember(String id) {
        return SwimMember.swimMember(NodeId.nodeId(id).unwrap(), MemberState.FAULTY, 0, ADDR);
    }

    private static SwimMember suspectMember(String id) {
        return SwimMember.swimMember(NodeId.nodeId(id).unwrap(), MemberState.SUSPECT, 0, ADDR);
    }

    private static NodeId id(String name) {
        return NodeId.nodeId(name).unwrap();
    }

    @Nested
    class DeterministicElection {

        @Test
        void evaluateElection_electsLowestAlive_whenNoIncumbent() {
            var members = List.of(aliveMember("worker-c"), aliveMember("worker-a"), aliveMember("worker-b"));

            var state = GovernorElection.evaluateElection(id("worker-b"), members, Option.empty());

            assertThat(state).isInstanceOf(GovernorState.Follower.class);
            assertThat(((GovernorState.Follower) state).governorId()).isEqualTo(id("worker-a"));
        }

        @Test
        void evaluateElection_returnsSelf_whenSelfIsLowest() {
            var members = List.of(aliveMember("worker-c"), aliveMember("worker-a"), aliveMember("worker-b"));

            var state = GovernorElection.evaluateElection(id("worker-a"), members, Option.empty());

            assertThat(state).isInstanceOf(GovernorState.Governor.class);
            assertThat(((GovernorState.Governor) state).self()).isEqualTo(id("worker-a"));
        }

        @Test
        void evaluateElection_skipsFaultyMembers_whenElecting() {
            var members = List.of(faultyMember("worker-a"), aliveMember("worker-b"), aliveMember("worker-c"));

            var state = GovernorElection.evaluateElection(id("worker-c"), members, Option.empty());

            assertThat(state).isInstanceOf(GovernorState.Follower.class);
            assertThat(((GovernorState.Follower) state).governorId()).isEqualTo(id("worker-b"));
        }

        @Test
        void evaluateElection_skipsSuspectMembers_whenElecting() {
            var members = List.of(suspectMember("worker-a"), aliveMember("worker-b"), aliveMember("worker-c"));

            var state = GovernorElection.evaluateElection(id("worker-c"), members, Option.empty());

            assertThat(state).isInstanceOf(GovernorState.Follower.class);
            assertThat(((GovernorState.Follower) state).governorId()).isEqualTo(id("worker-b"));
        }

        @Test
        void evaluateElection_electsSelf_whenOnlyMember() {
            var state = GovernorElection.evaluateElection(id("worker-solo"), List.of(), Option.empty());

            assertThat(state).isInstanceOf(GovernorState.Governor.class);
        }
    }

    @Nested
    class StickyIncumbent {

        @Test
        void evaluateElection_keepsIncumbent_whenStillAlive() {
            var members = List.of(aliveMember("worker-a"), aliveMember("worker-b"), aliveMember("worker-c"));
            var incumbent = Option.some(id("worker-b"));

            var state = GovernorElection.evaluateElection(id("worker-c"), members, incumbent);

            assertThat(state).isInstanceOf(GovernorState.Follower.class);
            assertThat(((GovernorState.Follower) state).governorId()).isEqualTo(id("worker-b"));
        }

        @Test
        void evaluateElection_reElects_whenIncumbentFaulty() {
            var members = List.of(faultyMember("worker-a"), aliveMember("worker-b"), aliveMember("worker-c"));
            var incumbent = Option.some(id("worker-a"));

            var state = GovernorElection.evaluateElection(id("worker-c"), members, incumbent);

            // worker-a is faulty, so re-election picks worker-b (lowest alive)
            assertThat(state).isInstanceOf(GovernorState.Follower.class);
            assertThat(((GovernorState.Follower) state).governorId()).isEqualTo(id("worker-b"));
        }

        @Test
        void evaluateElection_incumbentBecomesSelf_whenIncumbentIsSelf() {
            var members = List.of(aliveMember("worker-a"), aliveMember("worker-b"));
            var incumbent = Option.some(id("worker-a"));

            var state = GovernorElection.evaluateElection(id("worker-a"), members, incumbent);

            assertThat(state).isInstanceOf(GovernorState.Governor.class);
        }
    }

    @Nested
    class Determinism {

        @Test
        void evaluateElection_sameResult_fromDifferentNodes() {
            var members = List.of(aliveMember("worker-a"), aliveMember("worker-b"), aliveMember("worker-c"));

            var stateFromB = GovernorElection.evaluateElection(id("worker-b"), members, Option.empty());
            var stateFromC = GovernorElection.evaluateElection(id("worker-c"), members, Option.empty());

            // Both should agree that worker-a is the governor
            assertThat(stateFromB).isInstanceOf(GovernorState.Follower.class);
            assertThat(stateFromC).isInstanceOf(GovernorState.Follower.class);
            assertThat(((GovernorState.Follower) stateFromB).governorId())
                .isEqualTo(((GovernorState.Follower) stateFromC).governorId())
                .isEqualTo(id("worker-a"));
        }
    }
}
