package org.pragmatica.aether.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/// A loopback endpoint that is a transport failure by construction (#3558): it accepts every connection and closes
/// it at once without reading or answering, so a client always gets an I/O failure and never an answer. Whether the
/// close reaches the client as a FIN or a reset does not matter to the claim, so neither is forced. It replaces a "held,
/// unreachable port", whose refusal rests on what the kernel does with a SYN under load (#3319, #3321). Test-only.
final class DeadEndpoint implements AutoCloseable {
    private final ServerSocket server;
    private final Thread acceptor;

    private DeadEndpoint(ServerSocket server) {
        this.server = server;
        this.acceptor = Thread.ofPlatform().daemon().name("dead-endpoint").unstarted(this::closeEveryConnection);
    }

    static DeadEndpoint start() throws IOException {
        var endpoint = new DeadEndpoint(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));

        endpoint.acceptor.start();
        return endpoint;
    }

    int port() {
        return server.getLocalPort();
    }

    private void closeEveryConnection() {
        while (!server.isClosed()) {
            acceptAndClose();
        }
    }

    private void acceptAndClose() {
        try (Socket _ = server.accept()) {
            // closed unanswered by the try block
        } catch (IOException _) {
            // closed while accepting: the loop condition ends the thread
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
