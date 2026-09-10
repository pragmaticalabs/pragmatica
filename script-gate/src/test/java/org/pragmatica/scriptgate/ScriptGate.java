package org.pragmatica.scriptgate;

import java.nio.file.Path;

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

    /// The outcome of running a script; a harness failure fails the test rather than being asserted on.
    static ScriptRunner.Execution executed(Result<ScriptRunner.Execution> result) {
        return result.onFailure(cause -> fail("could not run the script: " + cause.message()))
                     .or(ScriptRunner.Execution.execution(ScriptRunner.TIMED_OUT, ""));
    }

    record unused() implements ScriptGate {}
}
