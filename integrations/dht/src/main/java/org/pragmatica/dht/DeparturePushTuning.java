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


/// The time knobs of a graceful-departure push (#2034).
///
/// - `baseBudget`: how long every page is resent. A replicated page stops here; its key still has a surviving replica.
/// - `soleExtra`: the extra window a sole-copy page gets after `baseBudget` (a page whose keys no surviving replica holds).
/// - `resendAfter`: how long one attempt waits for an ack before the page is resent, and the pause after a refused send.
/// - `nackBackoffCap`: a nacked page is resent after `min(resendAfter, nackBackoffCap)`, doubling per nack up to this cap.
/// - `eventDeliveryWait`: the tail of the window reserved for the events the push raises to land before the node halts.
///
/// Every window is also clipped by the [DepartureWindow] deadline, so a late start shortens them and never extends the drain.
public record DeparturePushTuning(TimeSpan baseBudget,
                                  TimeSpan soleExtra,
                                  TimeSpan resendAfter,
                                  TimeSpan nackBackoffCap,
                                  TimeSpan eventDeliveryWait) {
    /// 10 s base, 5 s sole extra, 1 s resend, 4 s nack cap, 2 s event wait. [taste, marked: settle by forge drain timings]
    public static final DeparturePushTuning DEFAULT = new DeparturePushTuning(timeSpan(10).seconds(),
                                                                              timeSpan(5).seconds(),
                                                                              timeSpan(1).seconds(),
                                                                              timeSpan(4).seconds(),
                                                                              timeSpan(2).seconds());

    /// The legacy budget-only form: `budget` is the whole resend window, with no sole extra, and an attempt waits at most
    /// `min(1 s, budget / 4)`.
    public static DeparturePushTuning forBudget(TimeSpan budget) {
        var resend = Math.max(1L,
                              Math.min(DEFAULT.resendAfter().nanos(),
                                       budget.nanos() / 4));

        return new DeparturePushTuning(budget,
                                       timeSpan(0).nanos(),
                                       timeSpan(resend).nanos(),
                                       DEFAULT.nackBackoffCap(),
                                       DEFAULT.eventDeliveryWait());
    }
}
