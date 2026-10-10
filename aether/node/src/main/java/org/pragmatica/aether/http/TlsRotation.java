// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.TlsContextFactory;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Certificate rotation for one listener, and certificate renewal for the cluster transport: the new TLS material is
/// built BEFORE anything running is touched. A bundle that does not build is refused with a typed cause, the running
/// listener keeps serving its current certificate, and the refusal is an operator event raised once on the transition
/// (not per repeated attempt) with a recovery event when a later rotation or renewal applies. Nothing falls back to
/// plain HTTP. A bundle is refused when any context it needs does not build, and the context factories refuse a
/// private key that does not match its certificate; nothing else about a bundle is checked, so a bundle that passes can
/// still be rejected by peers (untrusted CA, wrong name).
///
/// Not covered: a bind failure AFTER the old listener is stopped (the port taken in the gap) still leaves no listener.
public final class TlsRotation {
    private static final Logger log = LoggerFactory.getLogger(TlsRotation.class);

    private final String serverName;
    private final String subjectKind;
    private final OperatorWarningCode refusedCode;
    private final OperatorWarningCode restoredCode;

    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private final AtomicBoolean refused = new AtomicBoolean();

    private TlsRotation(String serverName,
                        String subjectKind,
                        OperatorWarningCode refusedCode,
                        OperatorWarningCode restoredCode) {
        this.serverName = serverName;
        this.subjectKind = subjectKind;
        this.refusedCode = refusedCode;
        this.restoredCode = restoredCode;
    }

    /// Rotation of one HTTP listener (`management`, `app-http`).
    public static TlsRotation tlsRotation(String serverName) {
        return new TlsRotation(serverName,
                               "HTTP server",
                               OperatorWarningCode.HTTP_TLS_ROTATION_REFUSED,
                               OperatorWarningCode.HTTP_TLS_ROTATION_RESTORED);
    }

    /// Renewal of the cluster transport's certificate (the QUIC server and client contexts).
    public static TlsRotation clusterRenewal() {
        return new TlsRotation("cluster-quic",
                               "transport",
                               OperatorWarningCode.CLUSTER_TLS_RENEWAL_REFUSED,
                               OperatorWarningCode.CLUSTER_TLS_RENEWAL_RESTORED);
    }

    @Contract
    public void useSink(OperatorWarningSink operatorWarningSink) {
        sink.set(operatorWarningSink);
    }

    /// Builds every context the rotation will need. Failure refuses the rotation before anything is stopped. The context
    /// factories also refuse a private key that does not belong to the certificate, which builds without error otherwise
    /// and then completes no handshake.
    public Result<Unit> validate(CertificateBundle bundle, boolean includesH1, boolean includesH3) {
        var h1 = includesH1
                 ? TlsContextFactory.createServer(serverConfig(bundle)).mapToUnit()
                 : Result.<Unit> success(unit());

        return h1.flatMap(_ -> includesH3
                               ? QuicSslContextFactory.createServerFromBundle(bundle, ClientAuthPolicy.NOT_REQUESTED).mapToUnit()
                               : Result.<Unit> success(unit()));
    }

    /// Same identity the restart builds: server authentication only (#967).
    public static TlsConfig serverConfig(CertificateBundle bundle) {
        return new TlsConfig.Server(new TlsConfig.Identity.FromProvider(bundle.certificatePem(), bundle.privateKeyPem()),
                                    Option.<TlsConfig.Trust> none());
    }

    public <T> Promise<T> refuse(Cause cause) {
        var typed = new HttpServerError.TlsRotationRefused(serverName, cause);

        raiseRefused(typed.message());

        return typed.promise();
    }

    /// A renewal refused where there is no promise to fail (the scheduler's callback): log it and raise the event.
    @Contract
    public void renewalRefused(Cause cause) {
        raiseRefused("TLS certificate renewal of the cluster " + subjectKind
                    + " refused, keeping the current certificate: " + cause.message());
    }

    private void raiseRefused(String message) {
        if (refused.compareAndSet(false, true)) {
            OperatorWarnings.raise(log, sink.get(), refusedCode, serverName, "{}", message);
        } else {
            log.error("{}", message);
        }
    }

    @Contract
    public void applied() {
        if (refused.compareAndSet(true, false)) {
            OperatorWarnings.raise(log,
                                   sink.get(),
                                   restoredCode,
                                   serverName,
                                   "TLS certificate of {} '{}' applied again; it serves the new certificate.",
                                   subjectKind,
                                   serverName);
        }
    }
}
