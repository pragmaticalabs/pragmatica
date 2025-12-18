package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Service for managing application blueprints in the cluster.
 * <p>
 * Blueprints define the desired cluster topology (which slices to deploy with how many instances).
 * BlueprintService handles CRUD operations for blueprints, storing them in the consensus KV-Store.
 * <p>
 * Operations:
 * - publish: Parse DSL, expand dependencies, store in KV-Store
 * - get: Retrieve ExpandedBlueprint by BlueprintId
 * - list: List all deployed blueprints
 * - delete: Remove blueprint from KV-Store
 */
public interface BlueprintService {
    /**
     * Publish a new blueprint from DSL string.
     * <p>
     * Process:
     * 1. Parse DSL to Blueprint
     * 2. Expand dependencies using Repository
     * 3. Store ExpandedBlueprint in KV-Store
     *
     * @param dsl Blueprint DSL definition
     *
     * @return ExpandedBlueprint after successful publication
     */
    Promise<ExpandedBlueprint> publish(String dsl);

    /**
     * Retrieve an existing blueprint by ID.
     *
     * @param id BlueprintId to look up
     *
     * @return Option.some(ExpandedBlueprint) if exists, Option.none() otherwise
     */
    Option<ExpandedBlueprint> get(BlueprintId id);

    /**
     * List all published blueprints.
     *
     * @return List of all ExpandedBlueprints in the cluster
     */
    List<ExpandedBlueprint> list();

    /**
     * Delete a blueprint from the cluster.
     * <p>
     * Idempotent: deleting non-existing blueprint succeeds with Unit.
     *
     * @param id BlueprintId to delete
     *
     * @return Promise of Unit on success
     */
    Promise<Unit> delete(BlueprintId id);
}
