package org.pragmatica.jbct.lint;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.jbct.lint.layer.LayerClassifier;
import org.pragmatica.jbct.lint.layer.LayerConfig;


/// Context for lint analysis providing configuration.
public record LintContext(List<Pattern> excludedPackagePatterns,
                          LintConfig config,
                          String fileName,
                          LayerClassifier layers) {
    public LintContext {
        excludedPackagePatterns = List.copyOf(excludedPackagePatterns);
    }

    /// Check if a package should be linted (not in excluded list).
    public boolean shouldLint(String packageName) {
        if (excludedPackagePatterns.isEmpty()) {
            return true;
        }

        return excludedPackagePatterns.stream()
                                      .noneMatch(pattern -> pattern.matcher(packageName)
                                                                   .matches());
    }

    /// Get the configured severity for a rule.
    public DiagnosticSeverity severityFor(String ruleId) {
        return config.ruleSeverities()
                     .getOrDefault(ruleId, DiagnosticSeverity.WARNING);
    }

    /// Check if a rule is enabled.
    public boolean isRuleEnabled(String ruleId) {
        return ! config.disabledRules()
                       .contains(ruleId);
    }

    /// Factory method with default configuration.
    public static LintContext defaultContext() {
        return new LintContext(List.of(), LintConfig.defaultConfig(), "Unknown.java", LayerClassifier.conventionsOnly());
    }

    /// Factory method with custom excluded package patterns.
    public static LintContext lintContext(List<String> excludePackages) {
        var patterns = excludePackages.stream().map(LintContext::globToRegex).map(Pattern::compile).toList();

        return new LintContext(patterns, LintConfig.defaultConfig(), "Unknown.java", LayerClassifier.conventionsOnly());
    }

    private static String globToRegex(String glob) {
        // Use placeholder to avoid ** being affected by * replacement; escape literal dots so a
        // glob segment separator matches only a real '.'.
        return glob.replace("**", "\0DOTSTAR\0")
                   .replace("*", "[^.]*")
                   .replace(".", "\\.")
                   .replace("\0DOTSTAR\0", ".*");
    }

    /// Builder-style method to set config.
    public LintContext withConfig(LintConfig config) {
        return new LintContext(excludedPackagePatterns, config, fileName, layers);
    }

    /// Builder-style method to set file name.
    public LintContext withFileName(String fileName) {
        return new LintContext(excludedPackagePatterns, config, fileName, layers);
    }

    /// Builder-style method to set excluded package patterns from glob strings.
    public LintContext withExcludePackages(List<String> patterns) {
        var compiledPatterns = patterns.stream().map(LintContext::globToRegex).map(Pattern::compile).toList();

        return new LintContext(compiledPatterns, config, fileName, layers);
    }

    /// Builder-style method to set the package-classification engine from layer config.
    public LintContext withLayers(LayerConfig layerConfig) {
        return new LintContext(excludedPackagePatterns, config, fileName, LayerClassifier.from(layerConfig));
    }

    /// Factory method from JbctConfig.
    public static LintContext fromConfig(org.pragmatica.jbct.config.JbctConfig jbctConfig) {
        return lintContext(jbctConfig.excludePackages()).withConfig(jbctConfig.lint())
                                                        .withLayers(jbctConfig.layers());
    }
}
