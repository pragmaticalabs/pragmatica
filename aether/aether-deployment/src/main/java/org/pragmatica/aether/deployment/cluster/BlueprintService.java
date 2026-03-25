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
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintResourcesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintResourcesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
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

/// Service for managing application blueprints in the cluster.
///
/// Blueprints define the desired cluster topology (which slices to deploy with how many instances).
/// BlueprintService handles CRUD operations for blueprints, storing them in the consensus KV-Store.
///
/// Operations:
/// - publish: Parse DSL, expand dependencies, store in KV-Store
/// - publishFromArtifact: Resolve artifact, parse blueprint JAR, expand and store
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

    /// Publish a blueprint from an artifact stored in ArtifactStore.
    /// Resolves the artifact, parses it as a blueprint JAR, expands dependencies,
    /// and stores the blueprint with its resources configuration in the KV-Store.
    ///
    /// @param artifactCoords Maven artifact coordinates (groupId:artifactId:version)
    ///
    /// @return ExpandedBlueprint after successful publication
    Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords);

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

    Cause ARTIFACT_STORE_NOT_CONFIGURED = Causes.cause("ArtifactStore not configured");

    /// Create a BlueprintService with artifact store support.
    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository,
                                             ArtifactStore artifactStore) {
        return new BlueprintServiceInstance(cluster, store, repository, Option.some(artifactStore));
    }

    /// Create a BlueprintService without artifact store support (backward compatibility).
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
        if (underscoreIdx <= 1) {
            return 0;
        }
        var numPart = filename.substring(1, underscoreIdx);
        return Result.lift1(Causes::fromThrowable, Integer::parseInt, numPart)
                     .or(0);
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
        var parsed = parseArtifactWithClassifier(artifactCoords);
        return parsed.artifact()
                     .async()
                     .flatMap(artifact -> resolveArtifactBytes(artifact,
                                                               parsed.classifier()))
                     .flatMap(jarBytes -> BlueprintArtifactParser.parse(jarBytes)
                                                                 .async())
                     .flatMap(artifact -> expandAndStoreArtifact(artifact,
                                                                 parsed.baseCoords()))
                     .onFailure(cause -> log.warn("Failed to publish blueprint from artifact: {}",
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
        return ParsedArtifactCoords.parsedArtifactCoords(MISSING_CLASSIFIER.result(), "", coords);
    }

    private Promise<byte[]> resolveFromArtifactStore(Artifact artifact) {
        return artifactStore.async(ARTIFACT_STORE_NOT_CONFIGURED)
                            .flatMap(store -> store.resolve(artifact));
    }

    @SuppressWarnings("JBCT-EX-01") // Infrastructure I/O: URL stream reading
    private static Promise<byte[]> readLocationBytes(Location location) {
        return Promise.lift(Causes::fromThrowable, () -> readStreamBytes(location));
    }

    @SuppressWarnings("JBCT-EX-01") // Adapter boundary: called within Promise.lift
    private static byte[] readStreamBytes(Location location) throws Exception {
        try (var stream = location.url()
                                  .openStream()) {
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
        return removeFromStore(AetherKey.AppBlueprintKey.appBlueprintKey(id))
        .onFailure(cause -> log.warn("Failed to delete blueprint {}: {}", id.asString(), cause.message()));
    }

    @Override
    public Result<Blueprint> validate(String dsl) {
        return BlueprintParser.parse(dsl);
    }

    private Promise<ExpandedBlueprint> expandAndStoreArtifact(BlueprintArtifact blueprintArtifact,
                                                              String artifactCoords) {
        return BlueprintExpander.expand(blueprintArtifact.blueprint(),
                                        repository)
                                .flatMap(expanded -> applyResourcesConfig(expanded,
                                                                          blueprintArtifact.resourcesConfig()))
                                .flatMap(this::validatePubSub)
                                .flatMap(expanded -> storeAllInSingleBatch(expanded,
                                                                           blueprintArtifact.resourcesConfig(),
                                                                           blueprintArtifact.schemaMigrations(),
                                                                           artifactCoords));
    }

    private Promise<ExpandedBlueprint> storeAllInSingleBatch(ExpandedBlueprint expanded,
                                                             Option<String> resourcesConfig,
                                                             Map<String, List<MigrationEntry>> migrations,
                                                             String artifactCoords) {
        var commands = buildAllCommands(expanded, resourcesConfig, migrations, artifactCoords);
        return cluster.apply(commands)
                      .map(_ -> expanded);
    }

    private List<KVCommand<AetherKey>> buildAllCommands(ExpandedBlueprint expanded,
                                                        Option<String> resourcesConfig,
                                                        Map<String, List<MigrationEntry>> migrations,
                                                        String artifactCoords) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        commands.add(buildBlueprintPutCommand(expanded));
        resourcesConfig.onPresent(content -> commands.add(buildResourcesPutCommand(expanded, content)));
        if (!migrations.isEmpty()) {
            commands.addAll(buildSchemaMigrationCommands(migrations, artifactCoords));
        }
        return commands;
    }

    private static KVCommand<AetherKey> buildBlueprintPutCommand(ExpandedBlueprint expanded) {
        return new Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                         AppBlueprintValue.appBlueprintValue(expanded));
    }

    private static KVCommand<AetherKey> buildResourcesPutCommand(ExpandedBlueprint expanded, String content) {
        return new Put<>(BlueprintResourcesKey.blueprintResourcesKey(expanded.id()),
                         BlueprintResourcesValue.blueprintResourcesValue(content));
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
        var maxVersion = migrationList.stream()
                                      .map(MigrationEntry::filename)
                                      .filter(f -> f.startsWith("V"))
                                      .mapToInt(BlueprintService::extractVersionNumber)
                                      .max()
                                      .orElse(0);
        var lastFilename = migrationList.isEmpty()
                           ? ""
                           : migrationList.getLast()
                                          .filename();
        var key = SchemaVersionKey.schemaVersionKey(datasource);
        var value = SchemaVersionValue.schemaVersionValue(datasource,
                                                          maxVersion,
                                                          lastFilename,
                                                          SchemaStatus.PENDING,
                                                          artifactCoords);
        return new Put<>(key, value);
    }

    private Promise<ExpandedBlueprint> validatePubSub(ExpandedBlueprint expanded) {
        return loadAllTopologies(expanded.loadOrder())
        .flatMap(topologies -> PubSubValidator.validate(topologies)
                                              .map(_ -> expanded)
                                              .async());
    }

    private Promise<List<SliceTopology>> loadAllTopologies(List<ResolvedSlice> slices) {
        return Promise.allOf(slices.stream()
                                   .map(this::loadTopology)
                                   .toList())
                      .map(BlueprintService::flattenTopologyResults);
    }

    private Promise<List<SliceTopology>> loadTopology(ResolvedSlice slice) {
        return repository.locate(slice.artifact())
                         .map(location -> TopologyParser.parseFromJar(location.url(),
                                                                      slice.artifact()
                                                                           .asString())
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
