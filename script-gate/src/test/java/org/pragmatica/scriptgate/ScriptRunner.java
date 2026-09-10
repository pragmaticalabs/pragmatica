package org.pragmatica.scriptgate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// Runs a real script in a real process and reports its exit code together with everything it
/// printed.
///
/// The output is part of the result on purpose. Every assertion in this module matches the SPECIFIC
/// diagnostic a script is supposed to emit, never a bare non-zero exit, because a bare non-zero exit
/// cannot tell "refused for the reason under test" apart from "the fixture was broken and it died" -
/// and those two are the observations this whole module exists to keep separate.
sealed interface ScriptRunner {
    /// A run that had to be killed reports this rather than a plausible exit code, so a hang can
    /// never be mistaken for a refusal.
    int TIMED_OUT = -1;

    int TIMEOUT_SECONDS = 120;

    record Execution(int exitCode, String output) {}

    static Execution run(Path workingDir, Map<String, String> environment, List<String> command)
    throws IOException, InterruptedException {
        var captured = Files.createTempFile("script-gate-", ".out");
        var builder = new ProcessBuilder(command).directory(workingDir.toFile())
                                                 .redirectErrorStream(true)
                                                 .redirectOutput(captured.toFile());

        builder.environment().putAll(environment);

        var process = builder.start();
        var finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly().waitFor();
        }

        var output = Files.readString(captured, StandardCharsets.UTF_8);

        Files.deleteIfExists(captured);

        if (!finished) {
            return new Execution(TIMED_OUT, output + "\n[ScriptRunner] killed after " + TIMEOUT_SECONDS + "s");
        }

        return new Execution(process.exitValue(), output);
    }

    static Path writeExecutable(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));

        return target;
    }

    static Path copyExecutable(Path source, Path target) throws IOException {
        return writeExecutable(target, Files.readString(source));
    }

    /// Nearest ancestor of the working directory holding both `forge.sh` and a `pom.xml`. Total by
    /// construction: when nothing matches it yields the starting directory, and
    /// [ScriptGateFixtureTest] is the positive control that fails loudly if that ever happens,
    /// rather than letting the suite run against a tree that does not contain the subjects.
    static Path repoRoot() {
        var start = Path.of("").toAbsolutePath();

        for (var candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("forge.sh")) && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
        }

        return start;
    }

    record unused() implements ScriptRunner {}
}
