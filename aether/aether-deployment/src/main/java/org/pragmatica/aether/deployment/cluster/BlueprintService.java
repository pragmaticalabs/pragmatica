// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
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
import org.pragmatica.aether.deployment.schema.SchemaError;
import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator;
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
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

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
    /// Durable terminal outcome of `id`'s last deployment attempt (#759 review, BLOCKING 3). Bounded
    /// to exactly one record per blueprint id: the FSM writes via `KVCommand.Put` at
    /// `AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(id)`, and a Put at the same key overwrites
    /// the prior value, so the store holds only the latest outcome — cardinality is the number of
    /// distinct blueprint ids ever deployed, not the number of attempts. Survives
    /// `unloadBlueprintSlices`'s ALL_OR_NOTHING rollback, which removes only `AppBlueprintKey`, never
    /// this key — the intended read path for the node's blueprint-status route after a rollback
    /// leaves `get(id)` empty while this stays populated.
    ///
    /// #760/#724 review round 2 item i: `Option.empty()` conflates four distinct situations a caller
    /// (e.g. the blueprint-status API route) cannot tell apart from this return value alone:
    ///
    ///   1. **Never deployed** — `id` has never been published/deployed; no attempt was ever made.
    ///   2. **In flight, progressing normally** — a deployment is underway right now and has not yet
    ///      reached a terminal transition; an outcome will land once it does.
    ///   3. **Orphaned by a crash** — the FSM host crashed before issuing the terminal
    ///      `submitBatch`/`apply` call, so no `DeploymentOutcomeKey` entry was ever written; nothing
    ///      further will happen without operator intervention (e.g. a redeploy), but the record looks
    ///      identical to case 2.
    ///   4. **Stuck, never resolving** — no crash occurred, but the FSM is waiting on an event (e.g. a
    ///      `NodeArtifactPutReceived`) that never arrives, so it never reaches a terminal transition
    ///      either; also indistinguishable from case 2 by this method alone.
    ///
    /// A caller needing to tell "will complete soon" (case 2) apart from "permanently stuck, needs
    /// intervention" (cases 3-4) must consult additional state — e.g. `get(id)`'s presence plus how
    /// long the blueprint has been in that state — `outcome()` alone cannot make that distinction.
    ///
    /// #759 review, BLOCKING 3 (fix): `publishFromArtifact` bundles a `KVCommand.Remove` of this key
    /// into the SAME consensus batch as the `AppBlueprintKey` Put that starts the new attempt, so the
    /// two land atomically. This closes the retry gap above case 2: before this fix, publishing a new
    /// attempt of an `id` that had already reached a terminal FAILED/ROLLED_BACK outcome left the STALE
    /// prior record in place until the new attempt's own terminal write, so a caller polling mid-flight
    /// saw the LAST attempt's failure while the new one was actively converging. The guarantee is now:
    /// at any instant, `id` is either in flight with `outcome(id)` empty, or terminal with exactly one
    /// record for the CURRENT attempt — never in flight while this method still reports a previous
    /// attempt's result. This does not resolve cases 3-4 above; it only ensures case 2 is never
    /// confused with a stale case-3/4-shaped terminal record from an earlier attempt of the same id.
    Option<AetherValue.DeploymentOutcomeValue> outcome(BlueprintId id);
    List<ExpandedBlueprint> list();
    Promise<Unit> delete(BlueprintId id);
    Result<Blueprint> validate(String dsl);
    Cause ARTIFACT_STORE_NOT_CONFIGURED = Causes.cause("ArtifactStore not configured");

    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository,
                                             ArtifactStore artifactStore,
                                             Option<ConfigurationProvider> nodeComposite) {
        return new BlueprintServiceInstance(cluster, store, repository, Option.some(artifactStore), nodeComposite);
    }

    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository,
                                             ArtifactStore artifactStore) {
        return new BlueprintServiceInstance(cluster, store, repository, Option.some(artifactStore), Option.empty());
    }

    static BlueprintService blueprintService(ClusterNode<KVCommand<AetherKey>> cluster,
                                             KVStore<AetherKey, AetherValue> store,
                                             Repository repository) {
        return new BlueprintServiceInstance(cluster, store, repository, Option.empty(), Option.empty());
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
    private final Option<ConfigurationProvider> nodeComposite;

    BlueprintServiceInstance(ClusterNode<KVCommand<AetherKey>> cluster,
                             KVStore<AetherKey, AetherValue> store,
                             Repository repository,
                             Option<ArtifactStore> artifactStore,
                             Option<ConfigurationProvider> nodeComposite) {
        this.cluster = cluster;
        this.store = store;
        this.repository = repository;
        this.artifactStore = artifactStore;
        this.nodeComposite = nodeComposite;
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
    public Option<AetherValue.DeploymentOutcomeValue> outcome(BlueprintId id) {
        return store.get(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(id))
                    .filter(AetherValue.DeploymentOutcomeValue.class::isInstance)
                    .map(AetherValue.DeploymentOutcomeValue.class::cast);
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
                                        repository)
                                .flatMap(expanded -> applyResourcesConfig(expanded,
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
        return ensureMigrationOwnership(expanded.id(),
                                        migrations).async()
                                       .flatMap(_ -> applyAllCommands(expanded,
                                                                      resourcesConfig,
                                                                      roleHints,
                                                                      migrations,
                                                                      artifactCoords,
                                                                      registerOnly));
    }

    private Promise<ExpandedBlueprint> applyAllCommands(ExpandedBlueprint expanded,
                                                        Option<String> resourcesConfig,
                                                        Map<String, String> roleHints,
                                                        Map<String, List<MigrationEntry>> migrations,
                                                        String artifactCoords,
                                                        boolean registerOnly) {
        var commands = buildAllCommands(expanded, resourcesConfig, roleHints, migrations, artifactCoords, registerOnly);

        return cluster.apply(commands)
                      .map(_ -> expanded);
    }

    /// Deploy-time single-migrator gate. Every datasource this artifact declares migrations for must
    /// be unclaimed or already claimed by this same blueprint; otherwise the whole publish is
    /// rejected before a single command is applied, so a second migrator never lands in KV.
    /// A blueprint declaring no migrations passes trivially — sharing a datasource for reads and
    /// writes is legal, only duplicate migration ownership is refused.
    ///
    /// Ownership is compared on the blueprint's `ArtifactBase` (group:artifact, version stripped),
    /// matching `ClusterDeploymentState.hasConflictingOwnership`: republishing `my-app:1.0.1` over
    /// records written by `my-app:1.0.0` is the same owner advancing its own schema, not a conflict.
    ///
    /// `firstFailureOf`, not `allOf`: accumulation would fold the conflicts into a composite cause,
    /// and a composite is not `HttpStatusAware` — the publish endpoint would answer 500 instead of
    /// the 409 the typed cause declares. The first conflict is also the whole answer, since the
    /// publish is refused outright either way.
    private Result<Unit> ensureMigrationOwnership(BlueprintId owner, Map<String, List<MigrationEntry>> migrations) {
        return Result.firstFailureOf(migrations.keySet()
                                               .stream()
                                               .map(datasource -> ensureDatasourceUnclaimed(owner, datasource))
                                               .toList()).mapToUnit();
    }

    private Result<Unit> ensureDatasourceUnclaimed(BlueprintId owner, String datasource) {
        return currentMigrationOwner(datasource).filter(current -> !current.base()
                                                                           .equals(owner.base()))
                                    .map(current -> ownershipConflict(datasource, current, owner))
                                    .or(Result::unitResult);
    }

    private static Result<Unit> ownershipConflict(String datasource, BlueprintId current, BlueprintId rejected) {
        return SchemaError.DatasourceOwnershipConflict.datasourceOwnershipConflict(datasource, current, rejected).result();
    }

    private Option<BlueprintId> currentMigrationOwner(String datasource) {
        return store.get(SchemaVersionKey.schemaVersionKey(datasource))
                    .filter(SchemaVersionValue.class::isInstance)
                    .map(SchemaVersionValue.class::cast)
                    .map(SchemaVersionValue::owningBlueprint);
    }

    private List<KVCommand<AetherKey>> buildAllCommands(ExpandedBlueprint expanded,
                                                        Option<String> resourcesConfig,
                                                        Map<String, String> roleHints,
                                                        Map<String, List<MigrationEntry>> migrations,
                                                        String artifactCoords,
                                                        boolean registerOnly) {
        var commands = new ArrayList<KVCommand<AetherKey>>();

        commands.add(buildBlueprintPutCommand(expanded, registerOnly));
        // #759 review, BLOCKING: a fresh publish reuses `expanded.id()` on a retry after a prior
        // FAILED/ROLLED_BACK attempt of the SAME blueprint id, and `outcome(id)` otherwise keeps
        // returning that stale terminal record — indistinguishable from the new attempt already
        // having failed — until the new attempt itself reaches a terminal transition. Bundling this
        // Remove into the SAME consensus batch as the AppBlueprintKey Put above makes the two land
        // atomically, so at any instant `id` is either in flight with no outcome, or terminal with
        // exactly one, never "in flight" while `outcome(id)` still shows the LAST attempt's result.
        // `DeploymentOutcomeValue` is not fenced (no `EpochBearing`/`LeaderValue`), so the witnessless
        // `Remove(key)` constructor is admitted unconditionally by the applier. No FSM event is wired
        // for `DeploymentOutcomeKey` changes (unlike `AppBlueprintKey`'s `handleAppBlueprintChange`/
        // `handleAppBlueprintRemoval`), so this Remove's position in the batch relative to the Put
        // above is NOT load-bearing — nothing subscribes to either notification.
        commands.add(new Remove<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id())));
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
        if (!migrations.isEmpty()) {
            commands.addAll(buildSchemaMigrationCommands(migrations, artifactCoords, expanded.id()));
        }

        return commands;
    }

    private static KVCommand<AetherKey> buildStreamBindingsCommand(ExpandedBlueprint expanded,
                                                                   Option<String> resourcesConfig,
                                                                   Map<String, String> roleHints) {
        var bindings = StreamResourceValidator.validate(resourcesConfig,
                                                        expanded.id().artifact(),
                                                        roleHints)
                                              .map(validated -> toNamedAddresses(expanded.id(),
                                                                                 validated))
                                              .or(List.<NamedAddress> of());

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
    private static Option<ResourceAddress> resolveOwnedAddress(String namespace,
                                                               String alias,
                                                               StreamResource.Owned owned) {
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
                                                                    String artifactCoords,
                                                                    BlueprintId owningBlueprint) {
        return migrations.entrySet()
                         .stream()
                         .map(entry -> buildMigrationCommand(entry, artifactCoords, owningBlueprint))
                         .toList();
    }

    private KVCommand<AetherKey> buildMigrationCommand(Map.Entry<String, List<MigrationEntry>> entry,
                                                       String artifactCoords,
                                                       BlueprintId owningBlueprint) {
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
                           : migrationList.getLast().filename();
        var key = SchemaVersionKey.schemaVersionKey(datasource);
        var value = SchemaVersionValue.schemaVersionValue(datasource,
                                                          maxVersion,
                                                          lastFilename,
                                                          SchemaStatus.PENDING,
                                                          artifactCoords,
                                                          owningBlueprint);

        return new Put<>(key, value);
    }

    private Promise<ExpandedBlueprint> validatePubSub(ExpandedBlueprint expanded) {
        return loadAllTopologies(expanded.loadOrder()).flatMap(topologies -> {
            noteConfigSectionPreflightSkipIfBlind(topologies);

            return PubSubValidator.validate(topologies)
                                  .flatMap(_ -> ConfigSectionPreflightValidator.validate(topologies, nodeComposite))
                                  .map(_ -> expanded)
                                  .async();
        });
    }

    /// Fail-open is a quiet gate by construction (#547): with no [ConfigurationProvider] wired,
    /// [ConfigSectionPreflightValidator] cannot distinguish a present section from an absent one, so
    /// it must not manufacture false positives — but a successful deploy must not read as "checked
    /// and passed" when it was actually "not checked". Same principle as the drain disruption-budget
    /// guard's visible bypass note (`NodeLifecycleRoutes`): a gate that quietly doesn't gate is the
    /// failure mode, so the skip is logged whenever there is at least one resource section it would
    /// otherwise have checked.
    private void noteConfigSectionPreflightSkipIfBlind(List<SliceTopology> topologies) {
        if (nodeComposite.isPresent()) {
            return;
        }

        var resourceCount = topologies.stream().mapToInt(t -> t.resources()
                                                               .size()).sum();

        if (resourceCount > 0) {
            log.warn("Config-section pre-flight (#547) SKIPPED for this deploy: no ConfigurationProvider "
                    + "is wired on this node, so {} declared resource section(s) across {} slice(s) could not "
                    + "be checked against the leader's composite configuration view. Deploy proceeds fail-open — "
                    + "this is 'not checked', not 'checked and passed'.",
                     resourceCount,
                     topologies.size());
        }
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
