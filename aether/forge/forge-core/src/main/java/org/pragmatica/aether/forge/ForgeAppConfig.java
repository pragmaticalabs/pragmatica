// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.file.Path;
import java.util.function.IntConsumer;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.LoggerFactory;


/// The `aether.toml` next to the `--config` file, as Forge reads it for `[app-http]` (API versioning, security mode, API keys).
///
/// Absent file: no configuration, Forge runs on its defaults. A file that EXISTS but does not load or validate is a refusal, not a default:
/// it used to be dropped through `Result::option`, so Forge ran with security NONE for a user who had asked for `jwt` (#909). A node refuses
/// the same config (exit 65); Forge must not be the one place that quietly runs on it.
interface ForgeAppConfig {
    static Result<Option<AetherConfig>> load(Option<Path> forgeConfig) {
        return forgeConfig.map(path -> path.resolveSibling("aether.toml"))
                          .filter(path -> path.toFile()
                                              .exists())
                          .fold(() -> Result.success(Option.<AetherConfig> none()),
                                ForgeAppConfig::loadExisting);
    }

    /// The configuration, or - when a sibling exists and does not load - the cause logged at ERROR and `exit` called with 1 (fail closed). The exit is
    /// injected so the refusal is verified rather than asserted; Forge passes `System::exit`.
    @Contract
    static Option<AetherConfig> loadOrRefuse(Option<Path> forgeConfig, IntConsumer exit) {
        return load(forgeConfig).onFailure(cause -> refuse(cause.message(),
                                                           exit))
                   .or(Option.none());
    }

    /// #962 — the refusal for an explicit `api-key` management mode with no key declared: Forge's own control calls to the
    /// fail-closed management plane would be refused with 401. Names the file, the setting and both ways out.
    static String keylessManagementRefusal(String file) {
        return "refusing to start: " + file
             + " sets [management] security_mode = \"api-key\" but declares no API key, so Forge's own control calls to the"
             + " management plane would be refused with 401. Declare a key ([app-http.api-keys.<key>] or AETHER_API_KEYS),"
             + " or choose another mode: set [management] security_mode = \"none\" or remove the setting.";
    }

    @Contract
    static void refuse(String message, IntConsumer exit) {
        LoggerFactory.getLogger(ForgeAppConfig.class).error("FATAL: {}", message);
        exit.accept(1);
    }

    private static Result<Option<AetherConfig>> loadExisting(Path path) {
        return ConfigLoader.load(path)
                           .map(Option::some)
                           .mapError(cause -> Causes.cause("refusing to start: " + path
                                                          + " could not be loaded or validated: " + cause.message()));
    }
}
