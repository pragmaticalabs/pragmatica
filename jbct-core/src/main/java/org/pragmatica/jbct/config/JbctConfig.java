package org.pragmatica.jbct.config;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified configuration for JBCT tools.
 * Combines formatter and linter configuration with project settings.
 */
public record JbctConfig(
        FormatterConfig formatter,
        LintConfig lint,
        List<String> sourceDirectories,
        List<String> businessPackages,
        List<String> slicePackages
) {

    /**
     * Default configuration.
     * Note: slicePackages is empty by default - must be configured for JBCT-SLICE-01 rule.
     */
    public static final JbctConfig DEFAULT = new JbctConfig(
            FormatterConfig.DEFAULT,
            LintConfig.DEFAULT,
            List.of("src/main/java"),
            List.of("**.usecase.**", "**.domain.**"),
            List.of()  // Empty - requires explicit configuration
    );

    /**
     * Create config from parsed TOML document.
     */
    public static JbctConfig fromToml(TomlDocument toml) {
        // Format section
        var formatterConfig = FormatterConfig.DEFAULT
                .withMaxLineLength(toml.getInt("format", "maxLineLength").or(120))
                .withIndentSize(toml.getInt("format", "indentSize").or(4))
                .withUseTabs(toml.getBoolean("format", "useTabs").or(false))
                .withAlignChainedCalls(toml.getBoolean("format", "alignChainedCalls").or(true))
                .withAlignArguments(toml.getBoolean("format", "alignArguments").or(true))
                .withAlignParameters(toml.getBoolean("format", "alignParameters").or(true))
                .withOrganizeImports(toml.getBoolean("format", "organizeImports").or(true));

        // Lint section
        boolean failOnWarning = toml.getBoolean("lint", "failOnWarning").or(false);

        // Lint rules section
        Map<String, DiagnosticSeverity> ruleSeverities = new HashMap<>(LintConfig.DEFAULT.ruleSeverities());
        Set<String> disabledRules = new HashSet<>(LintConfig.DEFAULT.disabledRules());

        var rulesSection = toml.getSection("lint.rules");
        for (var entry : rulesSection.entrySet()) {
            String ruleId = entry.getKey();
            String severityStr = entry.getValue().toLowerCase();

            switch (severityStr) {
                case "off", "disabled" -> disabledRules.add(ruleId);
                case "error" -> {
                    ruleSeverities.put(ruleId, DiagnosticSeverity.ERROR);
                    disabledRules.remove(ruleId);
                }
                case "warning", "warn" -> {
                    ruleSeverities.put(ruleId, DiagnosticSeverity.WARNING);
                    disabledRules.remove(ruleId);
                }
                case "info" -> {
                    ruleSeverities.put(ruleId, DiagnosticSeverity.INFO);
                    disabledRules.remove(ruleId);
                }
            }
        }

        var lintConfig = new LintConfig(
                Map.copyOf(ruleSeverities),
                Set.copyOf(disabledRules),
                failOnWarning
        );

        // Project section
        var sourceDirectories = toml.getStringList("project", "sourceDirectories")
                                    .or(List.of("src/main/java"));
        var businessPackages = toml.getStringList("lint", "businessPackages")
                                   .or(List.of("**.usecase.**", "**.domain.**"));

        // Slice packages - empty by default, must be explicitly configured
        var slicePackages = toml.getStringList("lint", "slicePackages")
                                .or(List.of());

        return new JbctConfig(formatterConfig, lintConfig, sourceDirectories, businessPackages, slicePackages);
    }

    /**
     * Merge this config with another, with other taking precedence.
     */
    public JbctConfig merge(JbctConfig other) {
        if (other == null) {
            return this;
        }

        // Merge formatter config (use other if different from default)
        var mergedFormatter = this.formatter;
        if (!other.formatter.equals(FormatterConfig.DEFAULT)) {
            mergedFormatter = other.formatter;
        }

        // Merge lint config (use other if different from default)
        var mergedLint = this.lint;
        if (!other.lint.equals(LintConfig.DEFAULT)) {
            mergedLint = other.lint;
        }

        // Merge source directories (use other if not default)
        var mergedSourceDirs = this.sourceDirectories;
        if (!other.sourceDirectories.equals(List.of("src/main/java"))) {
            mergedSourceDirs = other.sourceDirectories;
        }

        // Merge business packages (use other if not default)
        var mergedBusinessPackages = this.businessPackages;
        if (!other.businessPackages.equals(List.of("**.usecase.**", "**.domain.**"))) {
            mergedBusinessPackages = other.businessPackages;
        }

        // Merge slice packages (use other if not empty)
        var mergedSlicePackages = this.slicePackages;
        if (!other.slicePackages.isEmpty()) {
            mergedSlicePackages = other.slicePackages;
        }

        return new JbctConfig(mergedFormatter, mergedLint, mergedSourceDirs, mergedBusinessPackages, mergedSlicePackages);
    }

    /**
     * Generate TOML representation of this config.
     */
    public String toToml() {
        var sb = new StringBuilder();

        sb.append("# JBCT Configuration\n\n");

        // Format section
        sb.append("[format]\n");
        sb.append("maxLineLength = ").append(formatter.maxLineLength()).append("\n");
        sb.append("indentSize = ").append(formatter.indentSize()).append("\n");
        sb.append("useTabs = ").append(formatter.useTabs()).append("\n");
        sb.append("alignChainedCalls = ").append(formatter.alignChainedCalls()).append("\n");
        sb.append("alignArguments = ").append(formatter.alignArguments()).append("\n");
        sb.append("alignParameters = ").append(formatter.alignParameters()).append("\n");
        sb.append("organizeImports = ").append(formatter.organizeImports()).append("\n");
        sb.append("\n");

        // Lint section
        sb.append("[lint]\n");
        sb.append("failOnWarning = ").append(lint.failOnWarning()).append("\n");
        sb.append("businessPackages = [");
        sb.append(businessPackages.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", ")));
        sb.append("]\n");
        sb.append("# Slice packages for JBCT-SLICE-01 rule (required for external slice dependency checking)\n");
        sb.append("# Example: slicePackages = [\"**.usecase.**\"]\n");
        if (!slicePackages.isEmpty()) {
            sb.append("slicePackages = [");
            sb.append(slicePackages.stream()
                    .map(s -> "\"" + s + "\"")
                    .collect(Collectors.joining(", ")));
            sb.append("]\n");
        }
        sb.append("\n");

        // Lint rules section
        sb.append("[lint.rules]\n");
        for (var entry : lint.ruleSeverities().entrySet()) {
            if (lint.disabledRules().contains(entry.getKey())) {
                sb.append(entry.getKey()).append(" = \"off\"\n");
            } else {
                sb.append(entry.getKey()).append(" = \"").append(entry.getValue().name().toLowerCase()).append("\"\n");
            }
        }
        sb.append("\n");

        // Project section
        sb.append("[project]\n");
        sb.append("sourceDirectories = [");
        sb.append(sourceDirectories.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", ")));
        sb.append("]\n");

        return sb.toString();
    }
}
