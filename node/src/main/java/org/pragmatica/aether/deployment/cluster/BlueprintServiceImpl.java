package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.blueprint.BlueprintExpander;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

public interface BlueprintServiceImpl {
    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository) {
        record blueprintService(
        ClusterNode<KVCommand<AetherKey>> cluster,
        KVStore<AetherKey, AetherValue> store,
        Repository repository) implements BlueprintService {
            @Override
            public Promise<ExpandedBlueprint> publish(String dsl) {
                return BlueprintParser.parse(dsl)
                                      .async()
                                      .flatMap(blueprint -> BlueprintExpander.expand(blueprint, repository))
                                      .flatMap(this::storeBlueprint);
            }

            @Override
            public Option<ExpandedBlueprint> get(BlueprintId id) {
                return store.get(AppBlueprintKey.appBlueprintKey(id))
                            .flatMap(this::extractBlueprint);
            }

            @Override
            public List<ExpandedBlueprint> list() {
                return store.snapshot()
                            .values()
                            .stream()
                            .map(this::extractBlueprint)
                            .flatMap(Option::stream)
                            .toList();
            }

            @Override
            public Promise<Unit> delete(BlueprintId id) {
                return removeFromStore(AppBlueprintKey.appBlueprintKey(id));
            }

            private Promise<ExpandedBlueprint> storeBlueprint(ExpandedBlueprint expanded) {
                return storeBlueprintWithKey(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                             expanded);
            }

            private Promise<ExpandedBlueprint> storeBlueprintWithKey(AppBlueprintKey key, ExpandedBlueprint expanded) {
                var value = new AppBlueprintValue(expanded);
                KVCommand<AetherKey> command = new Put<>(key, value);
                return cluster.apply(List.of(command))
                              .map(_ -> expanded);
            }

            private Promise<Unit> removeFromStore(AppBlueprintKey key) {
                KVCommand<AetherKey> command = new Remove<>(key);
                return cluster.apply(List.of(command))
                              .mapToUnit();
            }

            private Option<ExpandedBlueprint> extractBlueprint(AetherValue value) {
                return switch (value) {
                    case AppBlueprintValue appValue -> Option.some(appValue.blueprint());
                    default -> Option.none();
                };
            }
        }
        return new blueprintService(cluster, store, repository);
    }
}
