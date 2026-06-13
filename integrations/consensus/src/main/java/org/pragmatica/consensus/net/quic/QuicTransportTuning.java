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

package org.pragmatica.consensus.net.quic;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

/// Tunables for QUIC consensus-stream resilience under burst load.
///
/// Two coordinated knobs address the consensus-send backpressure defect where a burst of
/// consensus commands (blueprint deploy → slice ACTIVATE) pushed the per-peer CONSENSUS
/// stream past Netty's write high-watermark, flipping `isWritable()` to false. The old code
/// silently dropped the message (relying on Rabia/SWIM retransmit), but retransmits hit the
/// same backpressure and also dropped, denying quorum until the 30s `cluster.apply` timeout.
///
/// 1. [#consensusRetryAttempts] / [#consensusRetryInterval] — wrap CONSENSUS sends in an async,
///    non-blocking [Retry] (delays scheduled via `SharedScheduler`, never blocking the Netty
///    event loop). The interval is SHORT so the retry catches the stream draining promptly
///    (QUIC writability flips back within tens of ms once the send buffer drains); window
///    coverage comes from the ATTEMPT COUNT, not a longer interval. The default 25ms × 200
///    attempts ≈ 5s budget — short, responsive, well under the 30s apply timeout. Most
///    messages resolve within the first few attempts once the stream drains; the high attempt
///    cap is the safety ceiling for a sustained burst.
/// 2. [#consensusWatermarkLowBytes] / [#consensusWatermarkHighBytes] — raise the CONSENSUS
///    stream's write-buffer high-watermark so command bursts fit before `isWritable()` flips
///    false at all. Netty's default is 32KB low / 64KB high; the defaults here lift that to
///    256KB low / 1MB high — a consensus command burst (typically a handful of KB-sized
///    framed protocol messages) fits without ever tripping backpressure.
///
/// Watermark thresholds are byte counts (ints, per Netty's `WriteBufferWaterMark` contract);
/// the retry interval is expressed entirely in [TimeSpan] (no hardcoded duration literals).
public record QuicTransportTuning(int consensusRetryAttempts,
                                  TimeSpan consensusRetryInterval,
                                  int consensusWatermarkLowBytes,
                                  int consensusWatermarkHighBytes) {

    /// Default consensus-send retry budget: a SHORT fixed interval polled MANY times rather
    /// than a longer interval. 25ms catches the stream drain promptly; 200 attempts cover a
    /// ≈5s worst-case sustained-burst window while staying well under the 30s `cluster.apply`
    /// deadline. The interval drives responsiveness; the attempt count drives window coverage.
    public static final int DEFAULT_RETRY_ATTEMPTS = 200;
    public static final TimeSpan DEFAULT_RETRY_INTERVAL = TimeSpan.timeSpan(25L).millis();

    /// CONSENSUS stream write-buffer watermarks. 256KB low / 1MB high — 16x Netty's default
    /// 64KB high. Sized so a deploy-time consensus command burst fits entirely in the write
    /// buffer before `isWritable()` flips false, eliminating backpressure refusals on the
    /// quorum-critical path under normal bursts (the retry above is the safety net for the
    /// pathological case).
    public static final int DEFAULT_WATERMARK_LOW_BYTES = 256 * 1024;
    public static final int DEFAULT_WATERMARK_HIGH_BYTES = 1024 * 1024;

    public static QuicTransportTuning defaults() {
        return new QuicTransportTuning(DEFAULT_RETRY_ATTEMPTS,
                                       DEFAULT_RETRY_INTERVAL,
                                       DEFAULT_WATERMARK_LOW_BYTES,
                                       DEFAULT_WATERMARK_HIGH_BYTES);
    }

    /// Build the async [Retry] used to wrap CONSENSUS sends. Fixed short interval — frequent
    /// polling for stream writability; window coverage scales with the attempt count.
    public Retry consensusRetry() {
        return Retry.retry()
                    .attempts(consensusRetryAttempts)
                    .strategy(BackoffStrategy.fixed().interval(consensusRetryInterval));
    }
}
