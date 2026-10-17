// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.aether.config.ConfigKeyLive;


/// `certTtl` is #693: parsed, defaulted to `"720h"`, and asserted by a parser test, but no production
/// code reads this accessor — auto-generated cert lifetime is not currently wired to it.
/// `@ConfigKeyLive`-suppressed rather than deleted: #693 owns the fix, not #519's dead-surface guard.
public record TlsDeploymentConfig(boolean autoGenerate,
                                  @ConfigKeyLive("#693: parsed, defaulted, tested — never read by production code") String certTtl) {
    public static TlsDeploymentConfig tlsDeploymentConfig(boolean autoGenerate, String certTtl) {
        return new TlsDeploymentConfig(autoGenerate, certTtl);
    }

    public static TlsDeploymentConfig defaultTlsConfig() {
        return new TlsDeploymentConfig(true, "720h");
    }
}
