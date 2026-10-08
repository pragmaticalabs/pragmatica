// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Cause;
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


/// Certificate rotation for one HTTP listener: the new TLS material is built BEFORE the running listener is touched.
/// A bundle that does not build is refused with a typed cause, the listener keeps serving its current certificate,
/// and the refusal is an operator event raised once on the transition (not per repeated attempt) with a recovery
/// event when a later rotation applies. Rotation never falls back to plain HTTP and never leaves the listener down
/// for a bundle that was knowable to be bad.
///
/// Not covered: a bind failure AFTER the old listener is stopped (the port taken in the gap) still leaves no listener.
public final class TlsRotation {
    private static final Logger log = LoggerFactory.getLogger(TlsRotation.class);

    private final String serverName;

    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private final AtomicBoolean refused = new AtomicBoolean();

    private TlsRotation(String serverName) {
        this.serverName = serverName;
    }

    public static TlsRotation tlsRotation(String serverName) {
        return new TlsRotation(serverName);
    }

    public void useSink(OperatorWarningSink operatorWarningSink) {
        sink.set(operatorWarningSink);
    }

    /// Builds every context the rotation will need. Failure refuses the rotation before anything is stopped.
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

        if (refused.compareAndSet(false, true)) {
            OperatorWarnings.raise(log,
                                   sink.get(),
                                   OperatorWarningCode.HTTP_TLS_ROTATION_REFUSED,
                                   serverName,
                                   "{}",
                                   typed.message());
        } else {
            log.error("{}", typed.message());
        }

        return typed.promise();
    }

    public void applied() {
        if (refused.compareAndSet(true, false)) {
            OperatorWarnings.raise(log,
                                   sink.get(),
                                   OperatorWarningCode.HTTP_TLS_ROTATION_RESTORED,
                                   serverName,
                                   "TLS certificate rotation of HTTP server '{}' applied again; the listener serves the rotated certificate.",
                                   serverName);
        }
    }
}
