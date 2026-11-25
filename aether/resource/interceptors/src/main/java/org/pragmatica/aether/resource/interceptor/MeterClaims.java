// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.lang.Unit;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;


/// Registration and removal of an interceptor's meters that stay correct when a new instance of one `(slice, section)`
/// is provisioned before the old one is closed (#280 R27).
///
/// The provider does not order the two: `SpiResourceProvider.releaseScope` detaches the old entry from its cache and only
/// then closes it, and a close of an entry whose provision has not resolved waits for it, so a provision of the same
/// key can run in between. A registry returns the EXISTING meter for an id it already holds, and removes by id, so a
/// plain register/remove pair would hand the new instance the old instance's meters and let the old close delete
/// them. A claim therefore displaces what holds the id and a release removes only what is still registered under its
/// own meter object; both run under one lock, so a release cannot interleave with a claim between its check and its
/// removal. The latest provision wins the id.
sealed interface MeterClaims {
    Object LOCK = new Object();

    /// Registers a fresh meter under `name` and `tags`, displacing any meter that already holds that id.
    static <M extends Meter> M claim(MeterRegistry registry, String name, Tags tags, Supplier<M> register) {
        synchronized (LOCK) {
            registry.find(name).tags(tags).meters().forEach(registry::remove);

            return register.get();
        }
    }

    /// Removes each of `owned` that is still the meter registered under its id.
    static Unit release(MeterRegistry registry, List<Meter> owned) {
        synchronized (LOCK) {
            owned.stream().filter(meter -> stillRegistered(registry, meter)).forEach(registry::remove);

            return Unit.unit();
        }
    }

    private static boolean stillRegistered(MeterRegistry registry, Meter meter) {
        return registry.find(meter.getId().getName())
                       .tags(meter.getId().getTagsAsIterable())
                       .meters()
                       .stream()
                       .anyMatch(candidate -> candidate == meter);
    }

    record unused() implements MeterClaims {}
}
