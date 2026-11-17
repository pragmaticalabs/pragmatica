package org.pragmatica.jbct.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.LintRun;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.lang.Option;


/// The source roots of one CLI run, each with the configuration its own module resolves (#2525, P2c).
///
/// Every argument path is a source root: [CliConfig#load] starts its `jbct.toml` chain at that path's module directory,
/// and the files under the path are collected with that configuration's `files` keys. Two paths in two modules
/// therefore get two configurations in one run, which `LintRun` keeps apart per root.
public sealed interface CliRoots {
    /// An argument path with its resolved configuration, lint context and the files collected under it.
    record ResolvedRoot(Path path, JbctConfig config, LintContext context, List<Path> files) {
        public static ResolvedRoot resolvedRoot(Path path, JbctConfig config, LintContext context, List<Path> files) {
            return new ResolvedRoot(path, config, context, List.copyOf(files));
        }

        /// This root as the run's routing entry.
        public LintRun.SourceRoot sourceRoot() {
            return LintRun.SourceRoot.sourceRoot(path, context);
        }

        private ResolvedRoot withFiles(List<Path> newFiles) {
            return resolvedRoot(path, config, context, newFiles);
        }

        private int depth() {
            return path.toAbsolutePath()
                       .normalize()
                       .getNameCount();
        }
    }

    /// Resolve every distinct argument path to a root, in argument order; empty (after printing each refusal to `err`)
    /// when any path's configuration cannot be used.
    ///
    /// A file collected under two overlapping arguments stays with the deepest root, the one `LintRun` routes it to.
    static Option<List<ResolvedRoot>> resolve(Option<Path> explicitConfigPath,
                                              Option<String> explicitMode,
                                              List<Path> paths,
                                              Function<JbctConfig, LintContext> contextOf,
                                              PrintStream err) {
        var loaded = distinct(paths).stream()
                             .map(path -> resolveRoot(explicitConfigPath, explicitMode, path, contextOf, err))
                             .toList();

        return loaded.stream()
                     .anyMatch(Option::isEmpty)
               ? Option.none()
               : Option.some(ownedByDeepest(loaded.stream().map(Option::unwrap).toList()));
    }

    /// Every collected file of the run, in root order.
    static List<Path> allFiles(List<ResolvedRoot> roots) {
        return roots.stream()
                    .flatMap(root -> root.files()
                                         .stream())
                    .toList();
    }

    private static Option<ResolvedRoot> resolveRoot(Option<Path> explicitConfigPath,
                                                    Option<String> explicitMode,
                                                    Path path,
                                                    Function<JbctConfig, LintContext> contextOf,
                                                    PrintStream err) {
        return CliConfig.load(explicitConfigPath, explicitMode, path, err).map(config -> ResolvedRoot.resolvedRoot(path,
                                                                                                                   config,
                                                                                                                   contextOf.apply(config),
                                                                                                                   FileCollector.collectJavaFiles(List.of(path),
                                                                                                                                                  config.files(),
                                                                                                                                                  err::println)));
    }

    private static List<Path> distinct(List<Path> paths) {
        return List.copyOf(paths.stream()
                                .collect(Collectors.toMap(path -> path.toAbsolutePath()
                                                                      .normalize(),
                                                          Function.identity(),
                                                          (first, second) -> first,
                                                          LinkedHashMap::new))
                                .values());
    }

    private static List<ResolvedRoot> ownedByDeepest(List<ResolvedRoot> roots) {
        return roots.stream()
                    .map(root -> root.withFiles(filesNotHeldDeeper(root, roots)))
                    .toList();
    }

    private static List<Path> filesNotHeldDeeper(ResolvedRoot root, List<ResolvedRoot> roots) {
        var deeper = new HashSet<Path>();

        roots.stream().filter(other -> other.depth() > root.depth()).forEach(other -> deeper.addAll(other.files()));

        return root.files()
                   .stream()
                   .filter(file -> !deeper.contains(file))
                   .toList();
    }

    record unused() implements CliRoots {}
}
