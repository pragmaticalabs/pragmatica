// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Option;


/// Runtime cap, in seconds, on the node-health and quorum-formation waits (`aether cluster bootstrap --wait --timeout N`). Deliberately NOT part
/// of the bootstrap config: the config is hashed to detect a changed config on resume, and a cap there made a resume with a different `--timeout`
/// fail as "Config has changed". Set by the command around one bootstrap call.
final class BootstrapWaitCap {
    static volatile Option<Integer> seconds = Option.none();

    private BootstrapWaitCap() {}

    static long cappedMs(long configuredMs) {
        return seconds.map(cap -> Math.min(configuredMs, cap * 1000L))
                      .or(configuredMs);
    }
}
