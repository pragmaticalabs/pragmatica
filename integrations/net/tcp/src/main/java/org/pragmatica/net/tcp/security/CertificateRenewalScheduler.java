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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// Schedules certificate renewal at 50% of remaining certificate validity.
///
/// When renewal succeeds, the callback receives a new [CertificateBundle].
/// New connections will use the new certificate; existing connections keep the old one
/// (fine for 7-day validity windows).
public final class CertificateRenewalScheduler {
    private static final Logger log = LoggerFactory.getLogger(CertificateRenewalScheduler.class);
    private static final long RENEWAL_NUMERATOR = 50;
    private static final long RENEWAL_DENOMINATOR = 100;
    private static final long RETRY_HOURS = 1;

    private final CertificateProvider provider;
    private final String nodeId;
    private final String hostname;
    private final Consumer<CertificateBundle> renewalCallback;
    private final ScheduledExecutorService scheduler;
    private volatile Instant currentNotAfter;

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
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::createDaemonThread);
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
        scheduler.shutdownNow();
        log.info("Certificate renewal scheduler stopped");
    }

    private Thread createDaemonThread(Runnable r) {
        var t = new Thread(r, "cert-renewal");
        t.setDaemon(true);
        return t;
    }

    private void scheduleNextRenewal() {
        var renewalDelay = calculateRenewalDelay();

        if (renewalDelay.isNegative() || renewalDelay.isZero()) {
            performRenewal();
            return;
        }

        scheduler.schedule(this::performRenewal, renewalDelay.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Next certificate renewal in {}", formatDuration(renewalDelay));
    }

    private void performRenewal() {
        log.info("Renewing certificate for node {}", nodeId);

        provider.issueCertificate(nodeId, hostname)
                .onSuccess(this::handleRenewalSuccess)
                .onFailure(this::handleRenewalFailure);
    }

    private void handleRenewalSuccess(CertificateBundle bundle) {
        currentNotAfter = bundle.notAfter();
        renewalCallback.accept(bundle);
        log.info("Certificate renewed, valid until {}", currentNotAfter);
        scheduleNextRenewal();
    }

    private void handleRenewalFailure(org.pragmatica.lang.Cause cause) {
        log.error("Certificate renewal failed: {}. Retrying in 1 hour.", cause.message());
        scheduler.schedule(this::performRenewal, RETRY_HOURS, TimeUnit.HOURS);
    }

    private Duration calculateRenewalDelay() {
        var remaining = Duration.between(Instant.now(), currentNotAfter);
        return remaining.multipliedBy(RENEWAL_NUMERATOR).dividedBy(RENEWAL_DENOMINATOR);
    }

    private static String formatDuration(Duration d) {
        var hours = d.toHours();
        var minutes = d.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
