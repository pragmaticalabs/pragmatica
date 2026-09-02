package org.pragmatica.jbct.derive.pipeline;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.derive.Inline;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.jbct.derive.result.Pressure;

import static org.assertj.core.api.Assertions.assertThat;

/// Press stage: the Card-3 triage table maps each answer's shape/kind onto the axis it presses and
/// the mechanism family; unrecognised or non-pressing answers are inert; the combination check is
/// first-class.
class PressTest {
    private static final String HEAD = """
        schema_version = "0.1"
        [meta]
        system = "t"
        era    = "e"
        mode   = "greenfield"
        """;

    @Test
    void press_pressesPersistence_forVolumeShape() {
        var pressures = press(HEAD + q5("path:reads", "twelve billion reads a year", "volume"));

        assertThat(onAxis(pressures, Axis.PERSISTENCE)).singleElement()
                                                       .extracting(Pressure::mechanism)
                                                       .isEqualTo("volume-containment");
    }

    @Test
    void press_pressesSubstrate_forBurstAndDeadlineShapes() {
        var burst = press(HEAD + q5("operation:accept", "peak spike", "burst"));
        var deadline = press(HEAD + q5("operation:accept", "modal deadline", "deadline"));

        assertThat(onAxis(burst, Axis.SUBSTRATE)).singleElement()
                                                 .extracting(Pressure::mechanism)
                                                 .isEqualTo("burst-absorption");
        assertThat(onAxis(deadline, Axis.SUBSTRATE)).singleElement()
                                                    .extracting(Pressure::mechanism)
                                                    .isEqualTo("burst-absorption");
    }

    @Test
    void press_pressesReadWrite_forContentionShape() {
        var pressures = press(HEAD + q5("path:hot", "hot partition", "contention"));

        assertThat(onAxis(pressures, Axis.READ_WRITE)).singleElement()
                                                      .extracting(Pressure::mechanism)
                                                      .isEqualTo("contention-containment");
    }

    @Test
    void press_pressesReadWrite_forSystemClock_andInertForRequesterClock() {
        var system = press(HEAD + q1("path:render", "50ms budget", "system-clock"));
        var requester = press(HEAD + q1("operation:incorporate", "24 hours", "requester-clock"));

        assertThat(onAxis(system, Axis.READ_WRITE)).singleElement().matches(Pressure::moves);
        assertThat(onAxis(requester, Axis.READ_WRITE)).singleElement().matches(pressure -> pressure.mode() == Pressure.Mode.INERT);
    }

    @Test
    void press_pressesState_forAuditKind() {
        var pressures = press(HEAD + q6("data-class:filings", "FOI duty", "audit", ""));

        assertThat(onAxis(pressures, Axis.STATE)).singleElement()
                                                 .extracting(Pressure::mechanism)
                                                 .isEqualTo("audit-log");
    }

    @Test
    void press_excludesScope_forMandateWithoutStrikeAtNarrowScope() {
        var pressures = press(HEAD + q6("data-class:card-data", "PCI — never sees card data", "mandate", ""));

        assertThat(onAxis(pressures, Axis.TOPOLOGY)).singleElement()
                                                    .matches(pressure -> pressure.mode() == Pressure.Mode.EXCLUDE);
    }

    @Test
    void press_doesNotPress_forMandateWithStrikes() {
        var pressures = press(HEAD + q6("system", "publication duty", "mandate", "strikes = [\"substrate:private-only\"]"));

        assertThat(pressures.stream().filter(Pressure::moves).toList()).isEmpty();
    }

    @Test
    void press_findsCombination_whenTwoQuestionsConvergeOnAnAxis() {
        var result = Press.press(Inline.sheet(HEAD
                                              + q5("path:reads", "twelve billion reads", "volume")
                                              + q6("data-class:public", "residency", "residency", "")));

        assertThat(result.combinations()).singleElement()
                                         .extracting(combination -> combination.axis())
                                         .isEqualTo(Axis.PERSISTENCE);
    }

    @Test
    void press_noCombination_forSingleQuestionPressure() {
        var result = Press.press(Inline.sheet(HEAD + q5("path:reads", "volume", "volume")));

        assertThat(result.combinations()).isEmpty();
    }

    @Test
    void press_findsCombination_forIntraQuestionScopeDivergence() {
        var result = Press.press(Inline.sheet(HEAD
                                              + q5("path:a", "volume at a", "volume")
                                              + q5("path:b", "volume at b", "volume")));

        assertThat(result.combinations()).anySatisfy(combination -> assertThat(combination.axis()).isEqualTo(Axis.PERSISTENCE));
    }

    @Test
    void press_marksInert_forContainedThinTierLoad() {
        var pressures = press(HEAD + """
            [[answers.q5]]
            scope     = "path:cache"
            statement = "5.8B hits a day below 2% CPU"
            shape     = "volume"
            contained = true
            status    = "answered"
            """);

        assertThat(pressures.stream().filter(Pressure::moves).toList()).isEmpty();
        assertThat(pressures).singleElement().matches(pressure -> pressure.mode() == Pressure.Mode.INERT);
    }

    private static List<Pressure> press(String toml) {
        return Press.press(Inline.sheet(toml)).pressures();
    }

    private static List<Pressure> onAxis(List<Pressure> pressures, Axis axis) {
        return pressures.stream().filter(pressure -> pressure.axis() == axis).toList();
    }

    private static String q1(String scope, String statement, String shape) {
        return """
            [[answers.q1]]
            scope     = "%s"
            statement = "%s"
            shape     = "%s"
            price     = "stated"
            status    = "answered"
            """.formatted(scope, statement, shape);
    }

    private static String q5(String scope, String statement, String shape) {
        return """
            [[answers.q5]]
            scope     = "%s"
            statement = "%s"
            shape     = "%s"
            status    = "answered"
            """.formatted(scope, statement, shape);
    }

    private static String q6(String scope, String statement, String kind, String extra) {
        return """
            [[answers.q6]]
            scope     = "%s"
            statement = "%s"
            kind      = "%s"
            status    = "answered"
            %s
            """.formatted(scope, statement, kind, extra);
    }
}
