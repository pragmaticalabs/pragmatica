package org.pragmatica.aether.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/// A loopback endpoint that is a transport failure by construction (#3558): it accepts every connection and resets
/// it at once (SO_LINGER 0), so a client always gets an I/O failure and never an answer. It replaces a "held,
/// unreachable port", whose refusal rests on what the kernel does with a SYN under load (#3319, #3321). Test-only.
final class DeadEndpoint implements AutoCloseable {
    private final ServerSocket server;
    private final Thread acceptor;

    private DeadEndpoint(ServerSocket server) {
        this.server = server;
        this.acceptor = Thread.ofPlatform().daemon().name("dead-endpoint").unstarted(this::resetEveryConnection);
    }

    static DeadEndpoint start() throws IOException {
        var endpoint = new DeadEndpoint(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));

        endpoint.acceptor.start();
        return endpoint;
    }

    int port() {
        return server.getLocalPort();
    }

    private void resetEveryConnection() {
        while (!server.isClosed()) {
            acceptAndReset();
        }
    }

    private void acceptAndReset() {
        try (Socket socket = server.accept()) {
            socket.setSoLinger(true, 0);
        } catch (IOException _) {
            // closed while accepting: the loop condition ends the thread; a failed reset is the same outcome for the client
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
