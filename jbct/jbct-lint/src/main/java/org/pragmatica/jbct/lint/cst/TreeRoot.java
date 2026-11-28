package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.pragmatica.jbct.config.ConfigError;
import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.jbct.lint.layer.LayerCoverage;
import org.pragmatica.jbct.lint.tree.PackageTree;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Whether the package tree of one source root can be judged against (#2526): the refusal of a root whose
/// `[project] basePackage` the TREE rules cannot use.
///
/// The question is asked only of a root that reads its base package: a main root with at least one collected file whose
/// mode runs a TREE rule that `[lint.rules]` leaves on. Infrastructure, format-only and disabled roots and test roots
/// never read the key. Such a root is refused when its base package is absent ([ConfigError.BasePackageRequired]), has
/// an `io` or `shared` segment after its first ([ConfigError.BasePackageHasRole]), or covers no main file of the root
/// ([ConfigError.BasePackageCoversNoFile]). A refused root keeps its code rules; only the TREE rules lose the tree.
///
/// The answer reads ALL main files of the root on disk (their declared packages, through the `[files]` keys of the
/// root's config), never only the collected ones, so a file linted alone gets the same answer, and the same suggestion
/// (the common package prefix of those files), as when its module is linted. Limit: a root reached as a plain directory
/// argument is read whole, deeper layout roots inside it included.
sealed interface TreeRoot permits TreeRoot.unused {
    record unused() implements TreeRoot {}

    /// The rules that read a root's base package, in the order their coverage lines print.
    List<String> RULES = List.of("JBCT-TREE-01", "JBCT-TREE-02");

    /// The root as the run uses it: refused, without a tree, when it reads its base package and cannot use it.
    static ResolvedRoot judged(ResolvedRoot root) {
        return readsBasePackage(root)
               ? refusal(root.path(),
                         root.context().tree(),
                         mainPackages(root)).map(root::refused)
                        .or(root)
               : root;
    }

    /// A main root with a collected file, in a mode that runs a TREE rule left on by `[lint.rules]`.
    static boolean readsBasePackage(ResolvedRoot root) {
        return ! root.testRoot()
               && !root.files()
                       .isEmpty()
               && runsTreeRule(root.context());
    }

    private static boolean runsTreeRule(LintContext context) {
        return CstLinter.rulesFor(context.mode())
                        .stream()
                        .map(LintRule::ruleId)
                        .filter(RULES::contains)
                        .anyMatch(context::isRuleEnabled);
    }

    /// The refusal of a root whose tree is `tree` and whose main files declare `packages`; none when the tree can be
    /// judged against.
    static Option<Cause> refusal(Path sourceRoot, Option<PackageTree> tree, List<String> packages) {
        var suggestion = commonPrefix(packages);
        var toml = ConfigLoader.moduleDirectory(sourceRoot).resolve(ConfigLoader.PROJECT_CONFIG_NAME);

        return tree.map(declared -> refusalOf(sourceRoot, declared, packages, suggestion, toml))
                   .or(() -> some(ConfigError.BasePackageRequired.basePackageRequired(sourceRoot, suggestion, toml)));
    }

    private static Option<Cause> refusalOf(Path sourceRoot,
                                           PackageTree tree,
                                           List<String> packages,
                                           String suggestion,
                                           Path toml) {
        return tree.role(tree.root())
                   .isPresent()
               ? some(ConfigError.BasePackageHasRole.basePackageHasRole(sourceRoot, tree.root(), suggestion, toml))
               : uncovered(sourceRoot, tree, packages, suggestion, toml);
    }

    private static Option<Cause> uncovered(Path sourceRoot,
                                           PackageTree tree,
                                           List<String> packages,
                                           String suggestion,
                                           Path toml) {
        return packages.stream()
                       .anyMatch(packageName -> PackageTree.anc(tree.root(),
                                                                packageName))
               ? none()
               : some(ConfigError.BasePackageCoversNoFile.basePackageCoversNoFile(sourceRoot,
                                                                                  tree.root(),
                                                                                  suggestion,
                                                                                  toml));
    }

    /// The longest package every given package is equal to or below, segment by segment; `""` when they share none.
    static String commonPrefix(List<String> packages) {
        var split = packages.stream().map(TreeRoot::segmentsOf).toList();
        var shortest = split.stream().mapToInt(List::size).min().orElse(0);
        var shared = IntStream.range(0, shortest).takeWhile(index -> sameSegmentAt(split, index)).count();

        return split.isEmpty()
               ? ""
               : String.join(".",
                             split.getFirst().subList(0, (int) shared));
    }

    private static boolean sameSegmentAt(List<List<String>> split, int index) {
        return split.stream()
                    .map(segments -> segments.get(index))
                    .distinct()
                    .count() == 1;
    }

    private static List<String> segmentsOf(String packageName) {
        return packageName.isEmpty()
               ? List.of()
               : Arrays.asList(packageName.split(Pattern.quote(".")));
    }

    /// The declared package of every main file of the root on disk; a file that cannot be read declares none here (the
    /// run reports it as not read).
    private static List<String> mainPackages(ResolvedRoot root) {
        return FileCollector.collectJavaFiles(List.of(root.path()),
                                              root.config().files(),
                                              TreeRoot::unreported)
                            .stream()
                            .flatMap(file -> SourceFile.sourceFile(file)
                                                       .map(SourceFile::content)
                                                       .map(LayerCoverage::packageOf)
                                                       .stream())
                            .toList();
    }

    /// A collection message of the pre-pass is dropped (design-out): the run's own collection walks the same root with
    /// the same `[files]` keys and reports it, so printing it here would print it twice.
    @Contract
    private static void unreported(String message) {}
}
