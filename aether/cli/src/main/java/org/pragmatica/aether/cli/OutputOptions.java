package org.pragmatica.aether.cli;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/// Picocli mixin for output formatting options.
/// ScopeType.INHERIT propagates options from AetherCli to all subcommands.
/// Note: picocli requires mutable fields for option injection.
public class OutputOptions {
    @Option(names = {"--format", "-o"}, description = "Output format: json, table, value, csv",
            defaultValue = "TABLE", scope = ScopeType.INHERIT, converter = OutputFormatConverter.class)
    private OutputFormat format;

    @Option(names = "--field", description = "Extract specific field (dot-notation, e.g. cluster.leaderId)",
            scope = ScopeType.INHERIT)
    private String field;

    @Option(names = {"--quiet", "-q"}, description = "Suppress non-essential output",
            scope = ScopeType.INHERIT)
    private boolean quiet;

    @Option(names = "--no-color", description = "Disable colored output",
            scope = ScopeType.INHERIT)
    private boolean noColor;

    /// Returns whether quiet mode is enabled.
    public boolean isQuiet() {
        return quiet;
    }

    /// Returns whether colored output should be used.
    /// Respects both --no-color flag and NO_COLOR environment variable.
    public boolean useColor() {
        return !noColor && System.getenv("NO_COLOR") == null;
    }

    /// Returns the effective output format.
    /// When --field is set, overrides to VALUE format.
    public OutputFormat format() {
        return field != null ? OutputFormat.VALUE : format;
    }

    /// Returns the field path for extraction, or null if not set.
    public String field() {
        return field;
    }
}
