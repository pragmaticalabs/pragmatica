package org.pragmatica.jbct.config;

import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.jbct.lint.layer.LayerConfig;


/// Unified configuration for JBCT tools.
/// Combines formatter and linter configuration with project settings.
public record JbctConfig(FormatterConfig formatter,
                         LintConfig lint,
                         FilesConfig files,
                         BlueprintConfig blueprint,
                         List<String> sourceDirectories,
                         List<String> excludePackages,
                         LayerConfig layers) {
    public JbctConfig {
        sourceDirectories = List.copyOf(sourceDirectories);
        excludePackages = List.copyOf(excludePackages);
        layers = layers == null
                 ? LayerConfig.DEFAULT
                 : layers;
    }

    /// Default configuration.
    public static final JbctConfig DEFAULT = jbctConfig(FormatterConfig.DEFAULT,
                                                        LintConfig.DEFAULT,
                                                        FilesConfig.DEFAULT,
                                                        BlueprintConfig.DEFAULT,
                                                        List.of("src/main/java"),
                                                        List.of(),
                                                        LayerConfig.DEFAULT);

    /// Factory method for creating JbctConfig.
    public static JbctConfig jbctConfig(FormatterConfig formatter,
                                        LintConfig lint,
                                        FilesConfig files,
                                        BlueprintConfig blueprint,
                                        List<String> sourceDirectories,
                                        List<String> excludePackages,
                                        LayerConfig layers) {
        return new JbctConfig(formatter, lint, files, blueprint, sourceDirectories, excludePackages, layers);
    }

    /// Create config from parsed TOML document, applying built-in defaults to every absent key.
    ///
    /// This materialises a *single* document in isolation. Layered loading goes through
    /// [ConfigLoader], which folds [PartialConfig] layers per key and materialises once at the end.
    public static JbctConfig fromToml(TomlDocument toml) {
        return PartialConfig.partialConfig(toml)
                            .materialize();
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
        // Layering section (issue #452) — omitted when unconfigured (conventions apply).
        if (!layers.isEmpty()) {
            sb.append("\n[lint.layers]\n");
            appendGlobList(sb, "domain", layers.domainGlobs());
            appendGlobList(sb, "application", layers.applicationGlobs());
            appendGlobList(sb, "adapter", layers.adapterGlobs());
            appendGlobList(sb, "bootstrap", layers.bootstrapGlobs());
            appendGlobList(sb, "slices", layers.sliceGlobs());
        }

        return sb.toString();
    }

    private static void appendGlobList(StringBuilder sb, String key, List<String> globs) {
        if (globs.isEmpty()) {
            return;
        }

        sb.append(key)
          .append(" = [")
          .append(globs.stream()
                       .map(s -> "\"" + s + "\"")
                       .collect(Collectors.joining(", ")))
          .append("]\n");
    }
}
