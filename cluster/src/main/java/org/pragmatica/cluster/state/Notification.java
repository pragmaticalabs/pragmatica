package org.pragmatica.cluster.state;

/// State machine notifications root.
public interface Notification {
    <T extends Command> T cause();
}
