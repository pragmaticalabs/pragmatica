package org.pragmatica.jbct.lint.cst;

import org.pragmatica.jbct.lint.cst.RunOutcome.FormatAction;


/// The activities an invocation asks for, derived from its command (#2525). Package-private: a front end names a
/// command, never an activity set, so no front end can ask for a combination no command takes.
record Activities(boolean lint, FormatAction format, boolean score, boolean withholdFormatOnLintErrors) {
    static Activities activities(boolean lint, FormatAction format, boolean score, boolean withhold) {
        return new Activities(lint, format, score, withhold);
    }
}
