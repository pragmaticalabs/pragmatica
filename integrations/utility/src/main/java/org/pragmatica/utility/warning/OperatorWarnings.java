/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.utility.warning;

import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

import static org.pragmatica.lang.Result.lift;
import static org.pragmatica.lang.Unit.unit;


/// Reports an operator warning to both the log and the cluster event log, from one call site (#1574).
///
/// The log comes first and is unconditional. Warnings tend to fire exactly when the event log cannot
/// take them, for example during quorum loss, bootstrap or an event-stream outage. So the log line is
/// the floor, and the emitted event is an addition to it. The site's existing `{}` template is kept
/// word for word, prefixed with `[code]`, so a log search on either the old text or the code still finds
/// the line.
public sealed interface OperatorWarnings {
    /// Logs the warning at the level its code declares, then offers it to `sink`.
    ///
    /// This never throws and never blocks on emission. A sink that throws is logged at WARN and
    /// dropped. That is forward recovery: the log line written just before it is the report, and
    /// nothing is retried. Throttling is the sink's concern, because only the node's aggregator knows
    /// the window. So every call logs, and only the emit is rate-limited.
    static Unit raise(Logger log,
                      OperatorWarningSink sink,
                      OperatorWarningCode code,
                      String subject,
                      String template,
                      Object... args) {
        var warning = OperatorWarning.operatorWarning(code,
                                                      subject,
                                                      MessageFormatter.arrayFormat(template, args).getMessage());

        logWarning(log, warning);

        return emit(log, sink, warning);
    }

    private static Unit emit(Logger log, OperatorWarningSink sink, OperatorWarning warning) {
        return lift(Causes::fromThrowable,
                    () -> sink.accept(warning)).onFailure(cause -> log.warn("[{}] operator warning for {} not emitted, the log line stands: {}",
                                                                            warning.code().code(),
                                                                            warning.subject(),
                                                                            cause.message()))
                   .or(unit());
    }

    private static Unit logWarning(Logger log, OperatorWarning warning) {
        switch (warning.code().level()) {
            case WARNING -> log.warn("[{}] {}",
                                     warning.code().code(),
                                     warning.message());
            case CRITICAL -> log.error("[{}] {}",
                                       warning.code().code(),
                                       warning.message());
        }

        return unit();
    }

    record unused() implements OperatorWarnings {}
}
