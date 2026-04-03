package org.pragmatica.aether.update;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Traffic routing ratio between old and new versions.
///
///
/// Uses ratio-based routing (not percentages). For example:
///
///   - `(1, 3)` = 1 new : 3 old (25% to new)
///   - `(1, 1)` = 1 new : 1 old (50% to new)
///   - `(3, 1)` = 3 new : 1 old (75% to new)
///   - `(1, 0)` = 100% to new
///   - `(0, 1)` = 100% to old (initial state)
///
///
///
/// Ratios are scaled to actual instance counts. If ratio cannot be satisfied
/// with available instances (e.g., 1:3 with only 2 old instances), the operation
/// should be rejected.
///
/// @param newWeight weight for new version traffic
/// @param oldWeight weight for old version traffic
public record VersionRouting(int newWeight, int oldWeight) {
    private static final Cause NEGATIVE_WEIGHTS = Causes.cause("Weights must be non-negative");

    private static final Cause NO_POSITIVE_WEIGHT = Causes.cause("At least one weight must be positive");

    private static final Fn1<Cause, String> INVALID_RATIO_FORMAT = Causes.forOneValue("Invalid ratio format. Expected 'new:old', got: %s");

    public static final VersionRouting ALL_OLD = versionRouting(0, 1).unwrap();

    public static final VersionRouting ALL_NEW = versionRouting(1, 0).unwrap();

    public static Result<VersionRouting> versionRouting(int newWeight, int oldWeight) {
        if (newWeight <0 || oldWeight <0) {return NEGATIVE_WEIGHTS.result();}
        if (newWeight == 0 && oldWeight == 0) {return NO_POSITIVE_WEIGHT.result();}
        return Result.success(new VersionRouting(newWeight, oldWeight));
    }

    public static Result<VersionRouting> versionRouting(String ratio) {
        var parts = ratio.split(":");
        if (parts.length != 2) {return INVALID_RATIO_FORMAT.apply(ratio).result();}
        return Result.lift(_ -> INVALID_RATIO_FORMAT.apply(ratio),
                           () -> new VersionRouting(Integer.parseInt(parts[0].trim()),
                                                    Integer.parseInt(parts[1].trim())))
        .flatMap(vr -> versionRouting(vr.newWeight(),
                                      vr.oldWeight()));
    }

    public boolean isAllOld() {
        return newWeight == 0;
    }

    public boolean isAllNew() {
        return oldWeight == 0;
    }

    public int totalWeight() {
        return newWeight + oldWeight;
    }

    public double newVersionPercentage() {
        if (totalWeight() == 0) return 0.0;
        return (double) newWeight / totalWeight() * 100.0;
    }

    public Option<int[]> scaleToInstances(int newInstances, int oldInstances) {
        if (isAllOld()) {return Option.option(new int[]{0, oldInstances});}
        if (isAllNew()) {return Option.option(new int[]{newInstances, 0});}
        int maxNewScale = newInstances / newWeight;
        int maxOldScale = oldInstances / oldWeight;
        int scaleFactor = Math.min(maxNewScale, maxOldScale);
        if (scaleFactor <1) {return Option.none();}
        return Option.option(new int[]{scaleFactor * newWeight, scaleFactor * oldWeight});
    }

    @Override public String toString() {
        return newWeight + ":" + oldWeight;
    }
}
