package org.pragmatica.witness;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;


/// Leaves a marker file under `target/test-plan-witness` for exactly as long as a test plan is executing
/// (#1344). The marker is written before the first test runs and deleted only from
/// [#testPlanExecutionFinished], which JUnit does not reach when a Throwable escapes the engine — the path
/// that let a fork say goodbye and exit 0 with five classes unrun. The root pom's `test-plan-gate` profile
/// fails the build when a marker survives the test phase.
///
/// The module directory comes from the `basedir` system property surefire and failsafe set in the fork;
/// without it (an IDE run, a plain launcher) the witness does nothing, so it never litters a source tree.
/// The marker lives in its own directory rather than in `surefire-reports`, because the fork cannot tell
/// whether surefire or failsafe launched it and the gate reads the same directory after either.
public final class TestPlanWitness implements TestExecutionListener {
    static final String MARKER_SUFFIX = ".plan-incomplete";
    static final String BASEDIR_PROPERTY = "basedir";
    static final String WITNESS_DIR = "target/test-plan-witness";

    private final Path witnessDir;
    private volatile Path marker;

    /// The ServiceLoader entry point: rooted at the fork's `basedir`, inactive without one.
    public TestPlanWitness() {
        this(witnessDirFromBasedir());
    }

    private TestPlanWitness(Path witnessDir) {
        this.witnessDir = witnessDir;
    }

    /// A witness rooted at an explicit module directory, for pinning the mechanism without touching the
    /// running fork's own marker.
    public static TestPlanWitness rootedAt(Path basedir) {
        return new TestPlanWitness(basedir.resolve(WITNESS_DIR));
    }

    private static Path witnessDirFromBasedir() {
        var basedir = System.getProperty(BASEDIR_PROPERTY);

        return basedir == null
               ? null
               : Path.of(basedir, WITNESS_DIR);
    }

    /// `void` is JUnit's contract for a listener, not a choice made here.
    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void testPlanExecutionStarted(TestPlan testPlan) {
        if (witnessDir == null) {
            return;
        }

        marker = write(witnessDir.resolve(markerName()), testPlan);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void testPlanExecutionFinished(TestPlan testPlan) {
        var current = marker;

        if (current == null) {
            return;
        }

        try {
            Files.deleteIfExists(current);
        } catch (IOException e) {
            warn("cannot remove " + current + " (" + e + "); the gate will fail this module");
        }
    }

    /// The marker's absolute path, or `null` when the witness is inactive.
    public Path marker() {
        return marker;
    }

    /// The path is kept even when the write fails, so the finish is still a harmless delete; the failure
    /// itself can only be shouted, because a witness that cannot write has nothing for the gate to read.
    private static Path write(Path path, TestPlan testPlan) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content(testPlan));
        } catch (IOException e) {
            warn("cannot write " + path + " (" + e + "); this plan is unwitnessed");
        }

        return path;
    }

    private static String markerName() {
        return Instant.now()
                      .toString()
                      .replace(':', '-')
             + "-" + ManagementFactory.getRuntimeMXBean().getPid() + MARKER_SUFFIX;
    }

    /// One header line, then the planned top-level containers by their reporting name (the class name for a
    /// test class): a reader of a surviving marker sees what the fork set out to run.
    private static List<String> content(TestPlan testPlan) {
        var header = "# test plan started " + Instant.now()
                   + "; this file is removed when the plan finishes. "
                   + "If it survived, the fork ended before running everything it planned (#1344).";

        return Stream.concat(Stream.of(header),
                             roots(testPlan))
                     .toList();
    }

    private static Stream<String> roots(TestPlan testPlan) {
        return testPlan.getRoots()
                       .stream()
                       .flatMap(root -> testPlan.getChildren(root)
                                                .stream())
                       .map(TestIdentifier::getLegacyReportingName)
                       .sorted();
    }

    private static void warn(String message) {
        System.err.println("[test-plan-witness] " + message);
    }
}
