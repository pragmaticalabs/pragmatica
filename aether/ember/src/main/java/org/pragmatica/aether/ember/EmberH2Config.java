// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.pragmatica.lang.Option;


public record EmberH2Config(boolean enabled, int port, String name, boolean persistent, Option<String> initScript) {
    public static final int DEFAULT_PORT = 9092;

    public static final String DEFAULT_NAME = "forge";

    public static final boolean DEFAULT_PERSISTENT = false;

    public static EmberH2Config emberH2Config(boolean enabled,
                                              int port,
                                              String name,
                                              boolean persistent,
                                              Option<String> initScript) {
        return new EmberH2Config(enabled, port, name, persistent, initScript);
    }

    public static EmberH2Config disabled() {
        return new EmberH2Config(false, DEFAULT_PORT, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }

    public static EmberH2Config enabledWithDefaults() {
        return new EmberH2Config(true, DEFAULT_PORT, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }

    public static EmberH2Config enabledOnPort(int port) {
        return new EmberH2Config(true, port, DEFAULT_NAME, DEFAULT_PERSISTENT, Option.none());
    }
}
