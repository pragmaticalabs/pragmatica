// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.net.URI;
import java.util.Locale;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// The one rule for a usable `[app-http] jwks_url` (#909). Config load and cluster bootstrap (PF-34) both call this, so a bootstrap that
/// passes cannot hand a node a URL the node then refuses.
///
/// Rule: a non-blank absolute URL with a host and the `https` scheme; `http` is accepted only to a loopback host (`localhost`, `127.0.0.1`,
/// `::1`) so a local identity provider works in development. Anything else would fetch signing keys over a channel an attacker can rewrite.
public interface JwksUrl {
    /// The accepted URL, trimmed, or a cause naming what is wrong with it.
    static Result<String> jwksUrl(String raw) {
        return Verify.ensure(raw, Verify.Is::present)
                     .mapError(_ -> Causes.cause("jwks_url is blank"))
                     .map(String::trim)
                     .flatMap(JwksUrl::parse);
    }

    private static Result<String> parse(String url) {
        return Result.lift(_ -> Causes.cause("jwks_url '" + url + "' is not a valid URL"),
                           () -> URI.create(url))
                     .flatMap(uri -> checkAbsolute(url, uri));
    }

    private static Result<String> checkAbsolute(String url, URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            return Causes.cause("jwks_url '" + url + "' must be an absolute URL with a host").result();
        }

        return checkScheme(url, uri);
    }

    private static Result<String> checkScheme(String url, URI uri) {
        var scheme = uri.getScheme().toLowerCase(Locale.ROOT);

        if ("https".equals(scheme) || "http".equals(scheme) && isLoopback(uri.getHost())) {
            return Result.success(url);
        }

        return Causes.cause("jwks_url '" + url + "' must use https (http is accepted only to a loopback host)").result();
    }

    private static boolean isLoopback(String host) {
        var bare = host.startsWith("[") && host.endsWith("]")
                   ? host.substring(1, host.length() - 1)
                   : host;

        return "localhost".equalsIgnoreCase(bare) || "127.0.0.1".equals(bare) || "::1".equals(bare);
    }
}
