package org.pragmatica.net.redis;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/// A loopback port nothing listens on, for tests that expect a refused connect. Test-only, so blocking I/O and
/// checked exceptions are fine here.
///
/// Why not "bind a [ServerSocket], read its port, close it": between the close and the connect another process
/// (parallel modules, `-T 1C`) can be handed the same ephemeral port and listen on it, and the connect then
/// succeeds (#3319).
///
/// On Linux (CI) the test HOLDS the port for its whole duration: a plain bound TCP socket that never calls listen.
/// Bound, so the kernel keeps the port out of every other bind to port 0 and out of every outgoing connect's
/// source-port choice (which also rules out a TCP self-connect to it); `SO_REUSEADDR` is off, so no other socket can
/// bind the same port beside it; not listening, so a SYN to it is answered with RST and the client sees "connection
/// refused". Verified on Linux (RST, rival bind refused).
///
/// macOS does not answer a SYN to a bound, non-listening port with RST: it drops it and the connect times out
/// (verified on macOS 26). No hold that refuses exists there, so off Linux the port is released at once, as before:
/// the race remains on a developer machine, where nothing else runs in parallel, and CI is Linux.
final class UnreachablePort implements AutoCloseable {
    private final Socket holder;
    private final int port;

    static UnreachablePort hold() throws IOException {
        return System.getProperty("os.name").startsWith("Linux")
               ? held()
               : released();
    }

    private static UnreachablePort held() throws IOException {
        var socket = new Socket();

        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return new UnreachablePort(socket, socket.getLocalPort());
    }

    private static UnreachablePort released() throws IOException {
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // an unbound placeholder: nothing is held off Linux, see the class comment
            return new UnreachablePort(new Socket(), probe.getLocalPort());
        }
    }

    private UnreachablePort(Socket holder, int port) {
        this.holder = holder;
        this.port = port;
    }

    int port() {
        return port;
    }

    @Override
    public void close() throws IOException {
        holder.close();
    }
}
