// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.util.function.Supplier;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarningSink;


/// What every subscription of one [DefaultConfigChangeFeed] shares: how a record is read from one consistent
/// configuration state, how long a callback may run, and where operator events go.
record WatchEnvironment(ConfigReadProtocol readProtocol, TimeSpan callbackTimeout, Supplier<OperatorWarningSink> sink) {
    static WatchEnvironment watchEnvironment(ConfigReadProtocol readProtocol,
                                             TimeSpan callbackTimeout,
                                             Supplier<OperatorWarningSink> sink) {
        return new WatchEnvironment(readProtocol, callbackTimeout, sink);
    }
}
