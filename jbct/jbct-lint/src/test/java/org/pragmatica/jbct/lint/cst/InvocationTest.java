package org.pragmatica.jbct.lint.cst;

import org.pragmatica.jbct.lint.cst.RunOutcome.Command;
import org.pragmatica.jbct.lint.cst.RunOutcome.DensityLimit;
import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;
import org.pragmatica.jbct.lint.cst.RunOutcome.Invocation;
import org.pragmatica.jbct.lint.cst.RunOutcome.Surface;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// What an [Invocation] refuses or drops: a format action and `failBeforeFormat` exist only for the commands that take
/// them (#2525, memo3318 acceptance ruling 2).
class InvocationTest {
    @Test
    void format_withNoAction_isRefused() {
        Invocation.format(Surface.CLI, FormatAction.NONE)
                  .onSuccess(_ -> fail("a format without an action was accepted"))
                  .onFailure(cause -> assertEquals(LintRunError.General.FORMAT_ACTION_REQUIRED, cause));
    }

    @Test
    void format_withAnAction_keepsItAndAsksOnlyFormat() {
        Invocation.format(Surface.MAVEN, FormatAction.DRY_RUN)
                  .onFailure(cause -> fail("refused: " + cause.message()))
                  .onSuccess(invocation -> {
                      assertEquals(FormatAction.DRY_RUN, invocation.format());
                      assertEquals(new Activities(false, FormatAction.DRY_RUN, false, false), invocation.activities());
                  });
    }

    @Test
    void canonicalConstructor_dropsAFormatActionTheCommandDoesNotTake() {
        var invocation = new Invocation(Command.LINT, Surface.CLI, FormatAction.WRITE, false, Option.none(), Option.none());

        assertEquals(FormatAction.NONE, invocation.format());
        assertEquals(new Activities(true, FormatAction.NONE, false, false), invocation.activities());
    }

    @Test
    void canonicalConstructor_dropsFailBeforeFormatOutsideProcess() {
        var check = new Invocation(Command.CHECK, Surface.MAVEN, FormatAction.NONE, true, Option.none(), Option.none());

        assertFalse(check.failBeforeFormat());
        assertFalse(check.activities().withholdFormatOnLintErrors());
    }

    @Test
    void process_carriesFailBeforeFormat_andAsksLintAndWrite() {
        var invocation = Invocation.process(Surface.MAVEN, true);

        assertTrue(invocation.failBeforeFormat());
        assertEquals(new Activities(true, FormatAction.WRITE, false, true), invocation.activities());
    }

    @Test
    void canonicalConstructor_dropsTheDensityLimitOutsideScore() {
        var limit = Option.some(DensityLimit.densityLimit(5.0, "--max-density"));
        var lint = new Invocation(Command.LINT, Surface.CLI, FormatAction.NONE, false, limit, Option.none());

        assertTrue(lint.maxDensity().isEmpty());
        assertTrue(Invocation.score(Surface.CLI, limit).maxDensity().isPresent());
    }
}
