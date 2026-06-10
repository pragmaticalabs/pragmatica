// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.dependency.ArtifactMapper;
import org.pragmatica.aether.slice.dependency.DependencyCycleDetector;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.*;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-NEST-01", "JBCT-UTIL-02", "JBCT-ZONE-02"})
public interface BlueprintExpander {
    static Promise<ExpandedBlueprint> expand(Blueprint blueprint, Repository repository) {
        return expand(blueprint, RepositoryDependencyLoader.repositoryDependencyLoader(repository));
    }

    static Promise<ExpandedBlueprint> expand(Blueprint blueprint, DependencyLoader loader) {
        var explicitSlices = collectExplicitSlices(blueprint);

        return resolveDependencies(explicitSlices, loader).flatMap(allDeps -> buildExpandedBlueprint(blueprint,
                                                                                                     explicitSlices,
                                                                                                     allDeps));
    }

    private static Promise<ExpandedBlueprint> buildExpandedBlueprint(Blueprint blueprint,
                                                                     Map<Artifact, SliceSpec> explicitSlices,
                                                                     Map<Artifact, Set<Artifact>> allDeps) {
        var graph = buildDependencyGraph(allDeps);

        return checkCycles(graph).flatMap(_ -> buildLoadOrder(explicitSlices, allDeps, graph))
                          .map(loadOrder -> ExpandedBlueprint.expandedBlueprint(blueprint.id(),
                                                                                loadOrder,
                                                                                Option.none(),
                                                                                blueprint.securityOverrides()))
                          .async();
    }

    private static Map<Artifact, SliceSpec> collectExplicitSlices(Blueprint blueprint) {
        return blueprint.slices()
                        .stream()
                        .collect(Collectors.toUnmodifiableMap(SliceSpec::artifact, spec -> spec));
    }

    private static Promise<Map<Artifact, Set<Artifact>>> resolveDependencies(Map<Artifact, SliceSpec> explicitSlices,
                                                                             DependencyLoader loader) {
        var processed = new HashSet<Artifact>();
        var dependencies = new HashMap<Artifact, Set<Artifact>>();

        return resolveLevel(explicitSlices.keySet(), loader, processed, dependencies).map(_ -> Collections.unmodifiableMap(dependencies));
    }

    /// Resolves all artifacts at the current topological LEVEL concurrently, then recurses
    /// into the union of their dependencies as the next level. Concurrency is confined to
    /// dependency DISCOVERY: `loadDependencies` for every artifact at a level runs in
    /// parallel via `Promise.allOf`, collapsing N serial 30s-bounded DHT reads into one
    /// wide fan-out. Correctness is preserved because:
    ///   - `processed` / `dependencies` are mutated ONLY on the single thread that runs
    ///     `mergeLevel` after the fan-out completes (the `Promise` callbacks merely produce
    ///     `(artifact, deps)` values; no shared-map mutation happens off-thread), so no
    ///     `ConcurrentHashMap` is required.
    ///   - artifacts already in `processed` are filtered out before each level and marked
    ///     processed atomically for this single-threaded accumulation, so shared
    ///     dependencies are loaded exactly once.
    ///   - the full `dependencies` map is complete before `buildLoadOrder`/`topologicalSort`
    ///     runs (it runs only after this promise resolves), so topological ordering holds.
    private static Promise<Unit> resolveLevel(Set<Artifact> artifacts,
                                              DependencyLoader loader,
                                              Set<Artifact> processed,
                                              Map<Artifact, Set<Artifact>> dependencies) {
        var toProcess = artifacts.stream().filter(artifact -> !processed.contains(artifact)).peek(processed::add).toList();

        if (toProcess.isEmpty()) {
            return Promise.success(Unit.unit());
        }

        var levelLoads = toProcess.stream().map(artifact -> loadArtifactDeps(artifact, loader)).toList();

        return Promise.allOf(levelLoads)
                      .flatMap(results -> Result.firstFailureOf(results).async())
                      .flatMap(loaded -> recurseIntoNextLevel(loaded, loader, processed, dependencies));
    }

    private static Promise<ArtifactDeps> loadArtifactDeps(Artifact artifact, DependencyLoader loader) {
        return loader.loadDependencies(artifact)
                     .map(deps -> new ArtifactDeps(artifact, deps));
    }

    private static Promise<Unit> recurseIntoNextLevel(List<ArtifactDeps> loaded,
                                                      DependencyLoader loader,
                                                      Set<Artifact> processed,
                                                      Map<Artifact, Set<Artifact>> dependencies) {
        var nextLevel = mergeLevel(loaded, dependencies);

        return resolveLevel(nextLevel, loader, processed, dependencies);
    }

    /// Single-threaded merge of one level's `(artifact, deps)` results into the shared
    /// `dependencies` map, returning the union of all discovered deps as the next level.
    /// Runs on the thread completing the `Promise.allOf` fan-out — never concurrently.
    private static Set<Artifact> mergeLevel(List<ArtifactDeps> loaded, Map<Artifact, Set<Artifact>> dependencies) {
        var nextLevel = new HashSet<Artifact>();

        for (var entry : loaded) {
            dependencies.put(entry.artifact(), entry.deps());
            nextLevel.addAll(entry.deps());
        }

        return nextLevel;
    }

    record ArtifactDeps(Artifact artifact, Set<Artifact> deps) {}

    private static Map<String, List<String>> buildDependencyGraph(Map<Artifact, Set<Artifact>> dependencies) {
        return dependencies.entrySet()
                           .stream()
                           .collect(Collectors.toUnmodifiableMap(entry -> ArtifactMapper.toClassName(entry.getKey()),
                                                                 entry -> entry.getValue()
                                                                               .stream()
                                                                               .map(ArtifactMapper::toClassName)
                                                                               .toList()));
    }

    private static Result<Unit> checkCycles(Map<String, List<String>> graph) {
        return DependencyCycleDetector.checkForCycles(graph);
    }

    private static Result<List<ResolvedSlice>> buildLoadOrder(Map<Artifact, SliceSpec> explicitSlices,
                                                              Map<Artifact, Set<Artifact>> allDependencies,
                                                              Map<String, List<String>> graph) {
        var allArtifacts = collectAllArtifacts(explicitSlices.keySet(), allDependencies);
        var sorted = topologicalSort(allArtifacts, allDependencies);

        return Result.allOf(sorted.stream()
                                  .map(artifact -> createResolvedSlice(artifact, explicitSlices, allDependencies))
                                  .toList());
    }

    private static Set<Artifact> collectAllArtifacts(Set<Artifact> explicit,
                                                     Map<Artifact, Set<Artifact>> dependencies) {
        var all = new HashSet<>(explicit);

        dependencies.values().forEach(all::addAll);

        return all;
    }

    private static List<Artifact> topologicalSort(Set<Artifact> artifacts, Map<Artifact, Set<Artifact>> dependencies) {
        var visited = new HashSet<Artifact>();
        var result = new ArrayList<Artifact>();

        artifacts.stream().filter(artifact -> !visited.contains(artifact)).forEach(artifact -> topologicalSortDfs(artifact,
                                                                                                                  dependencies,
                                                                                                                  visited,
                                                                                                                  result));

        return result;
    }

    private static void topologicalSortDfs(Artifact artifact,
                                           Map<Artifact, Set<Artifact>> dependencies,
                                           Set<Artifact> visited,
                                           List<Artifact> result) {
        visited.add(artifact);
        dependencies.getOrDefault(artifact, Set.of()).stream().filter(dep -> !visited.contains(dep)).forEach(dep -> topologicalSortDfs(dep,
                                                                                                                                       dependencies,
                                                                                                                                       visited,
                                                                                                                                       result));
        result.add(artifact);
    }

    private static Result<ResolvedSlice> createResolvedSlice(Artifact artifact,
                                                             Map<Artifact, SliceSpec> explicitSlices,
                                                             Map<Artifact, Set<Artifact>> allDeps) {
        var deps = allDeps.getOrDefault(artifact, Set.of());

        return option(explicitSlices.get(artifact)).fold(() -> ResolvedSlice.resolvedSlice(artifact, 1, true, deps),
                                                         spec -> ResolvedSlice.resolvedSlice(artifact,
                                                                                             spec.instances(),
                                                                                             spec.minAvailable(),
                                                                                             false,
                                                                                             deps));
    }
}
