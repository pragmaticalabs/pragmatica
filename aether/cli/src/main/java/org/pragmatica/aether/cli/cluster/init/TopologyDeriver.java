// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.lang.Result;


public sealed interface TopologyDeriver {
    static Result<CoreWorkerSplit> derive(int totalNodes) {
        if (totalNodes <3) {return new ClusterInitError.TooFewNodes(totalNodes).result();}
        return switch (totalNodes) {
            case 3 -> CoreWorkerSplit.coreWorkerSplit(3, 0);
            case 4 -> CoreWorkerSplit.coreWorkerSplit(3, 1);
            case 5 -> CoreWorkerSplit.coreWorkerSplit(3, 2);
            case 6 -> CoreWorkerSplit.coreWorkerSplit(5, 1);
            case 7 -> CoreWorkerSplit.coreWorkerSplit(5, 2);
            case 8 -> CoreWorkerSplit.coreWorkerSplit(5, 3);
            case 9 -> CoreWorkerSplit.coreWorkerSplit(5, 4);
            default -> CoreWorkerSplit.coreWorkerSplit(5, totalNodes - 5);
        };
    }

    record unused() implements TopologyDeriver {}
}
