// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;


public class OutputOptions {
    @Option(names = {"--format", "-o"}, description = "Output format: json, table, value, csv", defaultValue = "TABLE", scope = ScopeType.INHERIT, converter = OutputFormatConverter.class)
    private OutputFormat format;

    @Option(names = "--field", description = "Extract specific field (dot-notation, e.g. cluster.leaderId)", scope = ScopeType.INHERIT)
    private String field;

    @Option(names = {"--quiet", "-q"}, description = "Suppress non-essential output", scope = ScopeType.INHERIT)
    private boolean quiet;

    @Option(names = "--no-color", description = "Disable colored output", scope = ScopeType.INHERIT)
    private boolean noColor;

    public boolean isQuiet() {
        return quiet;
    }

    public boolean useColor() {
        return ! noColor && System.getenv("NO_COLOR") == null;
    }

    public OutputFormat format() {
        return field != null
               ? OutputFormat.VALUE
               : format;
    }

    public String field() {
        return field;
    }
}
