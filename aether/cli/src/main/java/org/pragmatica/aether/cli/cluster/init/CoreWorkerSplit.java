// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.lang.Result;


public record CoreWorkerSplit(int core, int worker) {
    public static Result<CoreWorkerSplit> coreWorkerSplit(int core, int worker) {
        if (core <3) {return new ClusterInitError.InvalidTopology("core must be >= 3, got " + core).result();}
        if (core % 2 == 0) {return new ClusterInitError.InvalidTopology("core must be odd, got " + core).result();}
        if (worker <0) {return new ClusterInitError.InvalidTopology("worker must be >= 0, got " + worker).result();}

        return Result.success(new CoreWorkerSplit(core, worker));
    }

    public int total() {
        return core + worker;
    }
}
