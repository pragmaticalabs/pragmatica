// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;


public interface ResourceFactory<T, C> {
    Class<T> resourceType();
    Class<C> configType();
    Promise<T> provision(C config);

    default Promise<T> provision(C config, ProvisioningContext context) {
        return provision(config);
    }

    default int priority() {
        return 0;
    }

    default boolean supports(C config) {
        return true;
    }

    /// Default unload: close the resource through whichever close convention it implements.
    ///
    /// TWO conventions are recognised, and the project's own comes FIRST: [AsyncCloseable]
    /// (`Promise<Unit> close()`, `org.pragmatica.lang.io`) is awaited by returning its promise;
    /// the JDK's [AutoCloseable] is invoked synchronously. Dispatching on the JDK interface alone
    /// silently no-opped every `AsyncCloseable`-backed resource while REPORTING SUCCESS (#891),
    /// which is worse than a missed call because nothing upstream could tell.
    ///
    /// A resource implementing NEITHER is a named outcome — see [#noCloseConvention] — and is
    /// logged, so a third convention cannot be introduced and pass unnoticed the way the second
    /// one did. It logs at DEBUG rather than WARNING deliberately: value-like resources (method
    /// interceptors, stream handles) legitimately have nothing to close, and a warning per unload
    /// for each of them would train readers to ignore the message that matters.
    ///
    /// NOTE for anyone extending this: dispatch sees only the resource OBJECT. A resource that
    /// merely HOLDS a closeable (as the `@Http` client holds its Netty operations) is invisible
    /// here and must implement a convention itself or override this method — no amount of
    /// dispatch widening reaches it.
    ///
    /// A failed close is absorbed and logged in BOTH branches: the resource is released from the
    /// provider's cache regardless, and failing the whole `releaseAll` chain over one resource
    /// would block the release of every other. A factory whose close-time work is load-bearing
    /// (e.g. the durable entity's registration retraction) should still override this with a
    /// properly reported close of its own.
    default Promise<Unit> close(T resource) {
        return switch (resource) {
            case null -> Promise.unitPromise();
            case AsyncCloseable closeable -> closeable.close().recover(cause -> logAsyncCloseFailure(resource, cause));
            case AutoCloseable closeable -> closeSynchronously(resource, closeable);
            default -> noCloseConvention(resource);
        };
    }

    private static Unit logAsyncCloseFailure(Object resource, Cause cause) {
        logger().log(System.Logger.Level.WARNING,
                     "Resource close failed for " + resource.getClass()
                                                            .getName()
                     + " — the resource is released from the cache anyway: " + cause);

        return Unit.unit();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Promise<Unit> closeSynchronously(Object resource, AutoCloseable closeable) {
        return Promise.promise(promise -> {
            try {
                closeable.close();
            } catch (Exception e) {
                logger().log(System.Logger.Level.WARNING,
                             "Resource close failed for " + resource.getClass()
                                                                    .getName()
                             + " — the resource is released from the cache anyway",
                             e);
            }

            promise.succeed(Unit.unit());
        });
    }

    /// The "no recognised convention" outcome, named so it is greppable and logged so it is not
    /// silent. Reaching this for a resource that DOES own releasable state is the #891 defect
    /// class repeating itself.
    private static Promise<Unit> noCloseConvention(Object resource) {
        logger().log(System.Logger.Level.DEBUG,
                     "No close convention for " + resource.getClass()
                                                          .getName()
                     + " — implements neither AsyncCloseable nor AutoCloseable, so nothing was closed."
                     + " Override ResourceFactory.close(T) if this resource owns releasable state.");

        return Promise.unitPromise();
    }

    private static System.Logger logger() {
        return System.getLogger(ResourceFactory.class.getName());
    }
}
