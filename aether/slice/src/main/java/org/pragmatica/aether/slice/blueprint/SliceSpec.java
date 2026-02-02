package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

public record SliceSpec(Artifact artifact, int instances) {
    private static final Fn1<Cause, Integer> INVALID_INSTANCES = Causes.forOneValue("Instance count must be positive: %s");

    public static Result<SliceSpec> sliceSpec(Artifact artifact, int instances) {
        if (instances <= 0) {
            return INVALID_INSTANCES.apply(instances)
                                    .result();
        }
        return Result.success(new SliceSpec(artifact, instances));
    }

    public static Result<SliceSpec> sliceSpec(Artifact artifact) {
        return sliceSpec(artifact, 1);
    }
}
