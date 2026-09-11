package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.utils.Causes.cause;


/// A minimal but REAL git repository, because `scripts/changelog-check.sh` calls `git rev-parse` and
/// `git diff` and the defect it carries (#1000) lives in how their exit statuses are read. A fixture
/// that stubbed git would be built from the same premise as the defect and could not falsify it.
///
/// Deliberately has NO remote. `origin/<branch>` is therefore unresolvable in it, which is exactly
/// the #1000 condition: the workflow interpolates the base as `origin/${{ github.base_ref }}`, and a
/// renamed base branch, an unfetched ref or a fork edge leaves that ref absent from the checkout.
///
/// Every operation that can fail returns [Result], so no method here declares a checked exception:
/// `jbct.includeTests` is true for this module (see its pom), which holds this harness to the same
/// rules as production code.
record SyntheticGitRepo(Path root) {
    /// Identity and signing are pinned per-invocation rather than written into the fixture's config,
    /// so the host's own git configuration cannot decide whether a commit succeeds.
    private static final List<String> GIT = List.of("git",
                                                    "-c",
                                                    "user.name=script-gate",
                                                    "-c",
                                                    "user.email=script-gate@example.invalid",
                                                    "-c",
                                                    "commit.gpgsign=false",
                                                    "-c",
                                                    "init.defaultBranch=main");

    static Result<SyntheticGitRepo> syntheticGitRepo(Path root) {
        var repo = new SyntheticGitRepo(root);

        return repo.initialise()
                   .map(ignored -> repo);
    }

    private Result<Unit> initialise() {
        return git(List.of("init", "--quiet")).flatMap(ignored -> writeFile(Path.of("README.md"),
                                                                            "# changelog-check fixture\n"))
                  .flatMap(ignored -> commitAll("baseline"));
    }

    Result<Unit> writeFile(Path relative, String content) {
        var target = root.resolve(relative);

        return createParent(target).flatMap(ignored -> write(target, content))
                           .mapToUnit();
    }

    Result<Unit> commitAll(String message) {
        return git(List.of("add", "--all")).flatMap(ignored -> git(List.of("commit", "--quiet", "--message", message)));
    }

    private Result<Unit> git(List<String> arguments) {
        return ScriptRunner.run(root,
                                Map.of(),
                                command(arguments))
                           .flatMap(SyntheticGitRepo::requireSuccess);
    }

    private static List<String> command(List<String> arguments) {
        return Stream.concat(GIT.stream(),
                             arguments.stream())
                     .toList();
    }

    /// A git step that did not work must fail the TEST, never be mistaken for the script behaving
    /// unexpectedly - the distinction this whole module exists to keep.
    private static Result<Unit> requireSuccess(ScriptRunner.Execution execution) {
        return execution.exitCode() == 0
               ? Result.unitResult()
               : cause("git exited " + execution.exitCode() + ": " + execution.output()).result();
    }

    private static Result<Path> createParent(Path target) {
        return Result.lift(() -> Files.createDirectories(target.getParent()));
    }

    private static Result<Path> write(Path target, String content) {
        return Result.lift(() -> Files.writeString(target, content));
    }
}
