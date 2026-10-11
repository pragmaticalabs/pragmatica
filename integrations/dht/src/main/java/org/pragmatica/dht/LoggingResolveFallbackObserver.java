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
package org.pragmatica.dht;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.pragmatica.lang.Contract;


/// [ResolveFallbackObserver] that turns each outcome into one greppable line. An all-miss is a WARN carrying the
/// key hex, the verdict (see [ResolveMiss#verdict]) and the counts behind it; a fallback hit is an INFO. A client
/// serves keys whose absence is normal traffic, so `isWarnable` (on the key hex) decides which all-misses are
/// WARN and which go to the `debug` sink. The sinks are injected so this module keeps no logging-backend dependency
/// and a test can read exactly what was written.
public final class LoggingResolveFallbackObserver implements ResolveFallbackObserver {
    private final Predicate<String> isWarnable;
    private final Consumer<String> warn;
    private final Consumer<String> debug;
    private final Consumer<String> info;

    private LoggingResolveFallbackObserver(Predicate<String> isWarnable,
                                           Consumer<String> warn,
                                           Consumer<String> debug,
                                           Consumer<String> info) {
        this.isWarnable = isWarnable;
        this.warn = warn;
        this.debug = debug;
        this.info = info;
    }

    public static LoggingResolveFallbackObserver loggingResolveFallbackObserver(Predicate<String> isWarnable,
                                                                                Consumer<String> warn,
                                                                                Consumer<String> debug,
                                                                                Consumer<String> info) {
        return new LoggingResolveFallbackObserver(isWarnable, warn, debug, info);
    }

    @Override
    @Contract
    public void onResolvedViaFallback(String keyHex, int probed) {
        info.accept("DHT resolve via fallback key=" + keyHex + " probed=" + probed);
    }

    @Override
    @Contract
    public void onUnresolvedAfterFallback(ResolveMiss miss) {
        var sink = isWarnable.test(miss.keyHex())
                   ? warn
                   : debug;

        sink.accept(describe(miss));
    }

    static String describe(ResolveMiss miss) {
        return "DHT resolve all-miss key=" + miss.keyHex()
             + " verdict=" + miss.verdict()
             + " kind=" + miss.kind()
             + " elapsedMs=" + miss.elapsedMillis()
             + " lateValueFrom=" + (miss.lateValueFrom()
                                        .isEmpty()
                                    ? "none"
                                    : miss.lateValueFrom())
             + " departed=" + miss.departed()
             + " rSetAnswered=" + miss.rSetAnswered()
             + " rSetLive=" + miss.rSetLive()
             + " rSetSize=" + miss.rSetSize()
             + " probed=" + miss.probed()
             + " probesFailed=" + miss.probesFailed()
             + " unprobed=" + miss.unprobed();
    }
}
