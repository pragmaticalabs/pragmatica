package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;
import org.pragmatica.witness.TestPlanWitness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.assertj.core.api.Assertions.assertThat;


/// #1344 - a surefire fork that ends its test plan early and then says goodbye exits 0, and the
/// build reads as green with every later class unrun. The witness is the evidence the root pom's
/// `test-plan-gate` profile reads afterwards: a marker written when the plan starts and removed
/// only when it finishes.
///
/// Two things are pinned, in two different ways, because they fail differently.
///
/// The MECHANISM is driven directly against a scratch module directory with a real discovered plan:
/// the marker exists while the plan is open, names what was planned, and is gone once the plan is
/// finished. Not calling `testPlanExecutionFinished` is the ticket's condition - JUnit does not reach
/// it from a finally block - so the marker's survival in that case is the property, asserted as
/// such rather than inferred from the happy path.
///
/// The WIRING is observed from inside this very fork: while this class runs, the plan that contains
/// it is open, so a marker naming this class must exist in this module's own witness directory. That marker is written by the ServiceLoader-registered witness on the real test
/// classpath, rooted at the `basedir` surefire sets. Remove the profile's dependency, the service
/// file, or the property, and this test reddens while the mechanism test stays green - which is why
/// both exist.
class PlanWitnessTest {
    private static final String MARKER_SUFFIX = ".plan-incomplete";
    private static final Path WITNESS_DIR = Path.of("target", "test-plan-witness");

    /// A class the mechanism test plans. Its binary name `PlanWitnessTest$PlannedFixture` matches none of
    /// surefire's default patterns (`Test*`, `*Test`, `*Tests`, `*TestCase`), so surefire never runs it
    /// itself - which is why the outer class is not named `Test...`.
    static class PlannedFixture {
        @Test
        void planned() {}
    }

    @Test
    void marker_existsWhilePlanIsOpen_namesThePlan_andIsGoneOnceFinished(@TempDir Path module) {
        var witness = TestPlanWitness.rootedAt(module);
        var plan = discover(PlannedFixture.class);

        witness.testPlanExecutionStarted(plan);
        var marker = witness.marker();

        assertThat(marker).isNotNull().exists().hasParent(module.resolve(WITNESS_DIR));
        assertThat(marker.getFileName().toString()).endsWith(MARKER_SUFFIX);
        assertThat(lines(marker)).anyMatch(line -> line.startsWith("# test plan started"))
                  .contains(PlannedFixture.class.getName());
        witness.testPlanExecutionFinished(plan);
        assertThat(marker).doesNotExist();
    }

    @Test
    void marker_survives_whenThePlanNeverFinishes(@TempDir Path module) {
        var witness = TestPlanWitness.rootedAt(module);

        witness.testPlanExecutionStarted(discover(PlannedFixture.class));
        assertThat(markersIn(module.resolve(WITNESS_DIR))).hasSize(1);
    }

    @Test
    void finishing_aPlanThatWasNeverStarted_touchesNothing(@TempDir Path module) {
        var witness = TestPlanWitness.rootedAt(module);

        witness.testPlanExecutionFinished(discover(PlannedFixture.class));
        assertThat(witness.marker()).isNull();
        assertThat(module.resolve(WITNESS_DIR)).doesNotExist();
    }

    @Test
    void thisFork_isWitnessed_byAMarkerNamingThisClass() {
        var basedir = System.getProperty("basedir");

        assertThat(basedir).as("surefire sets basedir in the fork; the witness roots there").isNotNull();
        var markers = markersIn(Path.of(basedir).resolve(WITNESS_DIR));

        assertThat(markers).as("the open plan's marker, written by the ServiceLoader-registered witness").isNotEmpty();
        assertThat(markers.stream().flatMap(marker -> lines(marker).stream())).contains(PlanWitnessTest.class.getName());
    }

    private static TestPlan discover(Class<?> planned) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                                                                          .selectors(selectClass(planned))
                                                                          .build();

        return LauncherFactory.create().discover(request);
    }

    private static List<Path> markersIn(Path reports) {
        return Result.lift(() -> {
            try (Stream<Path> paths = Files.list(reports)) {
                return paths.filter(path -> path.getFileName()
                                                .toString()
                                                .endsWith(MARKER_SUFFIX))
                            .toList();
            }
        }).or(List.of());
    }

    private static List<String> lines(Path file) {
        return Result.lift(() -> Files.readAllLines(file)).or(List.of());
    }
}
