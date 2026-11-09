// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

/// Marks a [ResourceFactory] whose resource one node may share between slices (#2973): SQL datasource pools.
///
/// **What the marker claims.** The factory's output depends only on `C`. It reads no `ProvisioningContext` extension
/// and no ambient static state, and it overrides neither `provision(C, ProvisioningContext)` nor `sectionBinder()`.
/// [SpiResourceProvider] checks the two overrides when it indexes its factories and DEMOTES a marker that declares
/// either one to a per-slice factory, with one WARN naming the class. The ambient-state clause is a reviewed claim and
/// cannot be checked mechanically: a factory that reads `ConfigService` statically (the notification listener) must not
/// carry this marker.
///
/// Two slices on one node share the factory's resource iff their [#shareKey(Object)] values are `equals`. The provider
/// never hands the shared instance out: each holding slice receives its own [#view(Object, Fn0)], whose `stop()` and
/// `close()` release only that holder. The instance closes when its last holder releases it.
///
/// @param <T> the resource type
/// @param <C> the configuration type
public interface NodeShareable<T, C> {
    /// The identity of the pool `provision(config)` would build, built from EFFECTIVE values: exactly the inputs that
    /// `provision` reads, so an absent default and the same default written explicitly give one key, and any input that
    /// changes the connection gives another. `none()` keeps the resource per slice (`pool_scope = "slice"`).
    Option<Object> shareKey(C config);

    /// The handle one holder receives. Its `stop()` and `close()` invoke `release` on the first call only and succeed
    /// without effect on every later call; after the release, every operation fails instead of reaching `pool`.
    T view(T pool, Fn0<Promise<Unit>> release);

    /// What a pool built from `config` asks of its server, for the node's connection-demand guard.
    PoolDemand demand(C config);

    /// A query reporting how many non-superuser connections `pool`'s server allows, or `none()` when the guard does not
    /// apply to this server type.
    Option<Promise<ServerProbe>> probe(T pool);

    /// A server as the guard identifies it: the configured `(type, host, port)` until the server answers a probe, then
    /// the answered `(addr, port)`, so several spellings of one server meet in one sum.
    record ServerId(String type, String host, int port) {
        public static ServerId serverId(String type, String host, int port) {
            return new ServerId(type, host, port);
        }

        public String subject() {
            return host + ":" + port;
        }
    }

    /// A pool's demand: `warm` connections opened at provisioning, up to `max` under load, against `configured`. The probe
    /// runs bounded by `probeTimeout` (the pool's connection timeout). `endpoint` labels the pool in logs and never
    /// carries a secret.
    record PoolDemand(int warm, int max, ServerId configured, TimeSpan probeTimeout, String endpoint) {
        public static PoolDemand poolDemand(int warm, int max, ServerId configured, TimeSpan probeTimeout, String endpoint) {
            return new PoolDemand(warm, max, configured, probeTimeout, endpoint);
        }
    }

    /// A probe's answer: the non-superuser connections the server allows, and the address it answered from (`none()` for
    /// a unix socket).
    record ServerProbe(int available, Option<String> addr, int port) {
        public static ServerProbe serverProbe(int available, Option<String> addr, int port) {
            return new ServerProbe(available, addr, port);
        }
    }
}
