package org.pragmatica.portbind;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.SocketException;

/// The one place a test builds a port-0 socket by itself (#1826, #3801). The port-bind gate refuses every other bind call in
/// a compiled test class, so a port a test uses comes from here, from a module's `ClusterTestPorts` reservation, or from
/// a server that binds port 0 itself and reports the port back.
///
/// The socket comes back OPEN, bound to the port the kernel chose, and holds that port until the caller closes it, so the port
/// is safe to use for exactly as long as the socket is open: `try (var held = HeldPort.udp()) { ... held.getLocalPort() ... }`.
/// A port read here and used after the close is the pick-then-rebind hazard (another process can take it first): the helper
/// returns the socket, never a bare port number, so the lifetime stays in the caller's hands and in plain sight.
public final class HeldPort {
    private HeldPort() {}

    /// A UDP socket bound to a kernel-chosen port, open.
    public static DatagramSocket udp() throws SocketException {
        return new DatagramSocket(0);
    }

    /// A TCP server socket bound to a kernel-chosen port, open.
    public static ServerSocket tcp() throws IOException {
        return new ServerSocket(0);
    }
}
