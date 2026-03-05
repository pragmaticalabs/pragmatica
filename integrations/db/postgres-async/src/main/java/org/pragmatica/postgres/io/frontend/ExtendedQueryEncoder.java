package org.pragmatica.postgres.io.frontend;

import org.pragmatica.postgres.message.ExtendedQueryMessage;

public abstract class ExtendedQueryEncoder<M extends ExtendedQueryMessage> extends SkipableEncoder<M> {
}
