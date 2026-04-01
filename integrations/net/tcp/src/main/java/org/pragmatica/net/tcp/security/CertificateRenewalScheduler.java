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

package org.pragmatica.net.tcp.security;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Schedules certificate renewal at 40% of remaining certificate validity (60% remaining).
///
/// When renewal succeeds, the callback receives a new [CertificateBundle].
/// New connections will use the new certificate; existing connections keep the old one
/// (fine for 7-day validity windows).
///
/// Retry strategy: exponential backoff starting at 5 minutes, capped at 4 hours.
public final class CertificateRenewalScheduler {
    private static final Logger log = LoggerFactory.getLogger(CertificateRenewalScheduler.class);
    private static final long RENEWAL_NUMERATOR = 40;
    private static final long RENEWAL_DENOMINATOR = 100;
    private static final long INITIAL_RETRY_MINUTES = 5;
    private static final long MAX_RETRY_MINUTES = 240;
    private static final double RETRY_MULTIPLIER = 3.0;

    private final CertificateProvider provider;
    private final String nodeId;
    private final String hostname;
    private final Consumer<CertificateBundle> renewalCallback;
    private final AtomicReference<Option<ScheduledFuture<?>>> scheduledTask = new AtomicReference<>(Option.none());
    private volatile Instant currentNotAfter;
    private volatile Instant lastRenewalAt;
    private volatile RenewalStatus renewalStatus = RenewalStatus.HEALTHY;
    private volatile int retryCount;

    /// Certificate renewal status for observability.
    public enum RenewalStatus {
        HEALTHY,
        RENEWING,
        FAILED
    }

    private CertificateRenewalScheduler(CertificateProvider provider,
                                        String nodeId,
                                        String hostname,
                                        Consumer<CertificateBundle> renewalCallback,
                                        Instant initialNotAfter) {
        this.provider = provider;
        this.nodeId = nodeId;
        this.hostname = hostname;
        this.renewalCallback = renewalCallback;
        this.currentNotAfter = initialNotAfter;
        this.lastRenewalAt = Instant.now();
    }

    /// Factory method.
    public static CertificateRenewalScheduler certificateRenewalScheduler(
            CertificateProvider provider,
            String nodeId,
            String hostname,
            Consumer<CertificateBundle> renewalCallback,
            Instant initialNotAfter) {
        return new CertificateRenewalScheduler(provider, nodeId, hostname,
                                               renewalCallback, initialNotAfter);
    }

    /// Start the renewal scheduler.
    public void start() {
        scheduleNextRenewal();
        log.info("Certificate renewal scheduler started for node {}", nodeId);
    }

    /// Stop the renewal scheduler.
    public void stop() {
        scheduledTask.getAndSet(Option.none())
                     .onPresent(task -> task.cancel(false));
        log.info("Certificate renewal scheduler stopped");
    }

    private void scheduleNextRenewal() {
        var renewalDelay = calculateRenewalDelay();

        if (renewalDelay.isNegative() || renewalDelay.isZero()) {
            performRenewal();
            return;
        }

        scheduledTask.set(Option.some(SharedScheduler.schedule(this::performRenewal, TimeSpan.timeSpan(renewalDelay.toMillis()).millis())));
        log.info("Next certificate renewal in {}", formatDuration(renewalDelay));
    }

    private void performRenewal() {
        log.info("Renewing certificate for node {}", nodeId);
        renewalStatus = RenewalStatus.RENEWING;

        provider.issueCertificate(nodeId, hostname)
                .onSuccess(this::handleRenewalSuccess)
                .onFailure(this::handleRenewalFailure);
    }

    private void handleRenewalSuccess(CertificateBundle bundle) {
        currentNotAfter = bundle.notAfter();
        lastRenewalAt = Instant.now();
        renewalStatus = RenewalStatus.HEALTHY;
        retryCount = 0;
        renewalCallback.accept(bundle);
        log.info("Certificate renewed, valid until {}", currentNotAfter);
        scheduleNextRenewal();
    }

    private void handleRenewalFailure(org.pragmatica.lang.Cause cause) {
        renewalStatus = RenewalStatus.FAILED;
        retryCount++;
        var retryDelayMinutes = calculateRetryDelay();
        log.error("Certificate renewal failed (attempt {}): {}. Retrying in {} minutes.",
                  retryCount, cause.message(), retryDelayMinutes);
        scheduledTask.set(Option.some(SharedScheduler.schedule(this::performRenewal,
                                                               TimeSpan.timeSpan(retryDelayMinutes).minutes())));
    }

    private long calculateRetryDelay() {
        var delayMinutes = (long) (INITIAL_RETRY_MINUTES * Math.pow(RETRY_MULTIPLIER, retryCount - 1));
        return Math.min(delayMinutes, MAX_RETRY_MINUTES);
    }

    private Duration calculateRenewalDelay() {
        var remaining = Duration.between(Instant.now(), currentNotAfter);
        return remaining.multipliedBy(RENEWAL_NUMERATOR).dividedBy(RENEWAL_DENOMINATOR);
    }

    /// Get the certificate expiry timestamp.
    public Instant currentNotAfter() {
        return currentNotAfter;
    }

    /// Get the seconds until certificate expiry.
    public long secondsUntilExpiry() {
        return Duration.between(Instant.now(), currentNotAfter).toSeconds();
    }

    /// Get the timestamp of the last successful renewal.
    public Instant lastRenewalAt() {
        return lastRenewalAt;
    }

    /// Get the current renewal status.
    public RenewalStatus renewalStatus() {
        return renewalStatus;
    }

    private static String formatDuration(Duration d) {
        var hours = d.toHours();
        var minutes = d.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
