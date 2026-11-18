package org.pragmatica.jbct.lint.cst;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The public surface of [LintRun] is two entry points: build a run over a plan, perform it over an invocation (#2525,
/// guarantee G1). No public method takes a path and records an outcome, so an outcome is constructed in one place only,
/// `FileAction.perform`; a front end cannot add a result the run did not return. Mutant M9 (a public
/// `record(Path, ...)`) turns the first test red.
class RunSurfaceTest {
    @Test
    void publicMethods_areLintRunAndPerformOnly() {
        var signatures = Arrays.stream(LintRun.class.getDeclaredMethods())
                               .filter(RunSurfaceTest::isPublic)
                               .filter(method -> !method.isSynthetic())
                               .map(RunSurfaceTest::signature)
                               .sorted()
                               .toList();

        assertEquals(List.of("lintRun(RootResolver$RootPlan)", "perform(RunOutcome$Invocation)"), signatures);
    }

    @Test
    void noPublicConstructorAndNoPublicField() {
        assertTrue(Arrays.stream(LintRun.class.getConstructors()).findAny().isEmpty());
        assertTrue(Arrays.stream(LintRun.class.getFields()).findAny().isEmpty());
    }

    private static boolean isPublic(Method method) {
        return Modifier.isPublic(method.getModifiers());
    }

    private static String signature(Method method) {
        return method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                                              .map(Class::getName)
                                              .map(name -> name.substring(name.lastIndexOf('.') + 1))
                                              .reduce((a, b) -> a + ", " + b)
                                              .orElse("") + ")";
    }
}
