package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;


/// The fixture test classes the port-bind gate (#3801) is pinned against, as source compiled at test time into a scratch
/// directory (`fx.Forms`, `fx.EveryApi`, `fx.Clean`, and stand-ins for the Netty types, which this module does not link).
///
/// They are compiled here and not kept as test sources because this module's own test classes are scanned by the gate: a class
/// that really calls `new ServerSocket(computedPort)` in `src/test` would fail this module's build, which is the point of the
/// gate and the reason the fixtures cannot live where it looks.
final class PortBindFixtures {
    private PortBindFixtures() {}

    /// Every way into a bind that the issue's failure input lists, plus the forms the verification rounds found. Each method is
    /// named for the form; the pin asserts a site in each by name.
    static final String FORMS = """
        package fx;

        import java.net.DatagramSocket;
        import java.net.InetSocketAddress;
        import java.net.MulticastSocket;
        import java.net.ServerSocket;
        import java.net.Socket;
        import java.util.concurrent.Callable;

        public class Forms {
            interface Opener {
                ServerSocket open(int port) throws Exception;
            }

            static final Callable<ServerSocket> STATIC_INIT = () -> new ServerSocket(0);
            final Callable<DatagramSocket> fieldInit = () -> new DatagramSocket(0);

            ServerSocket computed(int computedPort) throws Exception {
                return new ServerSocket(computedPort + 1);
            }

            ServerSocket zeroLiteral() throws Exception {
                return new ServerSocket(0);
            }

            ServerSocket spelledOddly() throws Exception {
                return new ServerSocket((int) 0x0);
            }

            void aliasedAddress() throws Exception {
                var address = new InetSocketAddress(0);
                var socket = new Socket();

                socket.bind(address);
            }

            Opener methodReference() {
                return ServerSocket::new;
            }

            Callable<ServerSocket> insideLambda(int port) {
                return () -> new ServerSocket(port);
            }

            DatagramSocket noArgBindsAWildcardPort() throws Exception {
                return new DatagramSocket();
            }

            MulticastSocket multicast() throws Exception {
                return new MulticastSocket(0);
            }

            void throughSubtypeReceiver(Narrow narrow) throws Exception {
                narrow.bind(new InetSocketAddress(0));
            }

            static class Narrow extends ServerSocket {
                Narrow() throws Exception {
                    super();
                }
            }

            static class Superclass extends ServerSocket {
                Superclass() throws Exception {
                    super(0);
                }
            }
        }
        """;

    /// One method per type in the gate's API set, so a type added to the set without a fixture goes red in the pin.
    static final String EVERY_API = """
        package fx;

        import com.sun.net.httpserver.HttpServer;
        import io.netty.bootstrap.ServerBootstrap;
        import io.netty.channel.Channel;
        import java.net.DatagramSocket;
        import java.net.InetSocketAddress;
        import java.net.ServerSocket;
        import java.net.Socket;
        import java.nio.channels.AsynchronousServerSocketChannel;
        import java.nio.channels.DatagramChannel;
        import java.nio.channels.ServerSocketChannel;
        import javax.net.ServerSocketFactory;

        public class EveryApi {
            void serverSocket() throws Exception {
                new ServerSocket(0);
            }

            void datagramSocket() throws Exception {
                new DatagramSocket(0);
            }

            void socket() throws Exception {
                new Socket().bind(new InetSocketAddress(0));
            }

            void networkChannel() throws Exception {
                ServerSocketChannel.open().bind(null);
                DatagramChannel.open().bind(null);
                AsynchronousServerSocketChannel.open().bind(null);
            }

            void serverSocketFactory() throws Exception {
                ServerSocketFactory.getDefault().createServerSocket(0);
            }

            void httpServer() throws Exception {
                HttpServer.create(new InetSocketAddress(0), 0);
            }

            void nettyBootstrap() {
                new ServerBootstrap().bind("127.0.0.1", 0);
            }

            void nettyChannel(Channel channel) {
                channel.bind(new InetSocketAddress(0));
            }
        }
        """;

    /// What a test may do with sockets without binding one: connect out, build an address, take a held socket from HeldPort and
    /// read its port while it is open.
    static final String CLEAN = """
        package fx;

        import java.net.InetSocketAddress;
        import java.net.Socket;
        import org.pragmatica.portbind.HeldPort;

        public class Clean {
            Socket connectsOut(int port) throws Exception {
                return new Socket("localhost", port);
            }

            InetSocketAddress anAddress() {
                return new InetSocketAddress(0);
            }

            int heldPortRead() throws Exception {
                try (var held = HeldPort.udp()) {
                    return held.getLocalPort();
                }
            }
        }
        """;

    /// The Netty types the gate names, reduced to the members the fixtures call: this module links no Netty, and the gate resolves a
    /// receiver type from the scanned classes themselves.
    private static final List<String> NETTY_STUBS = List.of("""
                                                            package io.netty.channel;

                                                            public interface ChannelOutboundInvoker {
                                                                Object bind(java.net.SocketAddress address);
                                                            }
                                                            """,
                                                            """
                                                            package io.netty.channel;

                                                            public interface Channel extends ChannelOutboundInvoker {}
                                                            """,
                                                            """
                                                            package io.netty.bootstrap;

                                                            public abstract class AbstractBootstrap {
                                                                public Object bind(String host, int port) {
                                                                    return null;
                                                                }
                                                            }
                                                            """,
                                                            """
                                                            package io.netty.bootstrap;

                                                            public class ServerBootstrap extends AbstractBootstrap {}
                                                            """);

    /// Compiles the fixtures and the Netty stand-ins under `root` and returns the directory holding the class files.
    static Path compiled(Path root) {
        var sources = root.resolve("src");
        var classes = root.resolve("classes");
        var files = List.of(write(sources, "fx/Forms.java", FORMS),
                            write(sources, "fx/EveryApi.java", EVERY_API),
                            write(sources, "fx/Clean.java", CLEAN),
                            write(sources, "io/netty/channel/ChannelOutboundInvoker.java", NETTY_STUBS.get(0)),
                            write(sources, "io/netty/channel/Channel.java", NETTY_STUBS.get(1)),
                            write(sources, "io/netty/bootstrap/AbstractBootstrap.java", NETTY_STUBS.get(2)),
                            write(sources, "io/netty/bootstrap/ServerBootstrap.java", NETTY_STUBS.get(3)));

        return Result.lift(() -> Files.createDirectories(classes))
                     .map(dir -> javac(dir, files))
                     .unwrap();
    }

    /// The line (1-based) of the first line of `source` that contains `fragment`.
    static int lineOf(String source, String fragment) {
        return (int) source.lines()
                           .takeWhile(line -> !line.contains(fragment))
                           .count() + 1;
    }

    private static Path write(Path sources, String name, String text) {
        var file = sources.resolve(name);

        return Result.lift(() -> Files.createDirectories(file.getParent()))
                     .flatMap(dir -> Result.lift(() -> Files.writeString(file, text)))
                     .unwrap();
    }

    private static Path javac(Path classes, List<Path> files) {
        var arguments = Stream.concat(Stream.of("-d",
                                                classes.toString(),
                                                "-cp",
                                                System.getProperty("java.class.path")),
                                      files.stream().map(Path::toString))
                              .toArray(String[]::new);
        var status = ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments);

        assertThat(status).as("the port-bind fixtures must compile").isZero();

        return classes;
    }
}
