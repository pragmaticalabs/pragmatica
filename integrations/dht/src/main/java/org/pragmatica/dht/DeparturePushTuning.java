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

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The time knobs of a graceful-departure push (#2034, #3345).
///
/// - `resendAfter`: how long one attempt waits for an ack before the page is resent, the pause after a refused send, and the
///   deadline of the pre-exit probe of a holder (silence past it is a failed probe).
/// - `nackBackoffCap`: a nacked page is resent after `min(resendAfter, nackBackoffCap)`, doubling per nack up to this cap.
/// - `reportAfter`: how long an unfinished drain runs before it reports what is still owed (used by the hold, a later step).
/// - `eventDeliveryWait`: the tail of the window reserved for the events the push raises to land before the node halts.
///
/// The run itself ends at the [DepartureWindow] deadline less `eventDeliveryWait`, or earlier when nothing is owed.
public record DeparturePushTuning(TimeSpan resendAfter,
                                  TimeSpan nackBackoffCap,
                                  TimeSpan reportAfter,
                                  TimeSpan eventDeliveryWait) {
    /// 1 s resend, 4 s nack cap, 15 s report, 2 s event wait. [taste, marked: settle by forge drain timings]
    public static final DeparturePushTuning DEFAULT = new DeparturePushTuning(timeSpan(1).seconds(),
                                                                              timeSpan(4).seconds(),
                                                                              timeSpan(15).seconds(),
                                                                              timeSpan(2).seconds());
}
