package org.pragmatica.postgres.net;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

@FunctionalInterface
public interface Listening {

    Promise<Unit> unlisten();
}
