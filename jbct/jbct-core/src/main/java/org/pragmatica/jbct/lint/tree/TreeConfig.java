package org.pragmatica.jbct.lint.tree;

import java.util.List;


/// The `[lint.tree]` configuration (#2526): the one key `exclude`, a list of package globs in the shared
/// [org.pragmatica.jbct.shared.PackageGlob] syntax. A package matching a glob is not part of the project tree, so
/// neither TREE rule judges an import of it, and a file declared in it escapes the root check.
///
/// TOML shape:
/// ```toml
/// [lint.tree]
/// exclude = ["com.example.generated.**"]
/// ```
public record TreeConfig(List<String> excludeGlobs) {
    public TreeConfig {
        excludeGlobs = List.copyOf(excludeGlobs);
    }

    /// No exclusions: every package under the root belongs to the tree.
    public static final TreeConfig DEFAULT = new TreeConfig(List.of());

    /// Factory method for creating TreeConfig.
    public static TreeConfig treeConfig(List<String> excludeGlobs) {
        return new TreeConfig(excludeGlobs);
    }

    /// True when no exclusion is configured.
    public boolean isEmpty() {
        return excludeGlobs.isEmpty();
    }
}
