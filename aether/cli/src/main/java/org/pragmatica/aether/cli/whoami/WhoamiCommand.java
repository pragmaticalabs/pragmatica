// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.whoami;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputFormatter;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.WHOAMI;


/// Print the principal, authorization role, and roles attached to the API key
/// (or anonymous viewer context) used to make the request. Useful for
/// integration-test identity assertions and operator triage.
///
/// See `aether/docs/reference/management-api.md` for the wire shape and
/// `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2
/// for the RC1-blocker motivation.
@Command(name = "whoami", description = "Show the authenticated principal, authorization role, and roles")
public class WhoamiCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private AetherCli parent;

    @Override
    public Integer call() {
        var response = parent.fetch(WHOAMI);

        return OutputFormatter.printQuery(response, parent.outputOptions());
    }
}
