package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Thread-safe registry for tracking loaded slice instances.
///
/// Maps artifacts to their loaded slice instances. Supports:
/// - Registration of loaded slices
/// - Lookup by exact artifact
/// - Lookup by class name with version pattern matching
/// - Thread-safe concurrent access
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01", "JBCT-ZONE-03"}) public interface SliceRegistry {
    static SliceRegistry sliceRegistry() {
        return new SliceRegistryImpl(new ConcurrentHashMap<>());
    }

    Result<Unit> register(Artifact artifact, Slice slice);
    Result<Unit> unregister(Artifact artifact);
    Option<Slice> lookup(Artifact artifact);
    Option<Slice> find(String className, VersionPattern versionPattern);
    List<Artifact> allArtifacts();
    Option<Slice> findByArtifactKey(String groupId, String artifactId, VersionPattern versionPattern);

    record SliceRegistryImpl(ConcurrentMap<Artifact, Slice> registry) implements SliceRegistry {
        @Override public Result<Unit> register(Artifact artifact, Slice slice) {
            return option(registry.putIfAbsent(artifact, slice)).fold(Result::unitResult,
                                                                      _ -> ALREADY_REGISTERED.apply(artifact.asString())
                                                                                                   .result());
        }

        @Override public Result<Unit> unregister(Artifact artifact) {
            return option(registry.remove(artifact)).toResult(NOT_FOUND.apply(artifact.asString())).mapToUnit();
        }

        @Override public Option<Slice> lookup(Artifact artifact) {
            return option(registry.get(artifact));
        }

        @Override public Option<Slice> find(String className, VersionPattern versionPattern) {
            return registry.entrySet().stream()
                                    .filter(entry -> matchesClassName(entry.getKey(),
                                                                      className))
                                    .filter(entry -> versionPattern.matches(entry.getKey().version()))
                                    .map(Map.Entry::getValue)
                                    .findFirst()
                                    .map(Option::option)
                                    .orElse(none());
        }

        @Override public List<Artifact> allArtifacts() {
            return List.copyOf(registry.keySet());
        }

        @Override public Option<Slice> findByArtifactKey(String groupId,
                                                         String artifactId,
                                                         VersionPattern versionPattern) {
            return registry.entrySet().stream()
                                    .filter(entry -> entry.getKey().groupId()
                                                                 .id()
                                                                 .equals(groupId))
                                    .filter(entry -> entry.getKey().artifactId()
                                                                 .id()
                                                                 .equals(artifactId))
                                    .filter(entry -> versionPattern.matches(entry.getKey().version()))
                                    .map(Map.Entry::getValue)
                                    .findFirst()
                                    .map(Option::option)
                                    .orElse(none());
        }

        private boolean matchesClassName(Artifact artifact, String className) {
            return artifact.artifactId().id()
                                      .equals(extractSimpleName(className));
        }

        private String extractSimpleName(String className) {
            var lastDot = className.lastIndexOf('.');
            return lastDot >= 0
                  ? className.substring(lastDot + 1)
                  : className;
        }

        private static final Fn1<Cause, String> ALREADY_REGISTERED = Causes.forOneValue("Artifact already registered: %s");

        private static final Fn1<Cause, String> NOT_FOUND = Causes.forOneValue("Artifact not found in registry: %s");
    }
}
