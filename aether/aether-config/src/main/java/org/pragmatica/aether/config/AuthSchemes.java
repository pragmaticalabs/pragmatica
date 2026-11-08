// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// The one rule for a usable `auth_schemes` list (#2156): the `Authorization` schemes JWT mode accepts in front of the
/// token. Aether's `[app-http]` load and Terra's `[jwt]` load both call this, so the two runtimes accept and refuse the
/// same lists in the same words.
///
/// Rule: at least one entry; every entry an RFC 7235 `auth-scheme` (an RFC 7230 `token`); no entry named twice. Schemes
/// match case-insensitively (RFC 7235 section 2.1), so `Bearer` and `bearer` are the same entry and naming both is a
/// duplicate. The first entry is the one a `WWW-Authenticate` challenge advertises.
public interface AuthSchemes {
    /// The default: only `Bearer`, so a configuration that does not name `auth_schemes` keeps today's policy.
    List<String> DEFAULT = List.of("Bearer");
    /// RFC 7230 section 3.2.6 `token`: one or more `tchar`.
    Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /// The accepted list, unchanged, or a cause naming what is wrong with it.
    static Result<List<String>> authSchemes(List<String> raw) {
        return Verify.ensure(raw,
                             list -> !list.isEmpty())
                     .mapError(_ -> Causes.cause("auth_schemes is empty: name at least one scheme, e.g. [\"Bearer\"]"))
                     .flatMap(AuthSchemes::checkTokens)
                     .flatMap(AuthSchemes::checkDistinct)
                     .map(List::copyOf);
    }

    private static Result<List<String>> checkTokens(List<String> schemes) {
        return Option.from(schemes.stream().filter(scheme -> !Verify.Is.matches(scheme, TOKEN)).findFirst()).fold(() -> Result.success(schemes),
                                                                                                                  scheme -> Causes.cause("auth_schemes entry '" + scheme
                                                                                                                                        + "' is not an RFC 7235 auth-scheme token").result());
    }

    private static Result<List<String>> checkDistinct(List<String> schemes) {
        var seen = new HashSet<String>();

        return Option.from(schemes.stream().filter(scheme -> !seen.add(scheme.toLowerCase(Locale.ROOT))).findFirst()).fold(() -> Result.success(schemes),
                                                                                                                           scheme -> Causes.cause("auth_schemes names '" + scheme
                                                                                                                                                 + "' more than once (schemes match case-insensitively)").result());
    }
}
