// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.migration;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// One rendered migration script: a plain file name (it orders and identifies the script) and its SQL (#2100 E-B,
/// design §a.8).
public record RenderedScript(String filename, String sql) {
    private static final Pattern PLAIN_NAME = Pattern.compile("[^/\\\\\\s]+");

    public static Result<RenderedScript> renderedScript(String filename, String sql) {
        return Verify.ensure(filename,
                             Verify.Is::present,
                             MigrationSetError.invalidScript(filename))
                     .flatMap(RenderedScript::matchPlainName)
                     .flatMap(name -> requireSql(name, sql))
                     .map(name -> new RenderedScript(name, sql));
    }

    private static Result<String> matchPlainName(String filename) {
        return Verify.ensure(filename, Verify.Is::matches, PLAIN_NAME, MigrationSetError.invalidScript(filename));
    }

    private static Result<String> requireSql(String filename, String sql) {
        return Verify.ensure(sql,
                             Verify.Is::present,
                             MigrationSetError.invalidScript(filename))
                     .map(_ -> filename);
    }
}
