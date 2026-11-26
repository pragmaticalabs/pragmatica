package org.pragmatica.portbind;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/// The set of socket-bind and port-allocation APIs the port-bind gate refuses in test classes (#3801), as data. Each entry is a
/// type and the members of it (and of every subtype) that bind: `ServerSocket` and `DatagramSocket` allocate a port in every
/// constructor (`new DatagramSocket()` binds a wildcard ephemeral port), and `bind` on a socket, a channel
/// (`NetworkChannel` is the supertype of every NIO and NIO.2 channel), a Netty bootstrap or channel, or the JDK HTTP server binds
/// an address. The argument never matters: the port may be a literal, a computed value or 0, and the call is flagged.
///
/// A call is matched by the reference the compiler wrote: owner, name and descriptor. The owner is the static receiver type, so a
/// call through a subtype (`MulticastSocket`, `ServerSocketChannel`, a test's own subclass) does not carry the supertype's name;
/// the owner is therefore resolved against the scan's class loader and matched when it is assignable to the entry's type, and
/// by exact name when it cannot be loaded.
///
/// What this set does NOT contain, on purpose: `InetSocketAddress` constructors (an address is not a bind; the `bind` that
/// receives it is flagged), client sockets that connect, and the repository's own server-start entry points (they are handed a
/// port that came from a reservation, which is a decision about where the port came from, not a bind the scan can see).
/// Reflection and method handles looked up by name at run time are invisible to a bytecode scan.
///
/// JBCT rules suppressed on this class: this module may depend on nothing in this repository (every module with `src/test` gets it
/// at test scope, so a dependency on `core` is a reactor cycle), which leaves `Result` and `Option` unavailable, and the class
/// is a command-line tool whose boundary is the JDK's (`throws`, `Optional`, a `void main`).
@SuppressWarnings({"JBCT-EX-01", "JBCT-RET-01", "JBCT-BND-01"})
final class BindApis {
    /// The members of one type that bind. `member` answers for a member's name and descriptor.
    record Api(String type, MemberTest member) {}

    @FunctionalInterface
    interface MemberTest {
        boolean binds(String name, String descriptor);
    }

    private static final String CONSTRUCTOR = "<init>";
    private static final MemberTest ANY_CONSTRUCTOR = (name, descriptor) -> name.equals(CONSTRUCTOR);
    private static final MemberTest BIND = (name, descriptor) -> name.equals("bind");

    static final List<Api> ALL = List.of(new Api("java.net.ServerSocket", either(ANY_CONSTRUCTOR, BIND)),
                                         new Api("java.net.DatagramSocket", either(ANY_CONSTRUCTOR, BIND)),
                                         new Api("java.net.Socket", BIND),
                                         new Api("java.nio.channels.NetworkChannel", BIND),
                                         new Api("javax.net.ServerSocketFactory", named("createServerSocket")),
                                         new Api("com.sun.net.httpserver.HttpServer",
                                                 either(BIND,
                                                        (name, descriptor) -> name.equals("create") && descriptor.startsWith("(Ljava/net/InetSocketAddress;"))),
                                         new Api("io.netty.bootstrap.AbstractBootstrap", BIND),
                                         new Api("io.netty.channel.ChannelOutboundInvoker", BIND));

    /// Stands for a class that could not be loaded, so a failed load is remembered like a successful one.
    private static final Class<?> ABSENT = Void.class;

    private final Map<String, Class<?>> loaded = new ConcurrentHashMap<>();
    private final ClassLoader loader;

    BindApis(ClassLoader loader) {
        this.loader = loader;
    }

    /// The reference (`owner#name`, owner as the binary name the compiler wrote) when `owner.name descriptor` is a bind API, or empty.
    List<String> match(String owner, String name, String descriptor) {
        return ALL.stream()
                  .filter(api -> api.member()
                                    .binds(name, descriptor))
                  .filter(api -> isOrExtends(owner.replace('/', '.'),
                                             api.type()))
                  .map(api -> owner.replace('/', '.') + "#" + name)
                  .limit(1)
                  .toList();
    }

    private boolean isOrExtends(String ownerName, String type) {
        if (ownerName.equals(type)) {
            return true;
        }

        var ownerClass = load(ownerName);
        var apiClass = load(type);

        return ownerClass != ABSENT
               && apiClass != ABSENT
               && apiClass.isAssignableFrom(ownerClass);
    }

    private Class<?> load(String name) {
        return loaded.computeIfAbsent(name, this::tryLoad);
    }

    /// Loads without initialising. A class that cannot be loaded here (absent from this module's classpath, or one whose own
    /// dependencies are) leaves only the exact-name match, which is the safe direction: it can only miss a subtype's call.
    private Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return ABSENT;
        }
    }

    private static MemberTest named(String methodName) {
        return (name, descriptor) -> name.equals(methodName);
    }

    private static MemberTest either(MemberTest first, MemberTest second) {
        return (name, descriptor) -> first.binds(name, descriptor) || second.binds(name, descriptor);
    }
}
