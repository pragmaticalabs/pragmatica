package org.pragmatica.aether.invoke;

import org.pragmatica.lang.ContextPropagation;
import org.pragmatica.lang.Unit;

/// Aether implementation of context propagation for Promise async operations.
///
///
/// This implementation captures and restores the InvocationContext (request ID)
/// across async boundaries in Promise operations. It is loaded via ServiceLoader
/// when the aether module is present on the classpath.
///
///
/// The context snapshot captures the current request ID and restores it when
/// the async action runs, ensuring distributed tracing correlation across
/// all async operations in the system.
public final class AetherContextPropagation implements ContextPropagation {
    @Override
    public Object capture() {
        return InvocationContext.captureContext();
    }

    @Override
    public Unit runWith(Object snapshot, Runnable action) {
        if (snapshot instanceof InvocationContext.ContextSnapshot contextSnapshot) {
            contextSnapshot.runWithCaptured(action);
        } else {
            action.run();
        }
        return Unit.unit();
    }
}
