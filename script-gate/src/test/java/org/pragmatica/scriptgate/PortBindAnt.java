package org.pragmatica.scriptgate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;


/// Runs the port-bind gate (#3801) the way the build does: the root pom's own `port-bind-scan` antrun `<target>` (sliced out of
/// `pom.xml` as text, not copied), executed by Ant's own `java` task with `fork` and `failonerror` exactly as the pom writes them,
/// so the exit-to-build-failure path is exercised and not read. What the antrun plugin supplies is supplied here: the three Maven
/// properties the target names and the `maven.test.classpath` reference (this JVM's classpath, which holds the scan).
///
/// A build failure is a [BuildOutcome] that did not pass, with the log the build would print (the scan's own lines, then Ant's
/// failure message). Nothing here asserts: a test states what it expects of the outcome.
///
/// What this does NOT exercise, and what covers it: the plugin's phase binding, profile activation and `skip` (the stamp pin in
/// [PortBindGateTest], run by the build that compiled this module, and the pins in [PortBindGatePinsTest]).
sealed interface PortBindAnt {
    String EXECUTION_ID = "<id>port-bind-scan</id>";

    /// What the build did: whether it passed, and everything it printed.
    record BuildOutcome(boolean passed, String log) {}

    /// The gate's execution over `classes` for `module` in `mode`, in a build whose base directory is `workDir`.
    static BuildOutcome build(Path workDir, String module, Path classes, String mode) {
        var log = new ByteArrayOutputStream();
        var failure = Result.lift(() -> Files.writeString(workDir.resolve("build.xml"),
                                                          buildFile()))
                            .map(file -> project(workDir, module, classes, mode, log, file))
                            .flatMap(PortBindAnt::execute);

        return new BuildOutcome(failure.isSuccess(),
                                log.toString(StandardCharsets.UTF_8) + failure.fold(cause -> "\nBUILD FAILED: " + cause.message(),
                                                                                    unit -> ""));
    }

    /// The text between `open` and `close`, the first pair after the gate execution's `<id>` in the root pom.
    static String executionText(String open, String close) {
        return between(rootPom(), rootPom().indexOf(EXECUTION_ID), open, close);
    }

    /// The text between `open` and `close`, the first pair in the root pom.
    static String rootPomText(String open, String close) {
        return between(rootPom(), 0, open, close);
    }

    private static String buildFile() {
        var target = executionText("<target>", "</target>");

        return "<project name=\"port-bind-gate\" default=\"run\">"
             + "<path id=\"maven.test.classpath\"><pathelement path=\"${test.classpath}\"/></path>"
             + "<target name=\"run\">" + target
             + "</target></project>";
    }

    private static String rootPom() {
        return Result.lift(() -> Files.readString(ScriptRunner.repoRoot().resolve("pom.xml"))).unwrap();
    }

    private static String between(String text, int from, String open, String close) {
        var start = text.indexOf(open, from) + open.length();

        return text.substring(start, text.indexOf(close, start));
    }

    private static Project project(Path workDir,
                                   String module,
                                   Path classes,
                                   String mode,
                                   ByteArrayOutputStream log,
                                   Path file) {
        var project = new Project();
        var stream = new PrintStream(log, true, StandardCharsets.UTF_8);
        var logger = new DefaultLogger();

        project.init();
        project.setBaseDir(workDir.toFile());
        project.setProperty("project.artifactId", module);
        project.setProperty("project.build.testOutputDirectory", classes.toString());
        project.setProperty("port.bind.gate.mode", mode);
        project.setProperty("test.classpath", System.getProperty("java.class.path"));
        logger.setOutputPrintStream(stream);
        logger.setErrorPrintStream(stream);
        logger.setMessageOutputLevel(Project.MSG_INFO);
        project.addBuildListener(logger);
        ProjectHelper.configureProject(project, file.toFile());

        return project;
    }

    private static Result<Unit> execute(Project project) {
        return Result.lift(() -> run(project));
    }

    private static Unit run(Project project) {
        project.executeTarget("run");

        return Unit.unit();
    }

    record unused() implements PortBindAnt {}
}
