package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.blueprint.Blueprint;
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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;

/// Service for managing application blueprints in the cluster.
///
/// Blueprints define the desired cluster topology (which slices to deploy with how many instances).
/// BlueprintService handles CRUD operations for blueprints, storing them in the consensus KV-Store.
///
/// Operations:
/// - publish: Parse DSL, expand dependencies, store in KV-Store
/// - get: Retrieve ExpandedBlueprint by BlueprintId
/// - list: List all deployed blueprints
/// - delete: Remove blueprint from KV-Store
public interface BlueprintService {
    /// Publish a new blueprint from DSL string.
    ///
    /// Process:
    /// 1. Parse DSL to Blueprint
    /// 2. Expand dependencies using Repository
    /// 3. Store ExpandedBlueprint in KV-Store
    ///
    /// @param dsl Blueprint DSL definition
    ///
    /// @return ExpandedBlueprint after successful publication
    Promise<ExpandedBlueprint> publish(String dsl);

    /// Retrieve an existing blueprint by ID.
    ///
    /// @param id BlueprintId to look up
    ///
    /// @return Option.some(ExpandedBlueprint) if exists, Option.none() otherwise
    Option<ExpandedBlueprint> get(BlueprintId id);

    /// List all published blueprints.
    ///
    /// @return List of all ExpandedBlueprints in the cluster
    List<ExpandedBlueprint> list();

    /// Delete a blueprint from the cluster.
    ///
    /// Idempotent: deleting non-existing blueprint succeeds with Unit.
    ///
    /// @param id BlueprintId to delete
    ///
    /// @return Promise of Unit on success
    Promise<Unit> delete(BlueprintId id);

    /// Validate a blueprint DSL string without deploying.
    ///
    /// Parses the DSL and checks for syntax errors.
    /// Does NOT validate artifact availability in repository.
    ///
    /// @param dsl Blueprint DSL definition
    ///
    /// @return Result containing parsed Blueprint if valid, or error cause if invalid
    Result<Blueprint> validate(String dsl);

    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository) {
        record blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
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
                var result = new ArrayList<ExpandedBlueprint>();
                store.forEach(AppBlueprintKey.class, AppBlueprintValue.class,
                              (_, value) -> result.add(value.blueprint()));
                return result;
            }

            @Override
            public Promise<Unit> delete(BlueprintId id) {
                return removeFromStore(AppBlueprintKey.appBlueprintKey(id));
            }

            @Override
            public Result<Blueprint> validate(String dsl) {
                return BlueprintParser.parse(dsl);
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
