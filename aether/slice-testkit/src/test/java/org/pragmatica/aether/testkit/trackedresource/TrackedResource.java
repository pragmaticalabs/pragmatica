// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.trackedresource;

import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;


/// A resource that records whether it was actually closed (#892).
///
/// It extends [AsyncCloseable] rather than the JDK's `AutoCloseable` on purpose: that is the
/// convention `ResourceFactory`'s default close reaches through the #891 fix, so a release that
/// arrives here proves the whole dispatch ran, not merely that some close was attempted.
///
/// An interface rather than a record because the slice processor refuses a resource dependency
/// parameter that is not one ("Dependency parameter must be an interface, found: RECORD").
///
/// This type deliberately lives OUTSIDE the packaged slice's own package. The release-identity
/// fixture ships only that package into its slice jar, so this class resolves through the slice
/// loader's PARENT and the `Class` the generated factory passes to `provide(...)` is the same one
/// the node's factory registry holds. Packaging it would reproduce #773 instead — a different
/// defect, and one that would make a red result there ambiguous.
public interface TrackedResource extends AsyncCloseable {
    String config();

    boolean isClosed();

    static TrackedResource trackedResource(String config) {
        record trackedResource(String config, AtomicBoolean closedFlag) implements TrackedResource {
            @Override
            public Promise<Unit> close() {
                closedFlag.set(true);

                return Promise.unitPromise();
            }

            @Override
            public boolean isClosed() {
                return closedFlag.get();
            }
        }

        return new trackedResource(config, new AtomicBoolean(false));
    }
}
