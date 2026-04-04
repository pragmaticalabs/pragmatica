package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;


/// Errors that can occur when accessing SliceRuntime services.
public sealed interface SliceRuntimeError extends Cause {
    enum InvokerNotConfigured implements SliceRuntimeError {
        INSTANCE;
        @Override public String message() {
            return "SliceInvoker not configured. " + "This typically means the slice is being used outside of the Aether runtime.";
        }
    }
}
