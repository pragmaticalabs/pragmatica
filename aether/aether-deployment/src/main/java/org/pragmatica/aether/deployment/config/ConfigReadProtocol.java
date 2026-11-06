// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Runs a read against ONE configuration state, or fails; never returns a torn result. The node's dynamic overlay
/// provides the real one (`DynamicConfigurationProvider#atomically`); a node with no overlay has nothing that can
/// change under a read ([#unprotected]).
public interface ConfigReadProtocol {
    <R> Result<R> atomically(Fn0<R> read);

    static ConfigReadProtocol unprotected() {
        return ConfigReadProtocol::direct;
    }

    private static <R> Result<R> direct(Fn0<R> read) {
        return success(read.apply());
    }
}
