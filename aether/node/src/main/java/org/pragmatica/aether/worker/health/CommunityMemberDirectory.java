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
    private final Map<NodeId, String> assignments = new HashMap<>();
    private final Map<String, Set<NodeId>> communities = new HashMap<>();

    public static CommunityMemberDirectory communityMemberDirectory() {
        return new CommunityMemberDirectory();
    }

    public synchronized org.pragmatica.lang.Unit put(NodeId node, AetherValue.ActivationDirectiveValue value) {
        remove(node);
        if (("worker".equalsIgnoreCase(value.role()) || "spot".equalsIgnoreCase(value.role())) && !value.communityId()
                                                                                                        .isBlank()) {
            assignments.put(node, value.communityId());
            communities.computeIfAbsent(value.communityId(), _ -> new HashSet<>()).add(node);
        }

        return org.pragmatica.lang.Unit.unit();
    }

    public synchronized org.pragmatica.lang.Unit remove(NodeId node) {
        Option.option(assignments.remove(node)).onPresent(community -> removeFromCommunity(node, community));

        return org.pragmatica.lang.Unit.unit();
    }

    private void removeFromCommunity(NodeId node, String community) {
        Option.option(communities.get(community)).onPresent(members -> removeMember(community, members, node));
    }

    private void removeMember(String community, Set<NodeId> members, NodeId node) {
        members.remove(node);
        if (members.isEmpty()) {
            communities.remove(community);
        }
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
        snapshot.forEach(this::restoreEntry);

        return org.pragmatica.lang.Unit.unit();
    }

    private void restoreEntry(Object key, Object value) {
        if (key instanceof AetherKey.ActivationDirectiveKey directive && value instanceof AetherValue.ActivationDirectiveValue assignment) {
            put(directive.nodeId(), assignment);
        }
    }
}
