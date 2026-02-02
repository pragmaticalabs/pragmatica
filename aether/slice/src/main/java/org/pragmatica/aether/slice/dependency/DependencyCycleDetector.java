package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.*;

/**
 * Detects circular dependencies in slice dependency graphs.
 * <p>
 * Uses depth-first search with visited/visiting tracking to detect cycles.
 * A cycle exists if we encounter a node that is currently being visited (in the current DFS path).
 */
public interface DependencyCycleDetector {
    /**
     * Check for circular dependencies in the dependency graph.
     * <p>
     * The graph is represented as a map from slice class name to its dependencies.
     *
     * @param dependencies Map from slice class name to list of dependency class names
     *
     * @return Success with Unit if no cycles, failure with cycle path if cycle detected
     */
    static Result<Unit> checkForCycles(Map<String, List<String>> dependencies) {
        var visited = new HashSet<String>();
        var visiting = new HashSet<String>();
        var path = new ArrayList<String>();
        for (var node : dependencies.keySet()) {
            if (!visited.contains(node)) {
                var cycleResult = dfs(node, dependencies, visited, visiting, path);
                if (cycleResult.isFailure()) {
                    return cycleResult;
                }
            }
        }
        return Result.unitResult();
    }

    private static Result<Unit> dfs(String node,
                                    Map<String, List<String>> dependencies,
                                    Set<String> visited,
                                    Set<String> visiting,
                                    List<String> path) {
        visiting.add(node);
        path.add(node);
        var nodeDeps = dependencies.getOrDefault(node, List.of());
        for (var dep : nodeDeps) {
            if (visiting.contains(dep)) {
                // Found a cycle
                var cyclePath = buildCyclePath(path, dep);
                return Causes.cause("Circular dependency detected: " + cyclePath)
                             .result();
            }
            if (!visited.contains(dep)) {
                var result = dfs(dep, dependencies, visited, visiting, path);
                if (result.isFailure()) {
                    return result;
                }
            }
        }
        visiting.remove(node);
        visited.add(node);
        path.removeLast();
        return Result.unitResult();
    }

    private static String buildCyclePath(List<String> path, String cycleStart) {
        var cycleIndex = path.indexOf(cycleStart);
        var cycle = new ArrayList<>(path.subList(cycleIndex, path.size()));
        cycle.add(cycleStart);
        // Close the cycle
        return String.join(" -> ", cycle);
    }

    // Error constants
    Fn1<Cause, String> CIRCULAR_DEPENDENCY = Causes.forOneValue("Circular dependency detected: %s");
}
