package org.pragmatica.jbct.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.jbct.format.FormatterConfig;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.jbct.lint.layer.LayerConfig;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// One configuration layer, parsed but not yet materialised (issue #532).
///
/// Every key is an [Option], so "this layer says nothing about the key" is distinct from "this
/// layer explicitly sets the key to the value that happens to be the built-in default". That
/// distinction is what makes layering work at all:
///
/// 1. [#partialConfig] captures only the keys physically present in a document;
/// 2. [#mergeWith] folds layers key by key, nearest wins;
/// 3. [#materialize] applies the built-in defaults exactly once, at the end.
///
/// The previous section-level merge replaced whole records whenever a layer's section differed
/// from its default, discarding every sibling key a lower layer had set — which silently reverted
/// `failOnWarning = true` from `~/.jbct/config.toml` as soon as a project `jbct.toml` named a
/// single rule severity. Per-key folding removes that failure mode by construction rather than by
/// special case, and makes an explicit empty list (`excludes = []`) a real override.
record PartialConfig(PartialFormatter formatter,
                     PartialLint lint,
                     PartialFiles files,
                     PartialBlueprint blueprint,
                     Option<List<String>> sourceDirectories,
                     Option<List<String>> excludePackages,
                     PartialLayers layers) {
    /// A layer that sets nothing — the identity of [#mergeWith].
    static final PartialConfig EMPTY = new PartialConfig(PartialFormatter.EMPTY,
                                                         PartialLint.EMPTY,
                                                         PartialFiles.EMPTY,
                                                         PartialBlueprint.EMPTY,
                                                         none(),
                                                         none(),
                                                         PartialLayers.EMPTY);

    /// Parse a single layer from a TOML document.
    static PartialConfig partialConfig(TomlDocument toml) {
        return new PartialConfig(PartialFormatter.partialFormatter(toml),
                                 PartialLint.partialLint(toml),
                                 PartialFiles.partialFiles(toml),
                                 PartialBlueprint.partialBlueprint(toml),
                                 toml.getStringList("project", "sourceDirectories"),
                                 toml.getStringList("lint", "excludePackages"),
                                 PartialLayers.partialLayers(toml));
    }

    /// Fold a nearer (higher priority) layer onto this one — every key it sets wins, key by key.
    PartialConfig mergeWith(PartialConfig nearer) {
        return new PartialConfig(formatter.mergeWith(nearer.formatter),
                                 lint.mergeWith(nearer.lint),
                                 files.mergeWith(nearer.files),
                                 blueprint.mergeWith(nearer.blueprint),
                                 nearer.sourceDirectories.orElse(sourceDirectories),
                                 nearer.excludePackages.orElse(excludePackages),
                                 layers.mergeWith(nearer.layers));
    }

    /// Apply the built-in defaults to every key still absent after folding.
    JbctConfig materialize() {
        return JbctConfig.jbctConfig(formatter.materialize(),
                                     lint.materialize(),
                                     files.materialize(),
                                     blueprint.materialize(),
                                     sourceDirectories.or(JbctConfig.DEFAULT.sourceDirectories()),
                                     excludePackages.or(JbctConfig.DEFAULT.excludePackages()),
                                     layers.materialize());
    }

    /// `[format]` section of one layer.
    record PartialFormatter(Option<Integer> maxLineLength,
                            Option<Integer> indentSize,
                            Option<Boolean> useTabs,
                            Option<Boolean> organizeImports) {
        static final PartialFormatter EMPTY = new PartialFormatter(none(), none(), none(), none());

        static PartialFormatter partialFormatter(TomlDocument toml) {
            return new PartialFormatter(toml.getInt("format", "maxLineLength"),
                                        toml.getInt("format", "indentSize"),
                                        toml.getBoolean("format", "useTabs"),
                                        toml.getBoolean("format", "organizeImports"));
        }

        PartialFormatter mergeWith(PartialFormatter nearer) {
            return new PartialFormatter(nearer.maxLineLength.orElse(maxLineLength),
                                        nearer.indentSize.orElse(indentSize),
                                        nearer.useTabs.orElse(useTabs),
                                        nearer.organizeImports.orElse(organizeImports));
        }

        FormatterConfig materialize() {
            return FormatterConfig.formatterConfig(maxLineLength.or(FormatterConfig.DEFAULT.maxLineLength()),
                                                   indentSize.or(FormatterConfig.DEFAULT.indentSize()),
                                                   useTabs.or(FormatterConfig.DEFAULT.useTabs()),
                                                   organizeImports.or(FormatterConfig.DEFAULT.organizeImports()));
        }
    }

    /// `[lint]` scalars plus the per-rule settings written in `[lint.rules]` by one layer.
    ///
    /// Rule settings are held as a per-rule map rather than as the linter's severity map plus
    /// disabled set, because only a single value per rule can be folded unambiguously: a nearer
    /// layer writing `JBCT-STY-01 = "error"` must both raise the severity and lift an inherited
    /// `"off"`.
    record PartialLint(Option<Boolean> failOnWarning, Map<String, RuleSetting> rules) {
        static final PartialLint EMPTY = new PartialLint(none(), Map.of());

        PartialLint {
            rules = Map.copyOf(rules);
        }

        static PartialLint partialLint(TomlDocument toml) {
            return new PartialLint(toml.getBoolean("lint", "failOnWarning"), ruleSettings(toml));
        }

        PartialLint mergeWith(PartialLint nearer) {
            return new PartialLint(nearer.failOnWarning.orElse(failOnWarning), mergedRules(nearer.rules));
        }

        LintConfig materialize() {
            return LintConfig.lintConfig(materializedSeverities(),
                                         materializedDisabledRules(),
                                         failOnWarning.or(LintConfig.DEFAULT.failOnWarning()));
        }

        private static Map<String, RuleSetting> ruleSettings(TomlDocument toml) {
            return toml.getSection("lint.rules")
                       .entrySet()
                       .stream()
                       .flatMap(PartialLint::parsedSetting)
                       .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private static Stream<Map.Entry<String, RuleSetting>> parsedSetting(Map.Entry<String, String> entry) {
            return RuleSetting.ruleSetting(entry.getValue())
                              .map(setting -> Map.entry(entry.getKey(), setting))
                              .stream();
        }

        private Map<String, RuleSetting> mergedRules(Map<String, RuleSetting> nearer) {
            return Stream.concat(rules.entrySet()
                                      .stream(),
                                 nearer.entrySet()
                                       .stream())
                         .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                               Map.Entry::getValue,
                                                               (inherited, nearest) -> nearest));
        }

        private Map<String, DiagnosticSeverity> materializedSeverities() {
            return Stream.concat(LintConfig.DEFAULT.ruleSeverities()
                                                   .entrySet()
                                                   .stream(),
                                 rules.entrySet()
                                      .stream()
                                      .flatMap(entry -> severityEntry(entry.getKey(), entry.getValue())))
                         .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                               Map.Entry::getValue,
                                                               (builtIn, configured) -> configured));
        }

        private static Stream<Map.Entry<String, DiagnosticSeverity>> severityEntry(String ruleId,
                                                                                   RuleSetting setting) {
            return setting.severity()
                          .map(severity -> Map.entry(ruleId, severity))
                          .stream();
        }

        private Set<String> materializedDisabledRules() {
            return Stream.concat(LintConfig.DEFAULT.disabledRules()
                                                   .stream()
                                                   .filter(this::staysDisabled),
                                 rules.entrySet()
                                      .stream()
                                      .filter(PartialLint::isOff)
                                      .map(Map.Entry::getKey))
                         .collect(Collectors.toUnmodifiableSet());
        }

        /// A rule disabled by default stays disabled unless some layer explicitly re-enabled it.
        private boolean staysDisabled(String ruleId) {
            return option(rules.get(ruleId)).map(RuleSetting::isOff)
                                            .or(true);
        }

        private static boolean isOff(Map.Entry<String, RuleSetting> entry) {
            return entry.getValue()
                        .isOff();
        }
    }

    /// `[files]` section of one layer.
    record PartialFiles(Option<Long> maxFileSize, Option<List<String>> excludes) {
        static final PartialFiles EMPTY = new PartialFiles(none(), none());

        static PartialFiles partialFiles(TomlDocument toml) {
            return new PartialFiles(toml.getLong("files", "maxFileSize"), toml.getStringList("files", "excludes"));
        }

        PartialFiles mergeWith(PartialFiles nearer) {
            return new PartialFiles(nearer.maxFileSize.orElse(maxFileSize), nearer.excludes.orElse(excludes));
        }

        FilesConfig materialize() {
            return new FilesConfig(maxFileSize.or(FilesConfig.DEFAULT.maxFileSize()),
                                   excludes.or(FilesConfig.DEFAULT.excludes()));
        }
    }

    /// `[blueprint]` section of one layer.
    record PartialBlueprint(Option<BlueprintConfig.SchemaMode> schema) {
        static final PartialBlueprint EMPTY = new PartialBlueprint(none());

        static PartialBlueprint partialBlueprint(TomlDocument toml) {
            return new PartialBlueprint(toml.getString("blueprint", "schema")
                                            .map(BlueprintConfig.SchemaMode::fromString));
        }

        PartialBlueprint mergeWith(PartialBlueprint nearer) {
            return new PartialBlueprint(nearer.schema.orElse(schema));
        }

        BlueprintConfig materialize() {
            return new BlueprintConfig(schema.or(BlueprintConfig.DEFAULT.schema()));
        }
    }

    /// `[lint.layers]` section of one layer.
    record PartialLayers(Option<List<String>> domainGlobs,
                         Option<List<String>> applicationGlobs,
                         Option<List<String>> adapterGlobs,
                         Option<List<String>> bootstrapGlobs,
                         Option<List<String>> sliceGlobs) {
        static final PartialLayers EMPTY = new PartialLayers(none(), none(), none(), none(), none());

        static PartialLayers partialLayers(TomlDocument toml) {
            return new PartialLayers(toml.getStringList("lint.layers", "domain"),
                                     toml.getStringList("lint.layers", "application"),
                                     toml.getStringList("lint.layers", "adapter"),
                                     toml.getStringList("lint.layers", "bootstrap"),
                                     toml.getStringList("lint.layers", "slices"));
        }

        PartialLayers mergeWith(PartialLayers nearer) {
            return new PartialLayers(nearer.domainGlobs.orElse(domainGlobs),
                                     nearer.applicationGlobs.orElse(applicationGlobs),
                                     nearer.adapterGlobs.orElse(adapterGlobs),
                                     nearer.bootstrapGlobs.orElse(bootstrapGlobs),
                                     nearer.sliceGlobs.orElse(sliceGlobs));
        }

        LayerConfig materialize() {
            return LayerConfig.layerConfig(domainGlobs.or(LayerConfig.DEFAULT.domainGlobs()),
                                           applicationGlobs.or(LayerConfig.DEFAULT.applicationGlobs()),
                                           adapterGlobs.or(LayerConfig.DEFAULT.adapterGlobs()),
                                           bootstrapGlobs.or(LayerConfig.DEFAULT.bootstrapGlobs()),
                                           sliceGlobs.or(LayerConfig.DEFAULT.sliceGlobs()));
        }
    }

    /// A single `[lint.rules]` entry as written in one layer: a severity, or `off`.
    ///
    /// Modelled as one value per rule so that folding is a plain per-key override; the linter's
    /// two-collection shape (severities + disabled set) is reconstructed in [PartialLint#materialize].
    enum RuleSetting {
        ERROR(some(DiagnosticSeverity.ERROR)),
        WARNING(some(DiagnosticSeverity.WARNING)),
        INFO(some(DiagnosticSeverity.INFO)),
        OFF(none());

        private final Option<DiagnosticSeverity> severity;

        RuleSetting(Option<DiagnosticSeverity> severity) {
            this.severity = severity;
        }

        /// Parse a TOML severity token. An unrecognised token yields [Option#none()] — the layer
        /// simply says nothing about that rule, preserving the loader's long-standing leniency.
        static Option<RuleSetting> ruleSetting(String raw) {
            return switch (raw.toLowerCase()) {
                case "off", "disabled" -> some(OFF);
                case "error" -> some(ERROR);
                case "warning", "warn" -> some(WARNING);
                case "info" -> some(INFO);
                default -> none();
            };
        }

        Option<DiagnosticSeverity> severity() {
            return severity;
        }

        boolean isOff() {
            return this == OFF;
        }
    }
}
