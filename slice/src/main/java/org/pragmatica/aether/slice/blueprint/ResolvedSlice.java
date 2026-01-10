package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

public record ResolvedSlice(Artifact artifact, int instances, boolean isDependency) {
    private static final Cause NULL_ARTIFACT = Causes.cause("Artifact cannot be null");
    private static final Fn1<Cause, Integer> INVALID_INSTANCES = Causes.forOneValue("Instances must be positive, got: %s");

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact, int instances, boolean isDependency) {
        if (artifact == null) {
            return NULL_ARTIFACT.result();
        }
        if (instances <= 0) {
            return INVALID_INSTANCES.apply(instances)
                                    .result();
        }
        return Result.success(new ResolvedSlice(artifact, instances, isDependency));
    }
}
