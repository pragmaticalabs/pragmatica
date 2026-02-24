package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;

@SuppressWarnings("JBCT-UTIL-02")
public record SliceSpec(Artifact artifact, int instances, int minAvailable) {
    private static final Fn1<Cause, Integer> INVALID_INSTANCES = Causes.forOneValue("Instance count must be positive: %s");
    private static final Fn1<Cause, String> INVALID_MIN_AVAILABLE = Causes.forOneValue("minAvailable must be >= 1 and <= instances: %s");

    public static Result<SliceSpec> sliceSpec(Artifact artifact, int instances, int minAvailable) {
        if (instances <= 0) {
            return INVALID_INSTANCES.apply(instances)
                                    .result();
        }
        if (minAvailable < 1 || minAvailable > instances) {
            return INVALID_MIN_AVAILABLE.apply("minAvailable=" + minAvailable + ", instances=" + instances)
                                        .result();
        }
        return success(new SliceSpec(artifact, instances, minAvailable));
    }

    public static Result<SliceSpec> sliceSpec(Artifact artifact, int instances) {
        return sliceSpec(artifact, instances, Math.ceilDiv(instances, 2));
    }

    public static Result<SliceSpec> sliceSpec(Artifact artifact) {
        return sliceSpec(artifact, 1, 1);
    }
}
