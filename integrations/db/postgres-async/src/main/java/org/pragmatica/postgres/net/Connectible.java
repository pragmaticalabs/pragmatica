package org.pragmatica.postgres.net;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/**
 * General container of connections.
 *
 * @author Marat Gainullin
 */
public interface Connectible extends QueryExecutor {
    Promise<Connection> getConnection();

    Promise<Unit> close();
}
