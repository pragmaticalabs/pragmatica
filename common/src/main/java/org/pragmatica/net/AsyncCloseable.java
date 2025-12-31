package org.pragmatica.net;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/**
 * Interface for resources that can be closed asynchronously.
 */
public interface AsyncCloseable {
    Promise<Unit> close();
}
