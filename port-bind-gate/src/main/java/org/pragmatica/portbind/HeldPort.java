package org.pragmatica.portbind;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;

import com.sun.net.httpserver.HttpServer;


/// The one place a test builds a port-0 socket by itself (#1826, #3801). The port-bind gate refuses every other bind call in
/// a compiled test class, so a port a test uses comes from here, from a module's `ClusterTestPorts` reservation, or from
/// a server that binds port 0 itself and reports the port back.
///
/// The socket comes back OPEN, bound to the port the kernel chose, and holds that port until the caller closes it, so the port
/// is safe to use for exactly as long as the socket is open: `try (var held = HeldPort.udp()) { ... held.getLocalPort() ... }`.
/// A port read here and used after the close is the pick-then-rebind hazard (another process can take it first): the helper
/// returns the socket (or the server), never a bare port number, so the lifetime stays in the caller's hands and in plain sight.
///
/// Every method throws what the JDK constructor it wraps throws, so a call site that moved here compiles with the same handlers.
///
/// JBCT rules suppressed on this class: this module may depend on nothing in this repository (every module with `src/test` gets it
/// at test scope, so a dependency on `core` is a reactor cycle), which leaves `Result` and `Option` unavailable, and the class
/// is a command-line tool whose boundary is the JDK's (`throws`, `Optional`, a `void main`).
@SuppressWarnings({"JBCT-EX-01", "JBCT-RET-01", "JBCT-BND-01"})
public final class HeldPort {
    private static final String LOOPBACK = "127.0.0.1";

    private HeldPort() {}

    /// A UDP socket bound to a kernel-chosen wildcard port, open.
    public static DatagramSocket udp() throws SocketException {
        return new DatagramSocket(0);
    }

    /// A UDP socket bound to a kernel-chosen port on `address`, open.
    public static DatagramSocket udp(InetAddress address) throws SocketException {
        return new DatagramSocket(0, address);
    }

    /// A TCP server socket bound to a kernel-chosen wildcard port, open.
    public static ServerSocket tcp() throws IOException {
        return new ServerSocket(0);
    }

    /// A TCP server socket bound to a kernel-chosen port on `address` with the given accept `backlog`, open.
    public static ServerSocket tcp(int backlog, InetAddress address) throws IOException {
        return new ServerSocket(0, backlog, address);
    }

    /// A JDK HTTP server bound to a kernel-chosen port on `127.0.0.1`: the server holds its own port from `create` until the caller
    /// stops it, and `getAddress().getPort()` reads the bound port back, so there is no pick-then-rebind. The caller starts it.
    public static HttpServer httpServer() throws IOException {
        return httpServer(InetAddress.getByName(LOOPBACK));
    }

    /// As [#httpServer()], on `address`.
    public static HttpServer httpServer(InetAddress address) throws IOException {
        return HttpServer.create(new InetSocketAddress(address, 0), 0);
    }
}
