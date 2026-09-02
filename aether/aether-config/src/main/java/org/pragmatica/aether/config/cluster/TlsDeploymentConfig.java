// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.config.ConfigKeyLive;


/// `certTtl` is #693: parsed, defaulted to `"720h"`, and asserted by a parser test, but no production
/// code reads this accessor — auto-generated cert lifetime is not currently wired to it.
/// `@ConfigKeyLive`-suppressed rather than deleted: #693 owns the fix, not #519's dead-surface guard.
/// (`clusterSecret` is genuinely live — read only from `aether/cli` call sites, e.g.
/// `ClusterBootstrapOrchestrator`/`BootstrapPhaseDeploy`/`BootstrapPhaseProvision`, a module `node`
/// doesn't depend on; `Main.resolveClusterSecret(TlsConfig)`'s own `tlsCfg.clusterSecret()` is an
/// unrelated same-named accessor on a different type, `TlsConfig`, not this record. Only `certTtl` is
/// dead here.)
public record TlsDeploymentConfig(boolean autoGenerate,
                                  Option<String> clusterSecret,
                                  @ConfigKeyLive("#693: parsed, defaulted, tested — never read by production code") String certTtl) {
    public static TlsDeploymentConfig tlsDeploymentConfig(boolean autoGenerate,
                                                          Option<String> clusterSecret,
                                                          String certTtl) {
        return new TlsDeploymentConfig(autoGenerate, clusterSecret, certTtl);
    }

    public static TlsDeploymentConfig defaultTlsConfig() {
        return new TlsDeploymentConfig(true, Option.none(), "720h");
    }
}
