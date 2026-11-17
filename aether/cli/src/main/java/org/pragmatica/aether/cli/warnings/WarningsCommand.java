// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.warnings;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputFormatter;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.WARNINGS;


/// Print the open FSM warnings of one committed state, with the committed revision of the core that answered (#3274). The answering node
/// refuses (exit non-zero, HTTP 503) when it is a worker, holds no quorum, has committed nothing yet, or its revision label is not exact;
/// point `--connect` at another core, or retry once the refusal clears. There is no forwarding.
///
/// See `aether/docs/reference/management-api.md` for the wire shape.
@Command(name = "warnings", description = "Show the open FSM warnings of the connected core node (refuses on a worker or a core without quorum)")
public class WarningsCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private AetherCli parent;

    @Override
    public Integer call() {
        var response = parent.fetch(WARNINGS);

        return OutputFormatter.printQuery(response, parent.outputOptions());
    }
}
