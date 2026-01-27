package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;

public record ResolvedSlice(Artifact artifact, int instances, boolean isDependency) {
    private static final Cause NULL_ARTIFACT = Causes.cause("Artifact cannot be null");
    private static final Fn1<Cause, Integer> INVALID_INSTANCES = Causes.forOneValue("Instances must be positive, got: %s");

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact, int instances, boolean isDependency) {
        return ensure(artifact, Is::notNull, NULL_ARTIFACT).filter(INVALID_INSTANCES.apply(instances),
                                                                   _ -> instances > 0)
                     .map(a -> new ResolvedSlice(a, instances, isDependency));
    }
}
