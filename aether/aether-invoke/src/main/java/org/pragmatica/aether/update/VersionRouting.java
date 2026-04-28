// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


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
