package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlueprintServiceTest {

    private BlueprintService service;
    private TestClusterNode cluster;
    private TestKVStore store;
    private Repository repository;

    @BeforeEach
    void setup() {
        cluster = new TestClusterNode();
        store = new TestKVStore();
        cluster.setStore(store);
        // Repository is not used in tests since we test get/list/delete directly
        repository = artifact -> Causes.cause("Repository not used in tests").promise();

        service = BlueprintServiceImpl.blueprintService(cluster, store, repository);
    }

    @Nested
    class PublishTests {
        // Note: publish() requires actual JAR files for dependency resolution.
        // These tests verify parser validation only.

        @Test
        void publish_fails_withInvalidDsl() {
            var dsl = "invalid blueprint";

            service.publish(dsl)
                   .await()
                   .onSuccessRun(Assertions::fail);
        }

        @Test
        void publish_fails_withMissingHeader() {
            var dsl = """
                    [slices]
                    org.example:slice-a:1.0.0 = 2
                    """;

            service.publish(dsl)
                   .await()
                   .onSuccessRun(Assertions::fail);
        }
    }

    @Nested
    class GetTests {
        @Test
        void get_returnsNone_whenBlueprintNotFound() {
            var id = BlueprintId.blueprintId("org.example:missing:1.0.0").unwrap();

            var result = service.get(id);

            assertThat(result.isPresent()).isFalse();
        }

        @Test
        void get_returnsSome_whenBlueprintExists() {
            var blueprintId = BlueprintId.blueprintId("org.example:existing:1.0.0").unwrap();
            var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();
            var expanded = ExpandedBlueprint.expandedBlueprint(
                    blueprintId,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 1, false).unwrap())
                                                              );

            var key = AppBlueprintKey.appBlueprintKey(blueprintId);
            var value = new AppBlueprintValue(expanded);
            store.process(new KVCommand.Put<>(key, value));

            var result = service.get(blueprintId);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(retrieved ->
                                     assertThat(retrieved.id().artifact().artifactId().id()).isEqualTo("existing")
                            );
        }
    }

    @Nested
    class ListTests {
        @Test
        void list_returnsEmpty_whenNoBlueprints() {
            var result = service.list();

            assertThat(result).isEmpty();
        }

        @Test
        void list_returnsAll_whenMultipleBlueprints() {
            var id1 = BlueprintId.blueprintId("org.example:app1:1.0.0").unwrap();
            var id2 = BlueprintId.blueprintId("org.example:app2:2.0.0").unwrap();
            var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();

            var expanded1 = ExpandedBlueprint.expandedBlueprint(
                    id1,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 1, false).unwrap())
                                                               );
            var expanded2 = ExpandedBlueprint.expandedBlueprint(
                    id2,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 2, false).unwrap())
                                                               );

            store.process(new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(id1), new AppBlueprintValue(expanded1)));
            store.process(new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(id2), new AppBlueprintValue(expanded2)));

            var result = service.list();

            assertThat(result).hasSize(2);
            assertThat(result.stream().map(e -> e.id().artifact().artifactId().id())).containsExactlyInAnyOrder("app1", "app2");
        }
    }

    @Nested
    class DeleteTests {
        @Test
        void delete_succeeds_whenBlueprintExists() {
            var blueprintId = BlueprintId.blueprintId("org.example:to-delete:1.0.0").unwrap();
            var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();
            var expanded = ExpandedBlueprint.expandedBlueprint(
                    blueprintId,
                    List.of(ResolvedSlice.resolvedSlice(artifact, 1, false).unwrap())
                                                              );

            var key = AppBlueprintKey.appBlueprintKey(blueprintId);
            store.process(new KVCommand.Put<>(key, new AppBlueprintValue(expanded)));

            service.delete(blueprintId)
                   .await()
                   .onFailureRun(Assertions::fail);

            var result = service.get(blueprintId);
            assertThat(result.isPresent()).isFalse();
        }

        @Test
        void delete_succeeds_whenBlueprintNotFound() {
            var id = BlueprintId.blueprintId("org.example:non-existent:1.0.0").unwrap();

            service.delete(id)
                   .await()
                   .onFailureRun(Assertions::fail);
        }
    }

    // Test implementation of ClusterNode that delegates to TestKVStore
    private static class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private TestKVStore store;

        void setStore(TestKVStore store) {
            this.store = store;
        }

        @Override
        public NodeId self() {
            return NodeId.nodeId("test-node").unwrap();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            // Process commands through the store
            return Promise.success(commands.stream()
                                           .map(cmd -> (R) store.process(cmd))
                                           .toList());
        }
    }

    // Test implementation of KVStore
    private static class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        public TestKVStore() {
            super(null, null, null);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        // Override the StateMachine process method
        @SuppressWarnings("unchecked")
        public Option<AetherValue> process(org.pragmatica.cluster.state.kvstore.KVCommand command) {
            return switch (command) {
                case KVCommand.Put<?, ?> put -> {
                    storage.put((AetherKey) put.key(), (AetherValue) put.value());
                    yield Option.none();
                }
                case KVCommand.Remove<?> remove -> {
                    storage.remove((AetherKey) remove.key());
                    yield Option.none();
                }
                case KVCommand.Get<?> get -> Option.option(storage.get((AetherKey) get.key()));
                default -> Option.none();
            };
        }
    }
}
