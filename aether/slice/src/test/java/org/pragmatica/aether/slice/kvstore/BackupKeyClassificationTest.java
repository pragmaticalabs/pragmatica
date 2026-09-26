// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkerSliceDirectiveKey;
import org.pragmatica.aether.slice.kvstore.BackupFixtures.Fixture;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.BackupFixtures.FIXTURES;

/// Guards what a backup may contain. A backup is restored into a cluster whose nodes are not the ones
/// that wrote it, so a backed-up key or value naming a [NodeId] would restore a reference to a node
/// that does not exist. The walk is structural — record components, type arguments, and the permitted
/// subtypes of sealed types, recursively — so a `NodeId` buried in `Option<List<SomeRecord>>` is found.
class BackupKeyClassificationTest {
    /// Paths that legitimately reach a `NodeId`, each with the mechanism that keeps it out of a backup.
    private static final Map<String, String> ALLOWED_NODE_REFERENCES = Map.of(
            // Node-scoped overrides share the key type with cluster-wide config; ConfigKey#isBackedUp is
            // false for them, so only the `nodeScope = none` rows reach a backup (pinned below).
            "ConfigKey.nodeScope", "filtered per entry by ConfigKey#isBackedUp");

    @Test
    void everyKeyType_isExactlyOneOfClusterStateOrRuntime() {
        var ambiguous = concreteKeyTypes().stream()
                                          .filter(type -> ClusterStateKey.class.isAssignableFrom(type) == RuntimeKey.class.isAssignableFrom(type))
                                          .map(Class::getSimpleName)
                                          .toList();

        assertThat(ambiguous).as("key types that are both or neither").isEmpty();
    }

    @Test
    void backedUpKeysAndValues_carryNoNodeId_exceptAllowlisted() {
        var found = new ArrayList<String>();
        var roots = Stream.concat(Arrays.stream(ClusterStateKey.class.getPermittedSubclasses()),
                                  FIXTURES.stream()
                                          .map(Fixture::value)
                                          .map(Object::getClass))
                          .distinct()
                          .toList();

        roots.forEach(root -> collectNodeReferences(root, root.getSimpleName(), new HashSet<>(), found));

        assertThat(found).as("NodeId reachable from a backed-up key or value")
                         .allMatch(ALLOWED_NODE_REFERENCES::containsKey);
    }

    /// An allowlist entry that no longer matches anything is a stale exemption waiting to hide a real one.
    @Test
    void nodeReferenceAllowlist_hasNoStaleEntries() {
        var found = new ArrayList<String>();

        Arrays.stream(ClusterStateKey.class.getPermittedSubclasses())
              .forEach(root -> collectNodeReferences(root, root.getSimpleName(), new HashSet<>(), found));

        assertThat(found).containsAll(ALLOWED_NODE_REFERENCES.keySet());
    }

    @Test
    void configKey_backsUpOnlyClusterWideEntries() {
        var node = NodeId.nodeId("node-1")
                         .unwrap();

        assertThat(ConfigKey.forKey("orders.banner")
                            .isBackedUp()).isTrue();
        assertThat(ConfigKey.forKey("orders.banner", node)
                            .isBackedUp()).isFalse();
    }

    /// The S28 decisions, pinned so a silent reclassification is a named failure.
    @Test
    void reclassifiedKeys_areRuntime() {
        assertThat(List.of(TopicSubscriptionKey.class,
                           EntityKeyspaceRegistrationKey.class,
                           StreamRegistrationKey.class,
                           ConsumerAssignmentKey.class,
                           StreamCursorCheckpointKey.class,
                           ScheduledTaskStateKey.class,
                           StreamRegistryKey.class,
                           ScheduledTaskKey.class,
                           WorkerSliceDirectiveKey.class,
                           GossipKeyRotationKey.class)).allMatch(RuntimeKey.class::isAssignableFrom);
        assertThat(ClusterStateKey.class.isAssignableFrom(DeploymentKey.class)).isTrue();
    }

    private static List<Class<?>> concreteKeyTypes() {
        return Stream.of(AetherKey.class.getPermittedSubclasses())
                     .flatMap(branch -> Stream.of(branch.getPermittedSubclasses()))
                     .toList();
    }

    private static void collectNodeReferences(Type type, String path, Set<Type> visited, List<String> found) {
        switch (type) {
            case Class<?> clazz when clazz == NodeId.class -> found.add(path);
            case Class<?> clazz when !visited.add(clazz) -> {}
            case Class<?> clazz when clazz.isRecord() -> Arrays.stream(clazz.getRecordComponents())
                                                               .forEach(component -> collectNodeReferences(component.getGenericType(),
                                                                                                           clazz.getSimpleName() + "." + component.getName(),
                                                                                                           visited,
                                                                                                           found));
            case Class<?> clazz when clazz.isSealed() -> Arrays.stream(clazz.getPermittedSubclasses())
                                                               .forEach(sub -> collectNodeReferences(sub, path, visited, found));
            case Class<?> clazz when clazz.isArray() -> collectNodeReferences(clazz.getComponentType(), path, visited, found);
            case Class<?> _ -> {}
            case ParameterizedType parameterized -> Arrays.stream(parameterized.getActualTypeArguments())
                                                          .forEach(argument -> collectNodeReferences(argument, path, visited, found));
            case WildcardType wildcard -> Arrays.stream(wildcard.getUpperBounds())
                                                .forEach(bound -> collectNodeReferences(bound, path, visited, found));
            case GenericArrayType array -> collectNodeReferences(array.getGenericComponentType(), path, visited, found);
            default -> {}
        }
    }
}
