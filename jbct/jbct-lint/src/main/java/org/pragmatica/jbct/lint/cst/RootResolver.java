package org.pragmatica.jbct.lint.cst;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.FileCollector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The one place that decides, from the paths a front end hands over, which source root each FILE belongs to, and each
/// root's configuration, mode and where the mode came from (#2525, P2c).
///
/// A front end (the CLI, the Maven goals) collects paths and, for Maven, declares its source and test directories; it
/// computes no root of its own. The answer is a [RootPlan]: roots in path order, each with its files, config, lint
/// context, mode and [ModeSource], and whether it is a test root.
///
/// **Root of a file** is decided from the FILE, never from the argument it was reached through: the deepest of the
/// declared roots that contain it and its layout root (the deepest `src/<kind>/java` ancestor); with neither, the
/// deepest directory argument that contains it, else its own directory. Two roots that are the same directory are one
/// root. Paths are normalised to one absolute form before grouping, so a file reached through two arguments (`./x` and
/// `x/sub`) is one file in one root. The root is decided from the path alone, so no file is opened to find it; the
/// path-minus-package-directories rule of the plan (L19) is not used, because it needs the file's content, and a
/// `disabled` root's files must not be opened.
///
/// **Configuration** is [ConfigLoader#loadForSourceRoot] from the root's own module chain, with the mode override
/// folded last. Files are collected with the `files` keys of the chain of the argument path they were reached through.
public sealed interface RootResolver permits RootResolver.unused {
    record unused() implements RootResolver {}

    /// Where a root's mode came from.
    sealed interface ModeSource {
        /// The wording the summary line prints after the mode.
        String describe();

        /// The mode is written by a `jbct.toml` (or the explicit config layer) at this path.
        record FromToml(Path file) implements ModeSource {
            public static FromToml fromToml(Path file) {
                return new FromToml(file);
            }

            @Override
            public String describe() {
                return "from " + file;
            }
        }

        /// The mode was given on the invocation (`--mode`, `-Djbct.mode`).
        record FromOverride(String flag) implements ModeSource {
            public static FromOverride fromOverride(String flag) {
                return new FromOverride(flag);
            }

            @Override
            public String describe() {
                return "from " + flag;
            }
        }

        /// No file and no override sets a mode: the built-in default applies.
        record FromDefault() implements ModeSource {
            public static FromDefault fromDefault() {
                return new FromDefault();
            }

            @Override
            public String describe() {
                return "default";
            }
        }
    }

    /// The mode given on the invocation: the raw value and the flag that carried it (`--mode`, `-Djbct.mode`).
    record ModeOverride(String value, String flag) {
        public static ModeOverride modeOverride(String value, String flag) {
            return new ModeOverride(value, flag);
        }
    }

    /// What a front end hands over: the paths to collect from (files and directories), the directories it declares as
    /// source roots (Maven's main and test directories), which of them are test roots, the explicit config layer, the
    /// mode override and the fail-on-warning flag.
    record Request(List<Path> paths,
                   Set<Path> declaredRoots,
                   Set<Path> declaredTestRoots,
                   Option<Path> explicitConfig,
                   Option<ModeOverride> modeOverride,
                   boolean failOnWarning) {
        public Request {
            paths = distinct(paths);
            declaredTestRoots = normalised(declaredTestRoots);
            declaredRoots = Stream.concat(normalised(declaredRoots).stream(),
                                          declaredTestRoots.stream())
                                  .collect(Collectors.toUnmodifiableSet());
        }

        /// A request over these paths with nothing declared: the CLI form.
        public static Request request(List<Path> paths) {
            return new Request(paths, Set.of(), Set.of(), Option.none(), Option.none(), false);
        }

        /// This request with the directories the front end declares as source roots and as test roots.
        public Request withDeclared(Set<Path> mainRoots, Set<Path> testRoots) {
            return new Request(paths,
                               Stream.concat(mainRoots.stream(), testRoots.stream()).collect(Collectors.toSet()),
                               testRoots,
                               explicitConfig,
                               modeOverride,
                               failOnWarning);
        }

        public Request withExplicitConfig(Option<Path> config) {
            return new Request(paths, declaredRoots, declaredTestRoots, config, modeOverride, failOnWarning);
        }

        public Request withModeOverride(Option<ModeOverride> override) {
            return new Request(paths, declaredRoots, declaredTestRoots, explicitConfig, override, failOnWarning);
        }

        public Request withFailOnWarning(boolean flag) {
            return new Request(paths, declaredRoots, declaredTestRoots, explicitConfig, modeOverride, flag);
        }
    }

    /// One source root with everything the run needs about it.
    record ResolvedRoot(Path path,
                        JbctConfig config,
                        LintContext context,
                        JbctMode mode,
                        ModeSource modeSource,
                        boolean testRoot,
                        List<Path> files) {
        public ResolvedRoot {
            files = List.copyOf(files);
        }

        public static ResolvedRoot resolvedRoot(Path path,
                                                JbctConfig config,
                                                ModeSource modeSource,
                                                boolean testRoot,
                                                List<Path> files) {
            return new ResolvedRoot(path,
                                    config,
                                    LintContext.fromConfig(config),
                                    config.mode(),
                                    modeSource,
                                    testRoot,
                                    files);
        }
    }

    /// The roots of one run, in path order, with the invocation facts the run needs to judge it.
    record RootPlan(List<ResolvedRoot> roots, boolean failOnWarning, String testRootRule, List<String> messages) {
        public RootPlan {
            roots = List.copyOf(roots);
            messages = List.copyOf(messages);
        }

        public static RootPlan rootPlan(List<ResolvedRoot> roots,
                                        boolean failOnWarning,
                                        String testRootRule,
                                        List<String> messages) {
            return new RootPlan(roots, failOnWarning, testRootRule, messages);
        }

        /// Every collected file of the plan, each once, in root order.
        public List<Path> files() {
            return roots.stream()
                        .flatMap(root -> root.files()
                                             .stream())
                        .toList();
        }
    }

    String SRC_TEST_RULE = "src/test relative to the module directory";
    String DECLARED_TEST_RULE = "declared test directories, and src/test relative to the module directory";

    /// Resolve the roots of a request. A configuration layer that cannot be used, or an unknown mode override, fails
    /// the whole resolution with the typed config error; nothing is guessed.
    static Result<RootPlan> resolve(Request request) {
        var messages = new ArrayList<String>();

        return Result.allOf(request.paths().stream().map(path -> collected(request, path, messages)).toList()).flatMap(collected -> plan(request,
                                                                                                                                         collected,
                                                                                                                                         messages));
    }

    record Collected(Path argument, List<Path> files) {
        static Collected collected(Path argument, List<Path> files) {
            return new Collected(argument,
                                 files.stream().map(file -> file.toAbsolutePath()
                                                                .normalize()).toList());
        }
    }

    private static Result<Collected> collected(Request request, Path path, List<String> messages) {
        return ConfigLoader.loadForSourceRoot(request.explicitConfig(),
                                              path)
                           .map(config -> Collected.collected(path,
                                                              FileCollector.collectJavaFiles(List.of(path),
                                                                                             config.files(),
                                                                                             messages::add)));
    }

    private static Result<RootPlan> plan(Request request, List<Collected> collected, List<String> messages) {
        var directories = collected.stream().map(Collected::argument).filter(Files::isDirectory).toList();
        var owned = ownedFiles(request, directories, collected);

        return Result.allOf(rootPaths(request, directories, owned).stream()
                                     .map(root -> resolvedRoot(request,
                                                               root,
                                                               owned.getOrDefault(root,
                                                                                  List.of())))
                                     .toList()).map(roots -> RootPlan.rootPlan(roots,
                                                                               request.failOnWarning(),
                                                                               request.declaredTestRoots()
                                                                                      .isEmpty()
                                                                               ? SRC_TEST_RULE
                                                                               : DECLARED_TEST_RULE,
                                                                               messages));
    }

    private static Map<Path, List<Path>> ownedFiles(Request request,
                                                    List<Path> directories,
                                                    List<Collected> collected) {
        return collected.stream()
                        .flatMap(entry -> entry.files()
                                               .stream())
                        .collect(Collectors.toCollection(LinkedHashSet::new))
                        .stream()
                        .collect(Collectors.groupingBy(file -> ownerOf(request, directories, file),
                                                       LinkedHashMap::new,
                                                       Collectors.toList()));
    }

    /// The roots that own a file, and the declared roots the front end also handed over as paths (Maven's empty main
    /// directory still has a line in the summary).
    private static List<Path> rootPaths(Request request, List<Path> directories, Map<Path, List<Path>> owned) {
        return Stream.concat(owned.keySet().stream(),
                             directories.stream()
                                        .map(directory -> directory.toAbsolutePath()
                                                                   .normalize())
                                        .filter(request.declaredRoots()::contains))
                     .distinct()
                     .sorted(Comparator.comparing(Path::toString))
                     .toList();
    }

    private static Path ownerOf(Request request, List<Path> directories, Path file) {
        return Option.from(Stream.concat(request.declaredRoots().stream().filter(file::startsWith),
                                         layoutRoot(file).stream())
                                 .max(Comparator.comparingInt(Path::getNameCount))).or(() -> fallbackRoot(directories,
                                                                                                          file));
    }

    /// The deepest `src/<kind>/java` ancestor of a file: the standard layout's source root.
    private static Option<Path> layoutRoot(Path file) {
        return Option.from(IntStream.range(2,
                                           file.getNameCount() - 1)
                                    .filter(index -> isLayoutMarker(file, index))
                                    .boxed()
                                    .max(Integer::compare)).map(index -> file.getRoot()
                                                                             .resolve(file.subpath(0, index + 1)));
    }

    private static boolean isLayoutMarker(Path file, int index) {
        return file.getName(index)
                   .toString()
                   .equals("java") && file.getName(index - 2)
                                          .toString()
                                          .equals("src");
    }

    private static Path fallbackRoot(List<Path> directories, Path file) {
        return Option.from(directories.stream()
                                      .map(directory -> directory.toAbsolutePath()
                                                                 .normalize())
                                      .filter(file::startsWith)
                                      .max(Comparator.comparingInt(Path::getNameCount))).or(() -> file.getParent());
    }

    private static Result<ResolvedRoot> resolvedRoot(Request request, Path root, List<Path> files) {
        return ConfigLoader.loadForSourceRoot(request.explicitConfig(),
                                              root)
                           .flatMap(config -> config.overrideMode(request.modeOverride().map(ModeOverride::value)))
                           .flatMap(config -> modeSource(request, root).map(source -> ResolvedRoot.resolvedRoot(root,
                                                                                                                config,
                                                                                                                source,
                                                                                                                isTestRoot(request,
                                                                                                                           root),
                                                                                                                files)));
    }

    private static Result<ModeSource> modeSource(Request request, Path root) {
        return request.modeOverride()
                      .map(override -> Result.<ModeSource> success(ModeSource.FromOverride.fromOverride(override.flag())))
                      .or(() -> fileSource(request, root));
    }

    private static Result<ModeSource> fileSource(Request request, Path root) {
        return ConfigLoader.modeLayer(request.explicitConfig(),
                                      root)
                           .map(layer -> layer.<ModeSource> map(ModeSource.FromToml::fromToml)
                                              .or(ModeSource.FromDefault.fromDefault()));
    }

    private static boolean isTestRoot(Request request, Path root) {
        return request.declaredTestRoots()
                      .stream()
                      .anyMatch(root::startsWith) || ConfigLoader.isTestRoot(root);
    }

    private static List<Path> distinct(List<Path> paths) {
        return List.copyOf(new LinkedHashSet<>(paths.stream().map(path -> path.toAbsolutePath()
                                                                              .normalize()).toList()));
    }

    private static Set<Path> normalised(Set<Path> paths) {
        return paths.stream()
                    .map(path -> path.toAbsolutePath()
                                     .normalize())
                    .collect(Collectors.toUnmodifiableSet());
    }
}
