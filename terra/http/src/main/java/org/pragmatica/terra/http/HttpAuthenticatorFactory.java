// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;


/// Extension point (#2880) through which an application supplies the launcher's authenticator, discovered with
/// [java.util.ServiceLoader] and selected BY NAME: `terra.toml` says `http.security_mode = "custom"` and
/// `http.authenticator = "<name>"`.
///
/// Selection rules, enforced by the launcher: no installed factory of that name refuses the launch (naming the
/// installed ones); two factories claiming the same name refuse the launch (naming both classes); exactly one is used.
/// Factories with other names are ignored, so several may be installed side by side.
///
/// The authenticator it builds produces the same principal type as the built-in modes (`SecurityContext`), so routes,
/// roles and slices see no difference.
public interface HttpAuthenticatorFactory {
    /// The name `http.authenticator` selects this factory by.
    String name();
    /// Build the authenticator from the host settings; its own settings live under the `authenticator.` prefix of
    /// `terra.toml` (for example `authenticator.<name>.issuer`), which the launcher accepts without interpreting.
    ///
    /// Called once per launch, also by `--check`: it must not open connections or acquire resources before the first
    /// request. A failure refuses the launch; so does a factory whose `name()` or this method throws or returns nothing
    /// (the launch refusal names the provider class). The authenticator returned is wrapped in a [GuardedAuthenticator]:
    /// one that throws per request answers HTTP 500 with a generic body and an ERROR log naming the provider.
    Result<HttpAuthenticator> authenticator(ConfigurationProvider settings);
}
