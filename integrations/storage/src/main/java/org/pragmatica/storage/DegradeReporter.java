package org.pragmatica.storage;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import static org.pragmatica.lang.Unit.unit;


/// How one storage instance tells an operator that it has degraded (A0b): one operator warning on the transition into a
/// condition, and the paired recovery code on the transition out of it, never one per tick.
///
/// A condition is OPEN from the moment it is raised until it is recovered; raising an open condition again does nothing,
/// which is what makes the warning transition-only. A condition whose code has no recovery stays open for the life of the
/// instance (a disk tier that failed to open is fixed by a restart).
///
/// **The sink arrives late.** Storage is built before the node's event aggregator exists, so the instance starts with the
/// log-only sink and the node binds the real one with [#bind]. A condition raised before then was logged when it was raised;
/// binding offers it to the sink once, without logging it a second time.
public final class DegradeReporter {
    private static final Logger log = LoggerFactory.getLogger(DegradeReporter.class);

    private final String instance;

    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private final Map<OperatorWarningCode, OperatorWarning> open = new ConcurrentHashMap<>();

    private DegradeReporter(String instance) {
        this.instance = instance;
    }

    /// A reporter for `instance`, bound to nothing: its warnings are logged until [#bind].
    public static DegradeReporter degradeReporter(String instance) {
        return new DegradeReporter(instance);
    }

    /// Routes this instance's warnings to `sink` and offers it the conditions already open.
    public Unit bind(OperatorWarningSink sink) {
        this.sink.set(sink);
        open.values().forEach(sink::accept);

        return unit();
    }

    /// Opens `code` for this instance and raises it, once. Returns true when this call was the transition.
    public boolean degraded(OperatorWarningCode code, String template, Object... args) {
        var warning = OperatorWarning.operatorWarning(code,
                                                      instance,
                                                      MessageFormatter.arrayFormat(template, args).getMessage());

        if (open.putIfAbsent(code, warning) != null) {
            return false;
        }

        OperatorWarnings.raise(log, sink.get(), code, instance, "{}", warning.message());

        return true;
    }

    /// Closes `code` for this instance and raises its recovery, once. Returns true when this call was the transition: a
    /// condition that was not open, or whose code has no recovery, raises nothing.
    public boolean recovered(OperatorWarningCode code, String template, Object... args) {
        if (open.remove(code) == null) {
            return false;
        }

        return recoveryOf(code).map(recovery -> raiseRecovery(recovery, template, args))
                         .or(false);
    }

    /// True while `code` is open for this instance.
    public boolean isDegraded(OperatorWarningCode code) {
        return open.containsKey(code);
    }

    private boolean raiseRecovery(OperatorWarningCode recovery, String template, Object... args) {
        OperatorWarnings.raise(log, sink.get(), recovery, instance, template, args);

        return true;
    }

    private static Option<OperatorWarningCode> recoveryOf(OperatorWarningCode code) {
        return Option.from(Arrays.stream(OperatorWarningCode.values())
                                 .filter(other -> other.recoveryOf()
                                                       .filter(code::equals)
                                                       .isPresent())
                                 .findFirst());
    }
}
