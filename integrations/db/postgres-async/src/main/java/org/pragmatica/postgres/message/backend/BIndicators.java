package org.pragmatica.postgres.message.backend;

import org.pragmatica.postgres.message.BackendMessage;

/**
 * @author Marat Gainullin
 */
public sealed interface BIndicators extends BackendMessage {
    record ParseComplete() implements BIndicators {}
    record CloseComplete() implements BIndicators {}
    record BindComplete() implements BIndicators {}
    record NoData() implements BIndicators {}

    ParseComplete PARSE_COMPLETE = new ParseComplete();
    CloseComplete CLOSE_COMPLETE = new CloseComplete();
    BindComplete BIND_COMPLETE = new BindComplete();
    NoData NO_DATA = new NoData();
}
