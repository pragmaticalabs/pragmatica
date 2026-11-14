package org.pragmatica.net.redis;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// A scripted RESP server over a plain socket: each accepted connection runs the given session on its
/// own thread. Test-only, so blocking I/O and checked exceptions are fine here.
final class FakeRedisServer implements AutoCloseable {
    interface Session {
        void run(Conn conn) throws Exception;
    }

    /// One accepted connection.
    record Conn(Socket socket, InputStream in, OutputStream out) {
        /// Read one command: an array of bulk strings. Throws on EOF.
        List<String> readCommand() throws IOException {
            var count = Integer.parseInt(line().substring(1));
            var parts = new ArrayList<String>();

            for (int i = 0; i < count; i++) {
                var size = Integer.parseInt(line().substring(1));
                var body = in.readNBytes(size);

                in.readNBytes(2);
                parts.add(new String(body, StandardCharsets.ISO_8859_1));
            }

            return parts;
        }

        private String line() throws IOException {
            var text = new StringBuilder();
            int next;

            while ((next = in.read()) != '\r') {
                if (next < 0) {
                    throw new IOException("eof");
                }
                text.append((char) next);
            }

            in.read();
            return text.toString();
        }

        void write(String wire) throws IOException {
            out.write(wire.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        /// Write one byte at a time with a flush after each, so replies arrive as fragments.
        void writeFragmented(String wire) throws IOException {
            for (byte b : wire.getBytes(StandardCharsets.ISO_8859_1)) {
                out.write(b);
                out.flush();
                sleepQuietly();
            }
        }

        private static void sleepQuietly() {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }

        void drop() throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }
    }

    private final ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final List<List<String>> received = new CopyOnWriteArrayList<>();

    static FakeRedisServer start(Session session) throws IOException {
        var fake = new FakeRedisServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));

        Thread.ofVirtual()
              .start(() -> fake.acceptLoop(session));
        return fake;
    }

    private FakeRedisServer(ServerSocket server) {
        this.server = server;
    }

    int port() {
        return server.getLocalPort();
    }

    /// Every command any connection read through [#record], in arrival order.
    List<List<String>> received() {
        return received;
    }

    List<String> recordCommand(Conn conn) throws IOException {
        var command = conn.readCommand();

        received.add(command);
        return command;
    }

    int connections() {
        return accepted.size();
    }

    private void acceptLoop(Session session) {
        try {
            while (!server.isClosed()) {
                var socket = server.accept();

                accepted.add(socket);
                Thread.ofVirtual()
                      .start(() -> serve(socket, session));
            }
        } catch (IOException e) {
            // server closed
        }
    }

    private static void serve(Socket socket, Session session) {
        try (socket) {
            session.run(new Conn(socket, new BufferedInputStream(socket.getInputStream()), socket.getOutputStream()));
        } catch (Exception e) {
            // peer went away or the script ended: the test asserts on the client side
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        accepted.forEach(FakeRedisServer::closeQuietly);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // already closed
        }
    }
}
