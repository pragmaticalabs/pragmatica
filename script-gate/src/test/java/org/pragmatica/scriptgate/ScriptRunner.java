package org.pragmatica.scriptgate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Runs a real script in a real process and reports its exit code together with everything it
/// printed.
///
/// The output is part of the result on purpose. Every assertion in this module matches the SPECIFIC
/// diagnostic a script is supposed to emit, never a bare non-zero exit, because a bare non-zero exit
/// cannot tell "refused for the reason under test" apart from "the fixture was broken and it died" -
/// and those two are the observations this whole module exists to keep separate.
///
/// Every operation that can fail returns [Result], so no method here declares a checked exception:
/// `jbct.includeTests` is true for this module (see its pom), which holds this harness to the same
/// rules as production code.
sealed interface ScriptRunner {
    /// A run that had to be killed reports this rather than a plausible exit code, so a hang can
    /// never be mistaken for a refusal.
    int TIMED_OUT = -1;
    int TIMEOUT_SECONDS = 120;
    /// Bound on the walk from the working directory toward the filesystem root.
    int MAX_ASCENT = 16;
    String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";

    record Execution(int exitCode, String output) {
        static Execution execution(int exitCode, String output) {
            return new Execution(exitCode, output);
        }
    }

    static Result<Execution> run(Path workingDir, Map<String, String> environment, List<String> command) {
        return captureFile().flatMap(captured -> spawn(captured, workingDir, environment, command));
    }

    /// Output goes to a file rather than a pipe: a process that never exits would block a pipe read
    /// forever, and the timeout below would then never be reached.
    private static Result<Path> captureFile() {
        return Result.lift(() -> Files.createTempFile("script-gate-", ".out")).map(ScriptRunner::markTransient);
    }

    private static Path markTransient(Path path) {
        path.toFile().deleteOnExit();

        return path;
    }

    private static Result<Execution> spawn(Path captured,
                                           Path workingDir,
                                           Map<String, String> environment,
                                           List<String> command) {
        return Result.lift(() -> processBuilder(captured, workingDir, environment, command).start()).flatMap(process -> settle(process,
                                                                                                                               captured));
    }

    private static ProcessBuilder processBuilder(Path captured,
                                                 Path workingDir,
                                                 Map<String, String> environment,
                                                 List<String> command) {
        var builder = new ProcessBuilder(command).directory(workingDir.toFile())
                                                 .redirectErrorStream(true)
                                                 .redirectOutput(captured.toFile());

        builder.environment().putAll(environment);

        return builder;
    }

    private static Result<Execution> settle(Process process, Path captured) {
        return Result.lift(() -> process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                     .map(finished -> haltUnlessFinished(process, finished))
                     .flatMap(finished -> outcome(process, captured, finished));
    }

    private static boolean haltUnlessFinished(Process process, boolean finished) {
        if (!finished) {
            process.destroyForcibly();
        }

        return finished;
    }

    private static Result<Execution> outcome(Process process, Path captured, boolean finished) {
        return Result.lift(() -> Files.readString(captured, StandardCharsets.UTF_8)).map(output -> execution(process,
                                                                                                             output,
                                                                                                             finished));
    }

    private static Execution execution(Process process, String output, boolean finished) {
        if (!finished) {
            return Execution.execution(TIMED_OUT, output + "[ScriptRunner] killed after " + TIMEOUT_SECONDS + "s");
        }

        return Execution.execution(process.exitValue(), output);
    }

    static Result<Path> writeExecutable(Path target, String content) {
        return createParent(target).flatMap(ignored -> writeContent(target, content))
                           .flatMap(ScriptRunner::makeExecutable);
    }

    static Result<Path> copyExecutable(Path source, Path target) {
        return Result.lift(() -> Files.readString(source)).flatMap(content -> writeExecutable(target, content));
    }

    private static Result<Path> createParent(Path target) {
        return Result.lift(() -> Files.createDirectories(target.getParent()));
    }

    private static Result<Path> writeContent(Path target, String content) {
        return Result.lift(() -> Files.writeString(target, content));
    }

    private static Result<Path> makeExecutable(Path target) {
        return Result.lift(() -> Files.setPosixFilePermissions(target,
                                                               PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS)));
    }

    /// Nearest ancestor of the working directory holding both `forge.sh` and a `pom.xml`. Total by
    /// construction: when nothing matches it yields the starting directory, and
    /// [ScriptGateFixtureTest] is the positive control that fails loudly if that ever happens,
    /// rather than letting the suite run against a tree that does not contain the subjects.
    static Path repoRoot() {
        var start = Path.of("").toAbsolutePath();

        return Option.from(Stream.iterate(start, Objects::nonNull, Path::getParent)
                                 .limit(MAX_ASCENT)
                                 .filter(ScriptRunner::holdsSubjects)
                                 .findFirst()).or(start);
    }

    private static boolean holdsSubjects(Path candidate) {
        return Files.isRegularFile(candidate.resolve("forge.sh")) && Files.isRegularFile(candidate.resolve("pom.xml"));
    }

    record unused() implements ScriptRunner {}
}
