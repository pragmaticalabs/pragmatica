package org.pragmatica.jbct.init;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/// Test support: reads a [Result] as a concrete value so `assertThat` never has to infer a lambda's type.
public final class ResultViews {
    private ResultViews() {}

    /// The failure cause, or null when the result is a success (assertions then fail on the type check).
    public static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    /// The ref the outcome names, or the failure message.
    public static String refOf(Result<AiToolsOutcome> result) {
        return result.fold(Cause::message, AiToolsOutcome::ref);
    }

    /// The tag of the ref, or the failure message.
    public static String tagOf(Result<AiToolsRef> result) {
        return result.fold(Cause::message, AiToolsRef::tag);
    }

    /// Number of files the outcome installed, or -1 on failure.
    public static int installedCount(Result<AiToolsOutcome> result) {
        return result.fold(_ -> -1, outcome -> outcome.installed().size());
    }

    /// True when the outcome is a success that installed and skipped nothing.
    public static boolean isEmptyOutcome(Result<AiToolsOutcome> result) {
        return result.fold(_ -> false, AiToolsOutcome::isEmpty);
    }

    /// The update the check found, `none` when current, or `failure`.
    public static String checkText(Result<Option<String>> result) {
        return result.fold(_ -> "failure", found -> found.or("none"));
    }
}
