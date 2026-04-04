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


/// Expands a Blueprint by resolving all transitive dependencies.
///
/// Process:
/// 1. Collect explicit slices from Blueprint
/// 2. For each slice, resolve transitive dependencies
/// 3. Build dependency graph and detect cycles
/// 4. Topologically sort (dependencies before dependents)
/// 5. Create ExpandedBlueprint with proper instance counts
///
/// Explicit slices keep their instance counts.
/// Transitive dependencies get instances=1, isDependency=true.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-NEST-01", "JBCT-UTIL-02", "JBCT-ZONE-02"}) public interface BlueprintExpander {
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
        return blueprint.slices().stream()
                               .collect(Collectors.toUnmodifiableMap(SliceSpec::artifact, spec -> spec));
    }

    private static Promise<Map<Artifact, Set<Artifact>>> resolveDependencies(Map<Artifact, SliceSpec> explicitSlices,
                                                                             DependencyLoader loader) {
        var processed = new HashSet<Artifact>();
        var dependencies = new HashMap<Artifact, Set<Artifact>>();
        return resolveDependenciesRecursive(explicitSlices.keySet(), loader, processed, dependencies).map(_ -> Collections.unmodifiableMap(dependencies));
    }

    private static Promise<Unit> resolveDependenciesRecursive(Set<Artifact> artifacts,
                                                              DependencyLoader loader,
                                                              Set<Artifact> processed,
                                                              Map<Artifact, Set<Artifact>> dependencies) {
        var toProcess = artifacts.stream().filter(artifact -> !processed.contains(artifact))
                                        .peek(processed::add)
                                        .toList();
        if (toProcess.isEmpty()) {return Promise.success(Unit.unit());}
        return processArtifactsSequentially(toProcess, loader, processed, dependencies, 0);
    }

    private static Promise<Unit> processArtifactsSequentially(List<Artifact> artifacts,
                                                              DependencyLoader loader,
                                                              Set<Artifact> processed,
                                                              Map<Artifact, Set<Artifact>> dependencies,
                                                              int index) {
        if (index >= artifacts.size()) {return Promise.success(Unit.unit());}
        var artifact = artifacts.get(index);
        return loader.loadDependencies(artifact).flatMap(deps -> storeDepsAndRecurse(artifact,
                                                                                     deps,
                                                                                     loader,
                                                                                     processed,
                                                                                     dependencies))
                                      .flatMap(_ -> processArtifactsSequentially(artifacts,
                                                                                 loader,
                                                                                 processed,
                                                                                 dependencies,
                                                                                 index + 1));
    }

    private static Promise<Unit> storeDepsAndRecurse(Artifact artifact,
                                                     Set<Artifact> deps,
                                                     DependencyLoader loader,
                                                     Set<Artifact> processed,
                                                     Map<Artifact, Set<Artifact>> dependencies) {
        dependencies.put(artifact, deps);
        return resolveDependenciesRecursive(deps, loader, processed, dependencies);
    }

    private static Map<String, List<String>> buildDependencyGraph(Map<Artifact, Set<Artifact>> dependencies) {
        return dependencies.entrySet().stream()
                                    .collect(Collectors.toUnmodifiableMap(entry -> ArtifactMapper.toClassName(entry.getKey()),
                                                                          entry -> entry.getValue().stream()
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
        return Result.allOf(sorted.stream().map(artifact -> createResolvedSlice(artifact,
                                                                                explicitSlices,
                                                                                allDependencies))
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
        artifacts.stream().filter(artifact -> !visited.contains(artifact))
                        .forEach(artifact -> topologicalSortDfs(artifact, dependencies, visited, result));
        return result;
    }

    private static void topologicalSortDfs(Artifact artifact,
                                           Map<Artifact, Set<Artifact>> dependencies,
                                           Set<Artifact> visited,
                                           List<Artifact> result) {
        visited.add(artifact);
        dependencies.getOrDefault(artifact,
                                  Set.of()).stream()
                                 .filter(dep -> !visited.contains(dep))
                                 .forEach(dep -> topologicalSortDfs(dep, dependencies, visited, result));
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
