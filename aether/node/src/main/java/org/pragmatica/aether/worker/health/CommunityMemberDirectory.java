// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.worker.health;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Unit.unit;


/// Index of committed assignment intent, independent of observed liveness.
public final class CommunityMemberDirectory {
    /// Where the directory's maps and sets come from. Production uses hash containers; a test substitutes
    /// counting ones to pin how many entries a lookup touches (#1840).
    interface Containers {
        <K, V> Map<K, V> map();

        <T> Set<T> set();

        Containers HASH = new Containers() {
            @Override
            public <K, V> Map<K, V> map() {
                return new HashMap<>();
            }

            @Override
            public <T> Set<T> set() {
                return new HashSet<>();
            }
        };
    }

    private final Containers containers;
    private final Map<NodeId, String> assignments;
    private final Map<String, Set<NodeId>> communities;
    private final Map<String, Set<NodeId>> candidates;

    private CommunityMemberDirectory(Containers containers) {
        this.containers = containers;
        this.assignments = containers.map();
        this.communities = containers.map();
        this.candidates = containers.map();
    }

    public static CommunityMemberDirectory communityMemberDirectory() {
        return new CommunityMemberDirectory(Containers.HASH);
    }

    static CommunityMemberDirectory communityMemberDirectory(Containers containers) {
        return new CommunityMemberDirectory(containers);
    }

    public synchronized org.pragmatica.lang.Unit put(NodeId node, AetherValue.ActivationDirectiveValue value) {
        remove(node);
        if (("worker".equalsIgnoreCase(value.role()) || "spot".equalsIgnoreCase(value.role())) && !value.communityId()
                                                                                                        .isBlank()) {
            assignments.put(node, value.communityId());
            communities.computeIfAbsent(value.communityId(), _ -> containers.set()).add(node);
        }

        if (AetherValue.ActivationDirectiveValue.WORKER.equals(value.role()) && !value.communityId().isBlank()) {
            candidates.computeIfAbsent(value.communityId(), _ -> containers.set()).add(node);
        }

        return org.pragmatica.lang.Unit.unit();
    }

    public synchronized org.pragmatica.lang.Unit remove(NodeId node) {
        Option.option(assignments.remove(node)).onPresent(community -> removeFromCommunity(node, community));

        return org.pragmatica.lang.Unit.unit();
    }

    private void removeFromCommunity(NodeId node, String community) {
        Option.option(communities.get(community)).onPresent(members -> removeMember(community, members, node));
        Option.option(candidates.get(community)).onPresent(members -> removeCandidate(community, members, node));
    }

    private void removeMember(String community, Set<NodeId> members, NodeId node) {
        members.remove(node);
        if (members.isEmpty()) {
            communities.remove(community);
        }
    }

    private void removeCandidate(String community, Set<NodeId> members, NodeId node) {
        members.remove(node);
        if (members.isEmpty()) {
            candidates.remove(community);
        }
    }

    /// Governor candidates of a community (H13): nodes whose committed directive has role WORKER and names
    /// this community. The ROLE is checked here, so a core is never a candidate whatever its directive's
    /// community says. O(community): a map lookup plus a copy of the community's candidates.
    public synchronized Set<NodeId> governorCandidates(String community) {
        return Set.copyOf(candidates.getOrDefault(community, Set.of()));
    }

    public synchronized Option<String> assignment(NodeId node) {
        return Option.option(assignments.get(node));
    }

    public synchronized List<NodeId> members(String community) {
        return Option.option(communities.get(community))
                     .map(members -> members.stream()
                                            .sorted()
                                            .toList())
                     .or(List.of());
    }

    public synchronized Set<String> communities() {
        return Set.copyOf(communities.keySet());
    }

    public synchronized org.pragmatica.lang.Unit restore(Map<?, ?> snapshot) {
        assignments.clear();
        communities.clear();
        candidates.clear();
        snapshot.forEach(this::restoreEntry);

        return org.pragmatica.lang.Unit.unit();
    }

    private void restoreEntry(Object key, Object value) {
        if (key instanceof AetherKey.ActivationDirectiveKey directive && value instanceof AetherValue.ActivationDirectiveValue assignment) {
            put(directive.nodeId(), assignment);
        }
    }
}
