package org.pragmatica.scriptgate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.junit.jupiter.api.Assertions.fail;


/// Bridges [Result] into JUnit's failure channel, so no test in this module declares a checked
/// exception and no fixture failure can pass silently for a test outcome.
///
/// Both helpers fail the test on the spot rather than returning a sentinel: `fail` throws, so the
/// `or(...)` fallbacks below are unreachable and exist only to satisfy the type. A fixture that
/// could not be built must never be mistaken for a script that behaved unexpectedly.
sealed interface ScriptGate {
    /// A fixture step that must have worked before the subject is exercised.
    static Unit given(Result<Unit> step) {
        return step.onFailure(cause -> fail("fixture setup failed: " + cause.message()))
                   .or(Unit.unit());
    }

    /// The built fixture. Consuming the [Result] here is what keeps the caller from discarding it:
    /// an assignment inside `onSuccess` leaves the Result itself unexamined, which is the same
    /// silent-ignore this module exists to object to.
    static SyntheticRepo fixture(Path root, Result<SyntheticRepo> result) {
        return result.onFailure(cause -> fail("could not build the fixture: " + cause.message()))
                     .or(new SyntheticRepo(root));
    }

    /// The built git fixture. Same contract as [#fixture], for the repository shape
    /// [ChangelogCheckTest] needs: `scripts/changelog-check.sh` calls real `git` commands, so its
    /// subject cannot be the Maven-shaped [SyntheticRepo].
    static SyntheticGitRepo gitFixture(Path root, Result<SyntheticGitRepo> result) {
        return result.onFailure(cause -> fail("could not build the git fixture: " + cause.message()))
                     .or(new SyntheticGitRepo(root));
    }

    /// The outcome of running a script; a harness failure fails the test rather than being asserted on.
    static ScriptRunner.Execution executed(Result<ScriptRunner.Execution> result) {
        return result.onFailure(cause -> fail("could not run the script: " + cause.message()))
                     .or(ScriptRunner.Execution.execution(ScriptRunner.TIMED_OUT, ""));
    }

    /// Where `tool` resolves on the PATH of the JVM running the tests.
    static Option<Path> onPath(String tool) {
        return Option.from(Stream.of(System.getenv("PATH").split(File.pathSeparator))
                                 .map(directory -> Path.of(directory).resolve(tool))
                                 .filter(Files::isExecutable)
                                 .findFirst());
    }

    /// A tool the script under test really needs and a test cannot stub. Its absence is a defect of the machine
    /// running the tests, not of the script, so it fails here by name instead of surfacing as a misleading
    /// assertion about what the script printed.
    static Path requiredTool(String tool) {
        return onPath(tool).or(() -> fail("environment prerequisite missing: '" + tool
                                         + "' is not on PATH; the script gate needs it installed and cannot stub it"));
    }

    /// A directory holding symlinks to only the named tools, for running a script with everything else absent from
    /// its PATH.
    static Result<Unit> linkTools(Path directory, List<String> tools) {
        return Result.lift(() -> Files.createDirectories(directory))
                     .flatMap(created -> Result.allOf(tools.stream().map(tool -> link(created, tool)).toList()))
                     .mapToUnit();
    }

    private static Result<Path> link(Path directory, String tool) {
        return Result.lift(() -> Files.createSymbolicLink(directory.resolve(tool), requiredTool(tool)));
    }

    record unused() implements ScriptGate {}
}
