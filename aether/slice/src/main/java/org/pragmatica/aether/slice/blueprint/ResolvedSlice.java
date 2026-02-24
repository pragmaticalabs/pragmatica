package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Set;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;

@SuppressWarnings({"JBCT-NAM-01", "JBCT-UTIL-02"})
public record ResolvedSlice(Artifact artifact, int instances, int minAvailable, boolean isDependency, Set<Artifact> dependencies) {
    private static final Cause NULL_ARTIFACT = Causes.cause("Artifact cannot be null");
    private static final Fn1<Cause, Integer> INVALID_INSTANCES = Causes.forOneValue("Instances must be positive, got: %s");

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact,
                                                      int instances,
                                                      int minAvailable,
                                                      boolean isDependency,
                                                      Set<Artifact> dependencies) {
        return ensure(artifact, Is::notNull, NULL_ARTIFACT).filter(INVALID_INSTANCES.apply(instances),
                                                                   _ -> instances > 0)
                     .map(a -> toResolvedSlice(a, instances, minAvailable, isDependency, dependencies));
    }

    @SuppressWarnings("JBCT-VO-02")
    private static ResolvedSlice toResolvedSlice(Artifact artifact,
                                                 int instances,
                                                 int minAvailable,
                                                 boolean isDependency,
                                                 Set<Artifact> dependencies) {
        var safeDeps = dependencies == null
                       ? Set.<Artifact>of()
                       : Set.copyOf(dependencies);
        return new ResolvedSlice(artifact, instances, minAvailable, isDependency, safeDeps);
    }

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact, int instances, boolean isDependency, Set<Artifact> dependencies) {
        return resolvedSlice(artifact, instances, Math.ceilDiv(instances, 2), isDependency, dependencies);
    }

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact, int instances, boolean isDependency) {
        return resolvedSlice(artifact, instances, isDependency, Set.of());
    }
}
