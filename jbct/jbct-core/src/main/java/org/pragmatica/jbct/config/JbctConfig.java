package org.pragmatica.jbct.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.lang.Option;


/// Unified configuration for JBCT tools.
/// Combines formatter and linter configuration with project settings.
public record JbctConfig(FormatterConfig formatter,
                         LintConfig lint,
                         FilesConfig files,
                         BlueprintConfig blueprint,
                         List<String> sourceDirectories,
                         List<String> excludePackages) {
    public JbctConfig {
        sourceDirectories = List.copyOf(sourceDirectories);
        excludePackages = List.copyOf(excludePackages);
    }

    /// Default configuration.
    public static final JbctConfig DEFAULT = jbctConfig(FormatterConfig.DEFAULT,
                                                        LintConfig.DEFAULT,
                                                        FilesConfig.DEFAULT,
                                                        BlueprintConfig.DEFAULT,
                                                        List.of("src/main/java"),
                                                        List.of());

    /// Factory method for creating JbctConfig.
    public static JbctConfig jbctConfig(FormatterConfig formatter,
                                        LintConfig lint,
                                        FilesConfig files,
                                        BlueprintConfig blueprint,
                                        List<String> sourceDirectories,
                                        List<String> excludePackages) {
        return new JbctConfig(formatter, lint, files, blueprint, sourceDirectories, excludePackages);
    }

    /// Create config from parsed TOML document.
    public static JbctConfig fromToml(TomlDocument toml) {
        // Format section
        var formatterConfig = FormatterConfig.DEFAULT.withMaxLineLength(toml.getInt("format", "maxLineLength").or(120))
                                                     .withIndentSize(toml.getInt("format", "indentSize").or(4))
                                                     .withUseTabs(toml.getBoolean("format", "useTabs").or(false))
                                                     .withOrganizeImports(toml.getBoolean("format", "organizeImports")
                                                                              .or(true));
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

        var lintConfig = LintConfig.lintConfig(Map.copyOf(ruleSeverities), Set.copyOf(disabledRules), failOnWarning);
        // Files section
        var maxFileSize = toml.getLong("files", "maxFileSize").or(FilesConfig.DEFAULT.maxFileSize());
        var fileExcludes = toml.getStringList("files", "excludes").or(FilesConfig.DEFAULT.excludes());
        var filesConfig = new FilesConfig(maxFileSize, fileExcludes);
        // Blueprint section
        var schemaMode = toml.getString("blueprint", "schema")
                             .map(BlueprintConfig.SchemaMode::fromString)
                             .or(BlueprintConfig.SchemaMode.REQUIRED);
        var blueprintConfig = new BlueprintConfig(schemaMode);
        // Project section
        var sourceDirectories = toml.getStringList("project", "sourceDirectories").or(List.of("src/main/java"));
        var excludePackages = toml.getStringList("lint", "excludePackages").or(List.of());

        return jbctConfig(formatterConfig,
                          lintConfig,
                          filesConfig,
                          blueprintConfig,
                          sourceDirectories,
                          excludePackages);
    }

    /// Merge this config with another, with other taking precedence.
    public JbctConfig merge(Option<JbctConfig> other) {
        return other.map(this::mergeWith)
                    .or(this);
    }

    private JbctConfig mergeWith(JbctConfig other) {
        var mergedFormatter = other.formatter.equals(FormatterConfig.DEFAULT)
                              ? this.formatter
                              : other.formatter;
        var mergedLint = other.lint.equals(LintConfig.DEFAULT)
                         ? this.lint
                         : other.lint;
        var mergedFiles = other.files.equals(FilesConfig.DEFAULT)
                          ? this.files
                          : other.files;
        var mergedBlueprint = other.blueprint.equals(BlueprintConfig.DEFAULT)
                              ? this.blueprint
                              : other.blueprint;
        var mergedSourceDirs = other.sourceDirectories.equals(List.of("src/main/java"))
                               ? this.sourceDirectories
                               : other.sourceDirectories;
        var mergedExcludePackages = other.excludePackages.isEmpty()
                                    ? this.excludePackages
                                    : other.excludePackages;

        return jbctConfig(mergedFormatter,
                          mergedLint,
                          mergedFiles,
                          mergedBlueprint,
                          mergedSourceDirs,
                          mergedExcludePackages);
    }

    /// Generate TOML representation of this config.
    public String toToml() {
        var sb = new StringBuilder();

        sb.append("# JBCT Configuration\n\n");
        // Format section
        sb.append("[format]\n");
        sb.append("maxLineLength = ").append(formatter.maxLineLength()).append("\n");
        sb.append("indentSize = ").append(formatter.indentSize()).append("\n");
        sb.append("useTabs = ").append(formatter.useTabs()).append("\n");
        sb.append("organizeImports = ").append(formatter.organizeImports()).append("\n");
        sb.append("\n");
        // Files section
        sb.append("[files]\n");
        sb.append("maxFileSize = ").append(files.maxFileSize()).append("\n");
        sb.append("excludes = [");
        sb.append(files.excludes().stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
        sb.append("]\n");
        sb.append("\n");
        // Lint section
        sb.append("[lint]\n");
        sb.append("failOnWarning = ").append(lint.failOnWarning()).append("\n");
        sb.append("excludePackages = [");
        sb.append(excludePackages.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
        sb.append("]\n");

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
        sb.append(sourceDirectories.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
        sb.append("]\n");

        return sb.toString();
    }
}
