// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintArtifact;
import org.pragmatica.aether.slice.blueprint.BlueprintArtifactParser;
import org.pragmatica.aether.slice.blueprint.BlueprintExpander;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.aether.slice.blueprint.PubSubValidator;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace;
import org.pragmatica.aether.deployment.validation.StreamResourceValidator;
import org.pragmatica.aether.deployment.validation.ValidatedStreamResources;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.aether.slice.topology.TopologyParser;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface BlueprintService {
    Promise<ExpandedBlueprint> publish(String dsl);
    Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords);
    /// Publish an artifact-based blueprint with explicit register-only semantics.
    /// When `registerOnly == true`, the blueprint definition is stored in KV (so the
    /// strategy-based deploy can later locate the upgrade target), but the
    /// `SliceTargetValue` Put that would activate the new version is suppressed by
    /// `ClusterDeploymentState.handleAppBlueprintChange` when an existing
    /// `SliceTargetValue` already pins the slice to a different (active) version.
    /// First-ever publish for a slice still bootstraps the `SliceTargetValue`
    /// (register-only cannot suppress fresh-slice bootstrap).
    /// When `registerOnly == false`, behaves as the default `publishFromArtifact` —
    /// the new version is immediately activated.
    Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords, boolean registerOnly);
    Option<ExpandedBlueprint> get(BlueprintId id);
    List<ExpandedBlueprint> list();
    Promise<Unit> delete(BlueprintId id);
    Result<Blueprint> validate(String dsl);
    Cause ARTIFACT_STORE_NOT_CONFIGURED = Causes.cause("ArtifactStore not configured");

    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository,
                                             ArtifactStore artifactStore) {
        return new BlueprintServiceInstance(cluster, store, repository, Option.some(artifactStore));
    }

    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository) {
        return new BlueprintServiceInstance(cluster, store, repository, Option.empty());
    }

    static List<SliceTopology> flattenTopologyResults(List<Result<List<SliceTopology>>> results) {
        return results.stream()
                      .flatMap(result -> result.or(List.of())
                                               .stream())
                      .toList();
    }

    static int extractVersionNumber(String filename) {
        var underscoreIdx = filename.indexOf("__");

        if (underscoreIdx <= 1) {return 0;}

        var numPart = filename.substring(1, underscoreIdx);

        return Result.lift1(Causes::fromThrowable, Integer::parseInt, numPart).or(0);
    }
}

@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
class BlueprintServiceInstance implements BlueprintService {
    private static final Logger log = LoggerFactory.getLogger(BlueprintServiceInstance.class);

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> store;
    private final Repository repository;
    private final Option<ArtifactStore> artifactStore;

    BlueprintServiceInstance(ClusterNode<KVCommand<AetherKey>> cluster,
                             KVStore<AetherKey, AetherValue> store,
                             Repository repository,
                             Option<ArtifactStore> artifactStore) {
        this.cluster = cluster;
        this.store = store;
        this.repository = repository;
        this.artifactStore = artifactStore;
    }

    @Override
    public Promise<ExpandedBlueprint> publish(String dsl) {
        return BlueprintParser.parse(dsl)
                              .async()
                              .flatMap(blueprint -> BlueprintExpander.expand(blueprint, repository))
                              .flatMap(this::validatePubSub)
                              .flatMap(this::storeBlueprint)
                              .onFailure(cause -> log.warn("Failed to publish blueprint: {}",
                                                           cause.message()));
    }

    @Override
    public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords) {
        return publishFromArtifact(artifactCoords, false);
    }

    @Override
    public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords, boolean registerOnly) {
        var parsed = parseArtifactWithClassifier(artifactCoords);

        return parsed.artifact()
                     .async()
                     .flatMap(artifact -> resolveArtifactBytes(artifact,
                                                               parsed.classifier()))
                     .flatMap(jarBytes -> BlueprintArtifactParser.parse(jarBytes).async())
                     .flatMap(artifact -> expandAndStoreArtifact(artifact,
                                                                 parsed.baseCoords(),
                                                                 registerOnly))
                     .onFailure(cause -> log.warn("Failed to publish blueprint from artifact (registerOnly={}): {}",
                                                  registerOnly,
                                                  cause.message()));
    }

    private Promise<byte[]> resolveArtifactBytes(Artifact artifact, String classifier) {
        return repository.locate(artifact, classifier)
                         .flatMap(BlueprintServiceInstance::readLocationBytes)
                         .orElse(() -> resolveFromArtifactStore(artifact));
    }

    private record ParsedArtifactCoords(Result<Artifact> artifact, String classifier, String baseCoords) {
        static ParsedArtifactCoords parsedArtifactCoords(Result<Artifact> artifact,
                                                         String classifier,
                                                         String baseCoords) {
            return new ParsedArtifactCoords(artifact, classifier, baseCoords);
        }
    }

    private static final Cause MISSING_CLASSIFIER = Causes.cause("Invalid artifact coordinates: expected groupId:artifactId:version:classifier (e.g., org.example:my-app:1.0.0:blueprint)");

    private static ParsedArtifactCoords parseArtifactWithClassifier(String coords) {
        var parts = coords.split(":");

        if (parts.length == 4) {
            var baseCoords = parts[0] + ":" + parts[1] + ":" + parts[2];

            return ParsedArtifactCoords.parsedArtifactCoords(Artifact.artifact(baseCoords), parts[3], baseCoords);
        }
        if (parts.length == 3) {
            return ParsedArtifactCoords.parsedArtifactCoords(Artifact.artifact(coords), "blueprint", coords);
        }

        return ParsedArtifactCoords.parsedArtifactCoords(MISSING_CLASSIFIER.result(), "", coords);
    }

    private Promise<byte[]> resolveFromArtifactStore(Artifact artifact) {
        return artifactStore.async(ARTIFACT_STORE_NOT_CONFIGURED)
                            .flatMap(store -> store.resolve(artifact));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Promise<byte[]> readLocationBytes(Location location) {
        return Promise.lift(Causes::fromThrowable, () -> readStreamBytes(location));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static byte[] readStreamBytes(Location location) throws Exception {
        try (var stream = location.url().openStream()) {
            return stream.readAllBytes();
        }
    }

    @Override
    public Option<ExpandedBlueprint> get(BlueprintId id) {
        return store.get(AetherKey.AppBlueprintKey.appBlueprintKey(id))
                    .flatMap(this::extractBlueprint);
    }

    @Override
    public List<ExpandedBlueprint> list() {
        var result = new ArrayList<ExpandedBlueprint>();
        store.forEach(AetherKey.AppBlueprintKey.class,
                      AetherValue.AppBlueprintValue.class,
                      (_, value) -> result.add(value.blueprint()));

        return result;
    }

    @Override
    public Promise<Unit> delete(BlueprintId id) {
        return removeFromStore(AetherKey.AppBlueprintKey.appBlueprintKey(id)).onFailure(cause -> log.warn("Failed to delete blueprint {}: {}",
                                                                                                          id.asString(),
                                                                                                          cause.message()));
    }

    @Override
    public Result<Blueprint> validate(String dsl) {
        return BlueprintParser.parse(dsl);
    }

    private Promise<ExpandedBlueprint> expandAndStoreArtifact(BlueprintArtifact blueprintArtifact,
                                                              String artifactCoords,
                                                              boolean registerOnly) {
        return BlueprintExpander.expand(blueprintArtifact.blueprint(),
                                        repository).flatMap(expanded -> applyResourcesConfig(expanded,
                                                                                             blueprintArtifact.resourcesConfig()))
                                       .flatMap(this::validatePubSub)
                                       .flatMap(expanded -> storeAllInSingleBatch(expanded,
                                                                                  blueprintArtifact.resourcesConfig(),
                                                                                  blueprintArtifact.roleHints(),
                                                                                  blueprintArtifact.schemaMigrations(),
                                                                                  artifactCoords,
                                                                                  registerOnly));
    }

    private Promise<ExpandedBlueprint> storeAllInSingleBatch(ExpandedBlueprint expanded,
                                                             Option<String> resourcesConfig,
                                                             Map<String, String> roleHints,
                                                             Map<String, List<MigrationEntry>> migrations,
                                                             String artifactCoords,
                                                             boolean registerOnly) {
        var commands = buildAllCommands(expanded, resourcesConfig, roleHints, migrations, artifactCoords, registerOnly);

        return cluster.apply(commands)
                      .map(_ -> expanded);
    }

    private List<KVCommand<AetherKey>> buildAllCommands(ExpandedBlueprint expanded,
                                                        Option<String> resourcesConfig,
                                                        Map<String, String> roleHints,
                                                        Map<String, List<MigrationEntry>> migrations,
                                                        String artifactCoords,
                                                        boolean registerOnly) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        commands.add(buildBlueprintPutCommand(expanded, registerOnly));
        // Slice META-INF/resources.toml is intentionally NOT published to KV — it is local to
        // each node and applied via the per-slice intrinsic config layer at slice load
        // (see SliceStore.loadSlice). The resourcesConfig parameter is kept here because the
        // ExpandedBlueprint already embeds it for downstream consumers (e.g., schema gating).
        //
        // Stage-3 (stream-namespaces §8.5): the per-blueprint alias→ResourceAddress bindings ARE
        // published to KV (replicated), so the per-slice runtime FSM can resolve refcount targets.
        // rc1's deploy chain has no stream-resource validation gate, so the resolved resource map is
        // (re-)derived here from the embedded resources.toml + roleHints. Derivation is best-effort:
        // on validation failure an empty bindings entry is still written, preserving rc1's deploy
        // semantics (the gate that would HTTP-422 on bad stream config is a separate stage).
        commands.add(buildStreamBindingsCommand(expanded, resourcesConfig, roleHints));
        if (!migrations.isEmpty()) {commands.addAll(buildSchemaMigrationCommands(migrations, artifactCoords));}

        return commands;
    }

    private static KVCommand<AetherKey> buildStreamBindingsCommand(ExpandedBlueprint expanded,
                                                                   Option<String> resourcesConfig,
                                                                   Map<String, String> roleHints) {
        var bindings = StreamResourceValidator.validate(resourcesConfig, expanded.id().artifact(), roleHints)
                                              .map(validated -> toNamedAddresses(expanded.id(), validated))
                                              .or(List.<NamedAddress>of());
        return new Put<>(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(expanded.id()),
                         BlueprintStreamBindingsValue.blueprintStreamBindingsValue(bindings));
    }

    private static List<NamedAddress> toNamedAddresses(BlueprintId blueprintId, ValidatedStreamResources validated) {
        var namespace = BlueprintNamespace.deriveNamespace(blueprintId).or("");
        var collected = new ArrayList<NamedAddress>();
        validated.resources()
                 .forEach((alias, resource) -> resolveBindingEntry(namespace, alias, resource).onPresent(collected::add));
        return List.copyOf(collected);
    }

    private static Option<NamedAddress> resolveBindingEntry(String namespace, String alias, StreamResource resource) {
        return switch (resource) {
            case StreamResource.Owned owned -> resolveOwnedAddress(namespace, alias, owned).map(address -> NamedAddress.namedAddress(alias,
                                                                                                                                      address));
            case StreamResource.External external -> Option.some(NamedAddress.namedAddress(alias, external.target()));
        };
    }

    /// Owned-resource address resolution. The blueprint's derived namespace + the local alias +
    /// the explicit version produce the fully-qualified address. `Latest` version specs have no
    /// concrete address at deploy time — the consumer resolves them at subscribe-time against the
    /// live registry, so they're omitted from the bindings map.
    private static Option<ResourceAddress> resolveOwnedAddress(String namespace, String alias, StreamResource.Owned owned) {
        return switch (owned.version()) {
            case StreamVersionSpec.Exact exact -> ResourceAddress.resourceAddress(namespace, alias, exact.version()).option();
            case StreamVersionSpec.Latest _ -> Option.none();
        };
    }

    private static KVCommand<AetherKey> buildBlueprintPutCommand(ExpandedBlueprint expanded, boolean registerOnly) {
        return new Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                         AppBlueprintValue.appBlueprintValue(expanded, registerOnly));
    }

    private Promise<ExpandedBlueprint> applyResourcesConfig(ExpandedBlueprint expanded,
                                                            Option<String> resourcesConfig) {
        return Promise.success(resourcesConfig.map(rc -> ExpandedBlueprint.expandedBlueprint(expanded.id(),
                                                                                             expanded.loadOrder(),
                                                                                             Option.some(rc),
                                                                                             expanded.securityOverrides()))
                                              .or(expanded));
    }

    private List<KVCommand<AetherKey>> buildSchemaMigrationCommands(Map<String, List<MigrationEntry>> migrations,
                                                                    String artifactCoords) {
        return migrations.entrySet()
                         .stream()
                         .map(entry -> buildMigrationCommand(entry, artifactCoords))
                         .toList();
    }

    private KVCommand<AetherKey> buildMigrationCommand(Map.Entry<String, List<MigrationEntry>> entry,
                                                       String artifactCoords) {
        var datasource = entry.getKey();
        var migrationList = entry.getValue();
        var maxVersion = migrationList.stream().map(MigrationEntry::filename).filter(f -> f.startsWith("V")).mapToInt(BlueprintService::extractVersionNumber).max().orElse(0);
        var lastFilename = migrationList.isEmpty()
                           ? ""
                           : migrationList.getLast().filename();
        var key = SchemaVersionKey.schemaVersionKey(datasource);
        var value = SchemaVersionValue.schemaVersionValue(datasource,
                                                          maxVersion,
                                                          lastFilename,
                                                          SchemaStatus.PENDING,
                                                          artifactCoords);

        return new Put<>(key, value);
    }

    private Promise<ExpandedBlueprint> validatePubSub(ExpandedBlueprint expanded) {
        return loadAllTopologies(expanded.loadOrder()).flatMap(topologies -> PubSubValidator.validate(topologies)
                                                                                            .map(_ -> expanded)
                                                                                            .async());
    }

    private Promise<List<SliceTopology>> loadAllTopologies(List<ResolvedSlice> slices) {
        return Promise.allOf(slices.stream().map(this::loadTopology).toList()).map(BlueprintService::flattenTopologyResults);
    }

    private Promise<List<SliceTopology>> loadTopology(ResolvedSlice slice) {
        return repository.locate(slice.artifact())
                         .map(location -> TopologyParser.parseFromJar(location.url(),
                                                                      slice.artifact().asString())
                                                        .or(List.of()));
    }

    private Promise<ExpandedBlueprint> storeBlueprint(ExpandedBlueprint expanded) {
        return storeBlueprintWithKey(AetherKey.AppBlueprintKey.appBlueprintKey(expanded.id()),
                                     expanded);
    }

    private Promise<ExpandedBlueprint> storeBlueprintWithKey(AetherKey.AppBlueprintKey key,
                                                             ExpandedBlueprint expanded) {
        var value = AppBlueprintValue.appBlueprintValue(expanded);
        KVCommand<AetherKey> command = new Put<>(key, value);

        return cluster.apply(List.of(command))
                      .map(_ -> expanded);
    }

    private Promise<Unit> removeFromStore(AetherKey.AppBlueprintKey key) {
        KVCommand<AetherKey> command = new Remove<>(key);

        return cluster.apply(List.of(command))
                      .mapToUnit();
    }

    private Option<ExpandedBlueprint> extractBlueprint(AetherValue value) {
        return switch (value) {
            case AetherValue.AppBlueprintValue appValue -> Option.some(appValue.blueprint());
            default -> Option.none();
        };
    }
}
