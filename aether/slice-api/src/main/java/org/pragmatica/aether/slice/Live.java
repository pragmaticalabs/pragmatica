// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.concurrent.AtomicHolder;
import org.pragmatica.lang.utils.Causes;


/// The latest value of a [Watched] configuration section, for pull access. Never empty.
///
/// Correct ONLY because deliveries to one subscription are serial (an [AtomicHolder] has no conditional set);
/// the callback is a non-blocking `holder.set`, so a hung-callback caveat cannot bite it.
public interface Live<T> {
    Cause NOTHING_DELIVERED = Causes.cause("Watched configuration delivered no value at registration");
    T get();

    static <T> Result<Live<T>> live(Watched<T> watched) {
        var holder = AtomicHolder.<T> atomicHolder();

        return watched.watch(value -> store(holder, value))
                      .flatMap(_ -> holder.get()
                                          .toResult(NOTHING_DELIVERED))
                      .map(first -> () -> holder.get()
                                                .or(first));
    }

    private static <T> Promise<Unit> store(AtomicHolder<T> holder, T value) {
        holder.set(value);

        return Promise.unitPromise();
    }
}
