package org.pragmatica.jbct.lint.cst;

import java.nio.file.Path;

import org.pragmatica.lang.Cause;


/// Failures of a [LintRun] itself, as opposed to a finding about a file (#2525): the run was asked for something its
/// source roots cannot answer.
public sealed interface LintRunError extends Cause {
    /// A file that lies under none of the run's source roots. It has no mode and no configuration, so it is refused
    /// rather than linted with someone else's rules or skipped without a word.
    record OutsideSourceRoots(Path file, String message) implements LintRunError {
        public static OutsideSourceRoots outsideSourceRoots(Path file) {
            return new OutsideSourceRoots(file, "File " + file + " is under none of the run's source roots");
        }
    }

    /// Failures that name no value: a call after [LintRun#finish()], when the run's diagnostics and summary were
    /// already handed out, so a later file or a second finish would be counted nowhere.
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
