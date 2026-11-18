package org.pragmatica.jbct.lint.cst;

import org.pragmatica.lang.Cause;


/// Failures of a [LintRun] itself, as opposed to a finding about a file (#2525): the run was asked for something its
/// plan cannot answer.
public sealed interface LintRunError extends Cause {
    /// Failures that name no value: a second [LintRun#perform], when the run's result was already handed out.
    enum General implements LintRunError {
        RUN_FINISHED("The lint run is already finished: build a new run for another pass"),
        FORMAT_ACTION_REQUIRED("The format command needs a format action: CHECK, DRY_RUN or WRITE, not NONE");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
