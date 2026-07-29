package org.pragmatica.jbct.lint.layer;

import java.util.List;


/// Explicit `[lint.layers]` configuration: per-layer package globs plus slice-root globs.
///
/// Empty by default — a zero-config project relies entirely on [LayerClassifier]'s book-layout
/// conventions. Any list provided here is consulted *before* conventions, so explicit config
/// overrides the defaults. Globs use the shared [org.pragmatica.jbct.shared.PackageGlob] syntax,
/// the same one `excludePackages` uses: `*` matches exactly one package segment, `**` matches
/// **zero or more** segments. `com.example.core.**` therefore classifies the bare package
/// `com.example.core` as well as every subpackage beneath it — a class sitting directly in the
/// package that declares a layer belongs to that layer — while never matching the sibling
/// `com.example.corex`.
///
/// Because `**` spans zero segments, `slices` globs want `*`: `com.example.usecase.*` makes each
/// individual slice package a root, whereas `com.example.usecase.**` would also make the
/// `com.example.usecase` grouping package itself a slice root.
///
/// TOML shape (each key is a string list):
/// ```toml
/// [lint.layers]
/// domain      = ["com.example.core.**"]
/// application = ["com.example.app.**"]
/// adapter     = ["com.example.adapter.**", "com.example.integration.**"]
/// bootstrap   = ["com.example.boot.**"]
/// slices      = ["com.example.usecase.*"]
/// ```
public record LayerConfig(List<String> domainGlobs,
                          List<String> applicationGlobs,
                          List<String> adapterGlobs,
                          List<String> bootstrapGlobs,
                          List<String> sliceGlobs) {
    public LayerConfig {
        domainGlobs = List.copyOf(domainGlobs);
        applicationGlobs = List.copyOf(applicationGlobs);
        adapterGlobs = List.copyOf(adapterGlobs);
        bootstrapGlobs = List.copyOf(bootstrapGlobs);
        sliceGlobs = List.copyOf(sliceGlobs);
    }

    /// Empty configuration — conventions only.
    public static final LayerConfig DEFAULT = new LayerConfig(List.of(), List.of(), List.of(), List.of(), List.of());

    /// Factory method for creating LayerConfig.
    public static LayerConfig layerConfig(List<String> domainGlobs,
                                          List<String> applicationGlobs,
                                          List<String> adapterGlobs,
                                          List<String> bootstrapGlobs,
                                          List<String> sliceGlobs) {
        return new LayerConfig(domainGlobs, applicationGlobs, adapterGlobs, bootstrapGlobs, sliceGlobs);
    }

    /// True when no explicit globs are configured (the classifier will use conventions only).
    public boolean isEmpty() {
        return domainGlobs.isEmpty() && applicationGlobs.isEmpty() && adapterGlobs.isEmpty()
               && bootstrapGlobs.isEmpty() && sliceGlobs.isEmpty();
    }
}
